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
import net.kigawa.exkes.common.domain.TerminationEvidence
import java.time.Instant

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

    private fun evidence() = TerminationEvidence(
        observedBy = "runtime-agent:rt-1",
        processIdentity = "container:abc123/pid:42",
        observedAt = Instant.parse("2026-10-12T00:00:00Z"),
    )

    @Test
    fun `cancel request moves non-terminal executions to CANCELLING, never CANCELLED`() {
        for (from in listOf(ExecutionStatus.QUEUED, ExecutionStatus.STARTING, ExecutionStatus.RUNNING)) {
            val execution = createExecution()
            // Walk to `from`.
            if (from != ExecutionStatus.QUEUED) {
                assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
            }
            if (from == ExecutionStatus.RUNNING) {
                assertTrue(repository.transition(execution.id, ExecutionStatus.STARTING, ExecutionStatus.RUNNING))
            }

            assertTrue(repository.transition(execution.id, from, ExecutionStatus.CANCELLING))
            val reloaded = repository.findById(execution.id)!!
            assertEquals(ExecutionStatus.CANCELLING, reloaded.status)
            assertFalse(reloaded.isTerminal)
            assertNotNull(reloaded.cancelRequestedAt)
            assertNull(reloaded.finishedAt)
            assertNull(reloaded.terminationEvidence)
        }
    }

    @Test
    fun `CANCELLED is unreachable without termination evidence`() {
        // The state machine itself forbids a direct jump from any non-terminal state.
        for (from in listOf(ExecutionStatus.QUEUED, ExecutionStatus.STARTING, ExecutionStatus.RUNNING)) {
            assertFalse(ExecutionStatus.canTransition(from, ExecutionStatus.CANCELLED), "from $from")
        }
        val execution = createExecution()
        assertFailsWith<IllegalArgumentException> {
            repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.CANCELLED)
        }
        // Even a CANCELLING row cannot be moved to CANCELLED through the generic transition without evidence.
        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.CANCELLING))
        assertFailsWith<IllegalArgumentException> {
            repository.transition(execution.id, ExecutionStatus.CANCELLING, ExecutionStatus.CANCELLED)
        }
        assertEquals(ExecutionStatus.CANCELLING, repository.findById(execution.id)!!.status)
    }

    @Test
    fun `confirmCancelled moves CANCELLING to CANCELLED and records evidence`() {
        val execution = createExecution()
        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertTrue(repository.transition(execution.id, ExecutionStatus.STARTING, ExecutionStatus.RUNNING))
        assertTrue(repository.transition(execution.id, ExecutionStatus.RUNNING, ExecutionStatus.CANCELLING))

        assertTrue(repository.confirmCancelled(execution.id, evidence()))
        val reloaded = repository.findById(execution.id)!!
        assertEquals(ExecutionStatus.CANCELLED, reloaded.status)
        assertTrue(reloaded.isTerminal)
        assertNotNull(reloaded.finishedAt)
        assertNotNull(reloaded.cancelRequestedAt)
        assertEquals(evidence(), reloaded.terminationEvidence)
    }

    @Test
    fun `confirmCancelled only applies to a CANCELLING row`() {
        val execution = createExecution()
        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertTrue(repository.transition(execution.id, ExecutionStatus.STARTING, ExecutionStatus.RUNNING))

        // Row is RUNNING (no cancel was requested): confirmation must not apply.
        assertFalse(repository.confirmCancelled(execution.id, evidence()))
        val reloaded = repository.findById(execution.id)!!
        assertEquals(ExecutionStatus.RUNNING, reloaded.status)
        assertNull(reloaded.terminationEvidence)
        assertNull(reloaded.finishedAt)
    }

    @Test
    fun `CANCELLING can still be overtaken by the process reporting RUNNING or SUCCEEDED`() {
        // Process survives the cancel request: runtime-agent reports it running, then exiting.
        val survived = createExecution()
        assertTrue(repository.transition(survived.id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertTrue(repository.transition(survived.id, ExecutionStatus.STARTING, ExecutionStatus.CANCELLING))
        assertTrue(repository.transition(survived.id, ExecutionStatus.CANCELLING, ExecutionStatus.RUNNING))
        assertEquals(ExecutionStatus.RUNNING, repository.findById(survived.id)!!.status)
        assertNotNull(repository.findById(survived.id)!!.cancelRequestedAt)

        val exited = createExecution()
        assertTrue(repository.transition(exited.id, ExecutionStatus.QUEUED, ExecutionStatus.CANCELLING))
        assertTrue(repository.transition(exited.id, ExecutionStatus.CANCELLING, ExecutionStatus.SUCCEEDED, exitCode = 0))
        assertEquals(ExecutionStatus.SUCCEEDED, repository.findById(exited.id)!!.status)
    }

    @Test
    fun `terminal executions stay terminal and cannot be cancelled or confirmed again`() {
        val execution = createExecution()
        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.CANCELLING))
        assertTrue(repository.confirmCancelled(execution.id, evidence()))

        assertFailsWith<IllegalArgumentException> {
            repository.transition(execution.id, ExecutionStatus.CANCELLED, ExecutionStatus.CANCELLING)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.transition(execution.id, ExecutionStatus.CANCELLED, ExecutionStatus.RUNNING)
        }
        // A second confirmation matches no row (row is no longer CANCELLING).
        assertFalse(repository.confirmCancelled(execution.id, evidence()))
        assertEquals(ExecutionStatus.CANCELLED, repository.findById(execution.id)!!.status)
    }

    @Test
    fun `CANCELLING appears in status filters as a non-terminal state`() {
        val runtime = createRuntime()
        val execution = createExecution(runtime = runtime)
        assertTrue(repository.transition(execution.id, ExecutionStatus.QUEUED, ExecutionStatus.CANCELLING))

        val cancelling = repository.list(runtimeId = runtime.id, status = ExecutionStatus.CANCELLING, limit = 10, offset = 0)
        assertEquals(1, cancelling.total)
        assertEquals(ExecutionStatus.CANCELLING, cancelling.items.single().status)

        val cancelled = repository.list(runtimeId = runtime.id, status = ExecutionStatus.CANCELLED, limit = 10, offset = 0)
        assertEquals(0, cancelled.total)
    }
}
