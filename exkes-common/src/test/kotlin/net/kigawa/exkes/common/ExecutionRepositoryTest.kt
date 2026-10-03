package net.kigawa.exkes.common

import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.ExecutionStatus
import net.kigawa.exkes.common.domain.ExecutionType
import net.kigawa.exkes.common.domain.RuntimeStatus

class ExecutionRepositoryTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: ExecutionRepository
    private lateinit var runtimeRepository: RuntimeRepository
    private lateinit var workspaceRepository: WorkspaceRepository
    private lateinit var templateRepository: RuntimeTemplateRepository

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_exec_repo_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = ExecutionRepository()
        runtimeRepository = RuntimeRepository()
        workspaceRepository = WorkspaceRepository()
        templateRepository = RuntimeTemplateRepository()
        // Create the default template that runtimes reference
        templateRepository.create(
            id = "runtime-agent",
            name = "Runtime Agent",
            description = "Default runtime with agent sidecar",
            image = "ghcr.io/kigawa/runtime-agent:latest",
            defaultResourcesJson = """{"cpu":"500m","memory":"512Mi"}""",
            defaultEnvJson = """{}""",
            defaultTtlSeconds = 3600L,
            labelsJson = """{"app":"exkes"}""",
        )
    }

    private fun createWorkspace(): net.kigawa.exkes.common.domain.Workspace =
        workspaceRepository.create(
            name = "ws-${System.nanoTime()}",
            storageClass = "rook-cephfs",
            storageSizeBytes = 1024L * 1024 * 1024,
            labels = emptyMap(),
        )

    private fun createRuntime(): net.kigawa.exkes.common.domain.Runtime {
        val workspace = createWorkspace()
        return runtimeRepository.create(
            workspaceId = workspace.id,
            templateId = "runtime-agent",
            resourcesJson = null,
            envJson = null,
            ttlSeconds = null,
            labelsJson = null,
        )
    }

    private fun createExecution(
        runtime: net.kigawa.exkes.common.domain.Runtime? = null,
        type: ExecutionType = ExecutionType.COMMAND,
    ) = repository.create(
        runtimeId = runtime?.id ?: createRuntime().id,
        type = type,
        commandJson = """{"command":["echo","hello"]}""",
        agentConfigJson = null,
    )

    @Test
    fun `create records a QUEUED execution`() {
        val execution = createExecution()

        assertEquals(ExecutionStatus.QUEUED, execution.status)
        assertEquals(ExecutionType.COMMAND, execution.type)
        assertEquals("""{"command":["echo","hello"]}""", execution.commandJson)
        assertNull(execution.startedAt)
        assertNull(execution.finishedAt)

        val reloaded = repository.findById(execution.id)
        assertNotNull(reloaded)
        assertEquals(execution.id, reloaded.id)
    }

    /** Conditional UPDATE: a replayed transition must not match any row. */
    @Test
    fun `transition only updates a row in the expected status`() {
        val execution = createExecution()

        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertFalse(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertEquals(ExecutionStatus.STARTING, repository.findById(execution.id)!!.status)
    }

    /** State machine guard: illegal transitions are rejected before SQL runs. */
    @Test
    fun `state machine rejects illegal transitions`() {
        val execution = createExecution()

        assertFailsWith<IllegalArgumentException> {
            repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.SUCCEEDED)
        }
        assertEquals(ExecutionStatus.QUEUED, repository.findById(execution.id)!!.status)

        // Valid path: QUEUED -> STARTING -> RUNNING -> SUCCEEDED
        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertNotNull(repository.findById(execution.id)!!.startedAt)

        assertTrue(repository.transition(execution.id, ExecutionStatus.STARTING, ExecutionStatus.RUNNING))
        assertTrue(repository.transition(execution.id, ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED, exitCode = 0))
        assertNotNull(repository.findById(execution.id)!!.finishedAt)

        // Cannot transition from terminal state
        assertFailsWith<IllegalArgumentException> {
            repository.transition(execution.id, ExecutionStatus.SUCCEEDED, ExecutionStatus.RUNNING)
        }
    }

    @Test
    fun `list filters by runtime and status`() {
        val runtime1 = createRuntime()
        val runtime2 = createRuntime()

        val exec1 = createExecution(runtime = runtime1, type = ExecutionType.COMMAND)
        val exec2 = createExecution(runtime = runtime1, type = ExecutionType.AGENT)
        val exec3 = createExecution(runtime = runtime2, type = ExecutionType.COMMAND)

        assertTrue(repository.transition(exec1.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertTrue(repository.transition(exec2.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertTrue(repository.transition(exec3.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))

        val allForRt1 = repository.list(runtimeId = runtime1.id, status = null, limit = 100, offset = 0)
        assertEquals(2, allForRt1.total)
        assertTrue(allForRt1.items.map { it.id }.containsAll(listOf(exec1.id, exec2.id)))

        val startingForRt1 = repository.list(runtimeId = runtime1.id, status = ExecutionStatus.STARTING, limit = 100, offset = 0)
        assertEquals(2, startingForRt1.total)

        val queuedForRt1 = repository.list(runtimeId = runtime1.id, status = ExecutionStatus.QUEUED, limit = 100, offset = 0)
        assertEquals(0, queuedForRt1.total)
    }

    @Test
    fun `transition records exit code and timestamps`() {
        val execution = createExecution()

        // QUEUED -> STARTING
        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        var reloaded = repository.findById(execution.id)!!
        assertEquals(ExecutionStatus.STARTING, reloaded.status)
        assertNotNull(reloaded.startedAt)
        assertNull(reloaded.finishedAt)

        // STARTING -> RUNNING
        assertTrue(repository.transition(execution.id, ExecutionStatus.STARTING, ExecutionStatus.RUNNING))
        reloaded = repository.findById(execution.id)!!
        assertEquals(ExecutionStatus.RUNNING, reloaded.status)

        // RUNNING -> SUCCEEDED with exit code
        assertTrue(repository.transition(execution.id, ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED, exitCode = 0))
        reloaded = repository.findById(execution.id)!!
        assertEquals(ExecutionStatus.SUCCEEDED, reloaded.status)
        assertEquals(0, reloaded.exitCode)
        assertNotNull(reloaded.finishedAt)
    }
}