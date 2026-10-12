package net.kigawa.exkes.common

import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.db.IdempotencyKeyMismatchException
import net.kigawa.exkes.common.db.IdempotencyRepository
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

class IdempotencyRepositoryTest {
    private lateinit var dataSource: DataSource
    private lateinit var idempotency: IdempotencyRepository
    private lateinit var executions: ExecutionRepository
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var runtimes: RuntimeRepository

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_idem_repo_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        idempotency = IdempotencyRepository()
        executions = ExecutionRepository()
        workspaces = WorkspaceRepository()
        runtimes = RuntimeRepository()
        RuntimeTemplateRepository().create(
            id = "idem-template",
            name = "Idem Template",
            description = null,
            image = "example/image:latest",
            defaultResourcesJson = null,
            defaultEnvJson = null,
            defaultTtlSeconds = null,
            labelsJson = null,
        )
    }

    private fun digest(value: String) = IdempotencyRepository.digestOf(JsonObject(mapOf("v" to JsonPrimitive(value))))

    @Test
    fun `same key and digest replays the first resource`() {
        val scope = "scope-replay-${System.nanoTime()}"
        val first = idempotency.execute(scope, "k1", digest("a"), { executions.findById(it) }, { createWorkspaceLikeExecution() }, { it.id })
        val second = idempotency.execute(scope, "k1", digest("a"), { executions.findById(it) }, { error("must not create again") }, { it.id })
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.value.id, second.value.id)
    }

    @Test
    fun `same key with a different digest is rejected`() {
        val scope = "scope-mismatch-${System.nanoTime()}"
        idempotency.execute(scope, "k1", digest("a"), { executions.findById(it) }, { createWorkspaceLikeExecution() }, { it.id })
        assertFailsWith<IdempotencyKeyMismatchException> {
            idempotency.execute(scope, "k1", digest("b"), { executions.findById(it) }, { error("must not create") }, { it.id })
        }
    }

    @Test
    fun `failed create leaves no idempotency record behind`() {
        val scope = "scope-rollback-${System.nanoTime()}"
        assertFailsWith<IllegalStateException> {
            idempotency.execute<net.kigawa.exkes.common.domain.Execution>(scope, "k1", digest("a"), { executions.findById(it) }, { error("boom") }, { it.id })
        }
        val retried = idempotency.execute(scope, "k1", digest("a"), { executions.findById(it) }, { createWorkspaceLikeExecution() }, { it.id })
        assertFalse(retried.replayed)
    }

    @Test
    fun `execution labels are filtered with AND semantics`() {
        val runtime = createRunningRuntime()
        val a = executions.create(runtime.id, ExecutionType.SYSTEM, null, null, labels = mapOf("run" to "r1", "step" to "build"))
        val b = executions.create(runtime.id, ExecutionType.SYSTEM, null, null, labels = mapOf("run" to "r1", "step" to "test"))
        executions.create(runtime.id, ExecutionType.SYSTEM, null, null, labels = mapOf("run" to "r2", "step" to "build"))

        val byRun = executions.list(runtime.id, null, 50, 0, labels = mapOf("run" to "r1"))
        assertEquals(setOf(a.id, b.id), byRun.items.map { it.id }.toSet())
        assertEquals(2L, byRun.total)

        val both = executions.list(runtime.id, null, 50, 0, labels = mapOf("run" to "r1", "step" to "build"))
        assertEquals(listOf(a.id), both.items.map { it.id })
        assertEquals(mapOf("run" to "r1", "step" to "build"), both.items.single().labels)

        assertEquals(0L, executions.list(runtime.id, null, 50, 0, labels = mapOf("run" to "missing")).total)
    }

    @Test
    fun `workspace list filters by exact name`() {
        val name = "ws-name-${System.nanoTime()}"
        val created = workspaces.create(name, "rook-cephfs", 1024L, emptyMap())
        workspaces.create("$name-other", "rook-cephfs", 1024L, emptyMap())
        val result = workspaces.list(null, 50, 0, name = name)
        assertEquals(listOf(created.id), result.items.map { it.id })
        assertEquals(1L, result.total)
    }

    private fun createWorkspaceLikeExecution() =
        executions.create(createRunningRuntime().id, ExecutionType.SYSTEM, null, null)

    private fun createRunningRuntime(): Runtime {
        val workspace = workspaces.create("ws-${System.nanoTime()}", "rook-cephfs", 1024L, emptyMap())
        check(workspaces.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        val runtime = runtimes.create(workspace.id, "idem-template", null, null, null, null)
        check(runtimes.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        check(runtimes.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING))
        return runtime
    }
}
