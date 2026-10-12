package net.kigawa.exkes.api

import java.time.Instant
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import net.kigawa.exkes.api.runtime.InvalidRuntimeRequestException
import net.kigawa.exkes.api.runtime.RuntimeNotAdmittingException
import net.kigawa.exkes.api.runtime.RuntimeService
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.ExecutionType
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.WorkspaceStatus

/**
 * Execution admission: a new Execution needs RUNNING plus a fresh Ready observation from
 * exkes-controller (`last_ready_observed_at`). The controller side of these writes is
 * covered by RuntimeReconcilerTest; here the controller is simulated through
 * RuntimeRepository, since exkes-api cannot depend on exkes-controller.
 */
class RuntimeServiceTest {
    private lateinit var dataSource: DataSource
    private lateinit var runtimes: RuntimeRepository
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var service: RuntimeService
    private lateinit var workspaceId: String
    private val templateId = "tpl-service-test"
    private val freshness = 15L
    private var now: Instant = Instant.now()

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_runtime_service_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        runtimes = RuntimeRepository()
        workspaces = WorkspaceRepository()
        val templates = RuntimeTemplateRepository()
        templates.create(
            id = templateId,
            name = "t",
            description = null,
            image = "img",
            defaultResourcesJson = null,
            defaultEnvJson = null,
            defaultTtlSeconds = null,
            labelsJson = null,
        )
        val ws = workspaces.create("ws-${System.nanoTime()}", "sc", 1024L, emptyMap())
        workspaces.transition(ws.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY)
        workspaceId = ws.id
        service = RuntimeService(
            runtimes, ExecutionRepository(), templates, workspaces,
            admissionFreshnessSeconds = freshness,
            clock = { now },
        )
    }

    private fun runtimeIn(status: RuntimeStatus): Runtime {
        val rt = runtimes.create(workspaceId, templateId, null, null, null, null)
        val path = listOf(RuntimeStatus.STARTING, RuntimeStatus.RUNNING, RuntimeStatus.DEGRADED)
        var from = RuntimeStatus.CREATING
        for (to in path) {
            if (from == status) break
            assertEquals(true, runtimes.transition(rt.id, from, to))
            from = to
        }
        check(runtimes.findById(rt.id)!!.status == status) { "fixture could not reach $status" }
        return runtimes.findById(rt.id)!!
    }

    private fun submit(runtimeId: String) = runBlocking {
        service.createExecution(runtimeId, ExecutionType.COMMAND, """{"command":"true"}""", null)
    }

    /** Simulates the controller observing Ready=True at [now]. */
    private fun observeReady(id: String) = assertEquals(true, runtimes.recordReadyObserved(id, now))

    /** Simulates the controller observing Ready=False: admission closes at once. */
    private fun observeNotReady(id: String) = assertEquals(true, runtimes.clearReadyObservation(id))

    @Test
    fun `execution is accepted while runtime is running with a fresh ready observation`() {
        val rt = runtimeIn(RuntimeStatus.RUNNING)
        observeReady(rt.id)
        assertEquals(rt.id, submit(rt.id).runtimeId)
    }

    @Test
    fun `running runtime without any ready observation is refused`() {
        val rt = runtimeIn(RuntimeStatus.RUNNING)
        assertFailsWith<RuntimeNotAdmittingException> { submit(rt.id) }
    }

    @Test
    fun `execution is refused right after the pod turns not ready`() {
        val rt = runtimeIn(RuntimeStatus.RUNNING)
        observeReady(rt.id)
        now = now.plusSeconds(1)
        observeNotReady(rt.id)
        // Still RUNNING in the DB, and the Ready observation from 1s ago is still fresh.
        assertEquals(RuntimeStatus.RUNNING, runtimes.findById(rt.id)!!.status)
        assertFailsWith<RuntimeNotAdmittingException> { submit(rt.id) }
    }

    @Test
    fun `execution is refused when the controller stops observing and the ready observation goes stale`() {
        val rt = runtimeIn(RuntimeStatus.RUNNING)
        observeReady(rt.id)
        now = now.plusSeconds(freshness + 1)
        assertFailsWith<RuntimeNotAdmittingException> { submit(rt.id) }
    }

    @Test
    fun `staleness boundary does not depend on sub-second rounding`() {
        val rt = runtimeIn(RuntimeStatus.RUNNING)
        observeReady(rt.id)
        // Just past the limit, including a 1ms margin: 15.001s must be refused, 14.9s must still be admitted.
        now = now.plusSeconds(freshness).plusMillis(1)
        assertFailsWith<RuntimeNotAdmittingException> { submit(rt.id) }
    }

    @Test
    fun `execution is accepted again after the pod is ready again`() {
        val rt = runtimeIn(RuntimeStatus.RUNNING)
        observeReady(rt.id)
        now = now.plusSeconds(1)
        observeNotReady(rt.id)
        assertFailsWith<RuntimeNotAdmittingException> { submit(rt.id) }
        now = now.plusSeconds(1)
        observeReady(rt.id)
        assertEquals(rt.id, submit(rt.id).runtimeId)
    }

    @Test
    fun `execution is refused when the ttl has passed even with a fresh ready observation`() {
        val ttlRuntime = runtimes.create(workspaceId, templateId, null, null, 10L, null)
        assertEquals(true, runtimes.transition(ttlRuntime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        assertEquals(true, runtimes.transition(ttlRuntime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING))
        val created = runtimes.findById(ttlRuntime.id)!!.createdAt
        // Fresh Ready observation taken at the moment the TTL runs out (controller has not
        // yet moved the row to EXPIRED).
        now = created.plusSeconds(10)
        observeReady(ttlRuntime.id)
        assertFailsWith<RuntimeNotAdmittingException> { submit(ttlRuntime.id) }
    }

    @Test
    fun `execution is refused while runtime is degraded`() {
        val rt = runtimeIn(RuntimeStatus.DEGRADED)
        observeReady(rt.id)
        assertFailsWith<InvalidRuntimeRequestException> { submit(rt.id) }
    }

    @Test
    fun `execution is refused while runtime is still starting`() {
        val rt = runtimeIn(RuntimeStatus.STARTING)
        assertFailsWith<InvalidRuntimeRequestException> { submit(rt.id) }
    }
}
