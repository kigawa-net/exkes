package net.kigawa.exkes.common

import java.sql.SQLIntegrityConstraintViolationException
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.DuplicateWorkspaceNameException
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.isDuplicateWorkspaceKeyViolation
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.WorkspaceStatus

/**
 * WorkspaceRepository behaviour that the API module cannot observe directly:
 * how a duplicate name is classified, and that a NOT NULL violation is never
 * reported as one (issue: "isDuplicateKey が NOT NULL 違反も 409 に倒す").
 */
class WorkspaceRepositoryTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: WorkspaceRepository

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_repo_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = WorkspaceRepository()
    }

    private fun create(name: String) = repository.create(
        name = name,
        storageClass = "rook-cephfs",
        storageSizeBytes = 1024L * 1024 * 1024,
        labels = emptyMap(),
    )

    @Test
    fun `duplicate name is reported as a duplicate workspace name`() {
        val name = "repo-dup-${System.nanoTime()}"
        create(name)

        assertFailsWith<DuplicateWorkspaceNameException> { create(name) }
    }

    /**
     * The real regression guard: an INSERT that violates a NOT NULL constraint
     * must propagate as-is. Classifying it as a duplicate name would turn a
     * schema bug into a 409 CONFLICT on the create endpoint.
     */
    @Test
    fun `not null violation is not classified as a duplicate name`() {
        val insert = """
            INSERT INTO workspaces (id, name, status, storage_class, storage_size, created_at, updated_at)
            VALUES (?, NULL, 'CREATING', 'rook-cephfs', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """.trimIndent()

        val thrown = dataSource.connection.use { conn ->
            conn.prepareStatement(insert).use { stmt ->
                stmt.setString(1, "00000000-0000-0000-0000-0000000000ff")
                runCatching { stmt.executeUpdate() }.exceptionOrNull()
            }
        }

        assertTrue(thrown is SQLIntegrityConstraintViolationException, "expected an integrity violation, got $thrown")
        assertFalse(
            isDuplicateWorkspaceKeyViolation(thrown),
            "a NOT NULL violation must not be classified as a duplicate workspace name",
        )
    }

    /** Unit-level classification: MariaDB and H2 word the two cases differently. */
    @Test
    fun `duplicate key detection matches only unique constraint violations`() {
        // MariaDB (mariadb-java-client).
        assertTrue(
            isDuplicateWorkspaceKeyViolation(
                SQLIntegrityConstraintViolationException("Duplicate entry 'ws' for key 'uk_workspaces_name'"),
            ),
        )
        // H2 (MODE=MySQL): no constraint name, unique violations only.
        assertTrue(
            isDuplicateWorkspaceKeyViolation(
                SQLIntegrityConstraintViolationException(
                    "Unique index or primary key violation: " +
                        "\"PUBLIC.UK_WORKSPACES_NAME_INDEX_9 ON PUBLIC.WORKSPACES(NAME) VALUES (\"ws\")\"",
                ),
            ),
        )
        // H2 NOT NULL violation: an integrity violation, but not a unique one.
        assertFalse(
            isDuplicateWorkspaceKeyViolation(
                SQLIntegrityConstraintViolationException("NULL not allowed for column \"NAME\""),
            ),
        )
        // Unrelated SQL failure.
        assertFalse(isDuplicateWorkspaceKeyViolation(IllegalStateException("connection refused")))
    }

    /** The cause chain of an Exposed wrapper must still be inspected. */
    @Test
    fun `duplicate key detection walks the cause chain`() {
        val wrapped = RuntimeException(
            "wrapped",
            SQLIntegrityConstraintViolationException("Duplicate entry 'ws' for key 'uk_workspaces_name'"),
        )

        assertTrue(isDuplicateWorkspaceKeyViolation(wrapped))
    }

    @Test
    fun `create records a CREATING row with the given storage settings`() {
        val workspace = create("repo-create-${System.nanoTime()}")

        assertEquals(WorkspaceStatus.CREATING, workspace.status)
        assertEquals("rook-cephfs", workspace.storageClass)
        assertEquals(null, workspace.pvcName)

        val reloaded = repository.findById(workspace.id)
        assertEquals(workspace.name, reloaded?.name)
        assertEquals(workspace.id, reloaded?.id)
    }

    /** Conditional UPDATE: a replayed transition must not match any row. */
    @Test
    fun `transition only updates a row in the expected status`() {
        val workspace = create("repo-transition-${System.nanoTime()}")

        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        assertFalse(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        assertEquals(WorkspaceStatus.READY, repository.findById(workspace.id)!!.status)
    }

    /** Rows the reconciler polls come back oldest first so CREATING age is visible. */
    @Test
    fun `findByStatus returns rows ordered by creation time ascending`() {
        val first = create("repo-order-1-${System.nanoTime()}")
        val second = create("repo-order-2-${System.nanoTime()}")

        val rows = repository.findByStatus(WorkspaceStatus.CREATING)

        assertEquals(listOf(first.id, second.id), rows.map { it.id })
    }
}
