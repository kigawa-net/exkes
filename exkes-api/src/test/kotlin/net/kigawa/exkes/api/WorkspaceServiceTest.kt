package net.kigawa.exkes.api

import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.exkes.api.workspace.WorkspaceConflictException
import net.kigawa.exkes.api.workspace.WorkspaceNotFoundException
import net.kigawa.exkes.api.workspace.WorkspaceService
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.config.WorkspaceConfig
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.WorkspaceStatus

/**
 * DELETE semantics of WorkspaceService, in particular the "re-read and
 * re-decide" behaviour required when the conditional UPDATE loses a race
 * (plan chapter 7.1).
 */
class WorkspaceServiceTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: WorkspaceRepository

    /**
     * Real repository that drops the first [droppedUpdates] conditional
     * UPDATEs, which is exactly what WorkspaceService observes when another
     * writer (the reconciler, or a second DELETE) wins the race.
     */
    private class RacingRepository(
        private val delegate: WorkspaceRepository,
        var droppedUpdates: Int,
    ) : WorkspaceRepository() {
        var transitionCalls: Int = 0

        override fun transition(
            id: String,
            from: WorkspaceStatus,
            to: WorkspaceStatus,
            errorMessage: String?,
        ): Boolean {
            transitionCalls++
            if (droppedUpdates > 0) {
                droppedUpdates--
                return false
            }
            return delegate.transition(id, from, to, errorMessage)
        }
    }

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_service_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = WorkspaceRepository()
    }

    private fun serviceWith(repository: WorkspaceRepository) = WorkspaceService(
        repository = repository,
        workspaceConfig = WorkspaceConfig(
            storageClass = "rook-cephfs",
            defaultStorageSizeBytes = 1024L * 1024 * 1024,
        ),
    )

    private fun createWorkspace(): String = repository.create(
        name = "service-${System.nanoTime()}",
        storageClass = "rook-cephfs",
        storageSizeBytes = 1024L * 1024 * 1024,
        labels = emptyMap(),
    ).id

    @Test
    fun `requestDeletion re-reads and retries when the conditional update loses the race`() {
        val id = createWorkspace()
        assertTrue(repository.transition(id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        val racing = RacingRepository(repository, droppedUpdates = 1)

        val result = runBlocking { serviceWith(racing).requestDeletion(id) }

        assertEquals(WorkspaceStatus.DELETING, result.status)
        assertEquals(2, racing.transitionCalls, "the lost update must be retried once")
        assertEquals(WorkspaceStatus.DELETING, repository.findById(id)!!.status)
    }

    @Test
    fun `requestDeletion reports conflict when the race is lost repeatedly`() {
        val id = createWorkspace()
        val racing = RacingRepository(repository, droppedUpdates = 100)

        assertFailsWith<WorkspaceConflictException> {
            runBlocking { serviceWith(racing).requestDeletion(id) }
        }

        assertEquals(WorkspaceService.MAX_DELETE_ATTEMPTS, racing.transitionCalls)
        assertEquals(WorkspaceStatus.CREATING, repository.findById(id)!!.status)
    }

    @Test
    fun `requestDeletion is idempotent while the workspace is already deleting`() {
        val id = createWorkspace()
        assertTrue(repository.transition(id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))
        val racing = RacingRepository(repository, droppedUpdates = 100)

        val result = runBlocking { serviceWith(racing).requestDeletion(id) }

        assertEquals(WorkspaceStatus.DELETING, result.status)
        assertEquals(0, racing.transitionCalls, "an already DELETING row needs no UPDATE")
    }

    @Test
    fun `requestDeletion of a deleted workspace reports not found`() {
        val id = createWorkspace()
        assertTrue(repository.transition(id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))
        assertTrue(repository.transition(id, WorkspaceStatus.DELETING, WorkspaceStatus.DELETED))

        assertFailsWith<WorkspaceNotFoundException> {
            runBlocking { serviceWith(repository).requestDeletion(id) }
        }
    }
}