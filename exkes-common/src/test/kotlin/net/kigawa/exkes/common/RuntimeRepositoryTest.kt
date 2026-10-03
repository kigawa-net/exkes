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
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.WorkspaceStatus

/**
 * RuntimeRepository behaviour that the API module cannot observe directly:
 * how a conditional UPDATE behaves, and that state machine transitions are enforced.
 */
class RuntimeRepositoryTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: RuntimeRepository
    private lateinit var workspaceRepository: WorkspaceRepository
    private lateinit var templateRepository: RuntimeTemplateRepository

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_runtime_repo_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = RuntimeRepository()
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
        return repository.create(
            workspaceId = workspace.id,
            templateId = "runtime-agent",
            resourcesJson = null,
            envJson = null,
            ttlSeconds = null,
            labelsJson = null,
        )
    }

    @Test
    fun `create records a CREATING row with the given settings`() {
        val runtime = createRuntime()

        assertEquals(RuntimeStatus.CREATING, runtime.status)
        assertEquals("runtime-agent", runtime.templateId)
        assertNull(runtime.podName)

        val reloaded = repository.findById(runtime.id)
        assertNotNull(reloaded)
        assertEquals(runtime.id, reloaded.id)
        assertEquals(runtime.workspaceId, reloaded.workspaceId)
    }

    /** Conditional UPDATE: a replayed transition must not match any row. */
    @Test
    fun `transition only updates a row in the expected status`() {
        val runtime = createRuntime()

        assertTrue(repository.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        assertFalse(repository.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        assertEquals(RuntimeStatus.STARTING, repository.findById(runtime.id)!!.status)
    }

    /** State machine guard: illegal transitions are rejected before SQL runs. */
    @Test
    fun `state machine rejects illegal transitions`() {
        val runtime = createRuntime()

        assertFailsWith<IllegalArgumentException> {
            repository.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.DELETED)
        }
        assertEquals(RuntimeStatus.CREATING, repository.findById(runtime.id)!!.status)

        // Valid path: CREATING -> STARTING -> RUNNING
        assertTrue(repository.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        assertTrue(repository.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING))

        // Cannot go back from RUNNING to CREATING
        assertFailsWith<IllegalArgumentException> {
            repository.transition(runtime.id, RuntimeStatus.RUNNING, RuntimeStatus.CREATING)
        }
        assertEquals(RuntimeStatus.RUNNING, repository.findById(runtime.id)!!.status)
    }

    /** Rows the reconciler polls come back oldest first so TTL age is visible. */
    @Test
    fun `findByStatus returns rows ordered by creation time ascending`() {
        val first = createRuntime()
        val second = createRuntime()

        val rows = repository.findByStatus(RuntimeStatus.CREATING)

        assertEquals(listOf(first.id, second.id), rows.map { it.id })
    }

    /** DELETED rows are filtered out of list by default. */
    @Test
    fun `list hides deleted runtimes`() {
        val keep = createRuntime()
        val drop = createRuntime()
        assertTrue(repository.transition(drop.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        assertTrue(repository.transition(drop.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING))
        assertTrue(repository.transition(drop.id, RuntimeStatus.RUNNING, RuntimeStatus.DELETING))
        assertTrue(repository.transition(drop.id, RuntimeStatus.DELETING, RuntimeStatus.DELETED))

        val all = repository.list(workspaceId = null, status = null, limit = 100, offset = 0)
        assertEquals(listOf(keep.id), all.items.map { it.id })
        assertEquals(1, all.total)

        // An explicit DELETED filter cannot resurrect the tombstone either.
        val deleted = repository.list(workspaceId = null, status = RuntimeStatus.DELETED, limit = 100, offset = 0)
        assertEquals(emptyList(), deleted.items)
        assertEquals(0, deleted.total)
    }

    /** Pod name is only written while in CREATING or STARTING. */
    @Test
    fun `pod name is not written for a runtime that already left creating or starting`() {
        val runtime = createRuntime()
        assertTrue(repository.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        assertTrue(repository.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING))

        // setPodName is guarded by status IN ('CREATING', 'STARTING').
        assertEquals(false, repository.setPodName(runtime.id, "rt-${runtime.id}"))
        assertNull(repository.findById(runtime.id)!!.podName)
    }
}