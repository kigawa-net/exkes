package net.kigawa.exkes.api

import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import net.kigawa.exkes.api.runtime.InvalidRuntimeRequestException
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

/** Execution admission: only RUNNING runtimes accept new Executions (DEGRADED = NotReady is refused). */
class RuntimeServiceTest {
    private lateinit var dataSource: DataSource
    private lateinit var runtimes: RuntimeRepository
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var service: RuntimeService
    private lateinit var workspaceId: String
    private val templateId = "tpl-service-test"

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
        service = RuntimeService(runtimes, ExecutionRepository(), templates, workspaces)
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

    @Test
    fun `execution is accepted while runtime is running`() {
        val rt = runtimeIn(RuntimeStatus.RUNNING)
        assertEquals(rt.id, submit(rt.id).runtimeId)
    }

    @Test
    fun `execution is refused while runtime is degraded`() {
        val rt = runtimeIn(RuntimeStatus.DEGRADED)
        assertFailsWith<InvalidRuntimeRequestException> { submit(rt.id) }
    }

    @Test
    fun `execution is refused while runtime is still starting`() {
        val rt = runtimeIn(RuntimeStatus.STARTING)
        assertFailsWith<InvalidRuntimeRequestException> { submit(rt.id) }
    }
}
