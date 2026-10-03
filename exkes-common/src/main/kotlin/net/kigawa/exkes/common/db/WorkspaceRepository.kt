package net.kigawa.exkes.common.db

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspaceStatus
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class DuplicateWorkspaceNameException(name: String) :
    RuntimeException("workspace name already exists: $name")

data class WorkspaceListResult(
    val items: List<Workspace>,
    val total: Long,
)

/**
 * CRUD / state transitions for workspaces.
 *
 * All timestamps are stored as UTC (LocalDateTime).
 * State transitions are always conditional (`WHERE id = ? AND status = ?`) so
 * the API and the reconciler can run concurrently (plan chapter 7.1).
 *
 * `open` so tests can wrap it and simulate a lost conditional UPDATE; only
 * [transition] and the reads used by WorkspaceService need overriding.
 */
open class WorkspaceRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(
        name: String,
        storageClass: String,
        storageSizeBytes: Long,
        labels: Map<String, String>,
    ): Workspace = transaction {
        val id = UUID.randomUUID().toString()
        val now = nowUtc()

        try {
            WorkspacesTable.insert {
                it[WorkspacesTable.id] = id
                it[WorkspacesTable.name] = name
                it[WorkspacesTable.status] = WorkspaceStatus.CREATING.name
                it[WorkspacesTable.storageClass] = storageClass
                it[WorkspacesTable.storageSize] = storageSizeBytes
                it[WorkspacesTable.labelsJson] = json.encodeToString(labels)
                it[WorkspacesTable.createdAt] = now
                it[WorkspacesTable.updatedAt] = now
            }
        } catch (e: ExposedSQLException) {
            if (isDuplicateWorkspaceKeyViolation(e)) throw DuplicateWorkspaceNameException(name)
            throw e
        }

        findByIdOrThrow(id)
    }

    fun findById(id: String): Workspace? = transaction {
        WorkspacesTable.selectAll().where { WorkspacesTable.id eq id }
            .singleOrNull()
            ?.toWorkspace()
    }

    /**
     * Lists workspaces, newest first.
     *
     * DELETED rows are tombstones: single GET reports them as 404
     * (plan chapter 6.2), so they are filtered out here as well — otherwise a
     * list and a subsequent GET would disagree. An explicit `status=DELETED`
     * filter therefore always returns an empty page.
     */
    fun list(status: WorkspaceStatus?, limit: Int, offset: Int): WorkspaceListResult = transaction {
        val rows = WorkspacesTable.selectAll().where {
            val filter = status?.let { WorkspacesTable.status eq it.name } ?: Op.TRUE
            filter and (WorkspacesTable.status neq WorkspaceStatus.DELETED.name)
        }
        val total = rows.count()
        val items = rows
            .orderBy(WorkspacesTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toWorkspace() }
        WorkspaceListResult(items, total)
    }

    /** Rows the reconciler has to process, newest first. */
    fun findByStatus(vararg statuses: WorkspaceStatus): List<Workspace> = transaction {
        WorkspacesTable.selectAll()
            .where { WorkspacesTable.status inList statuses.map { it.name } }
            .orderBy(WorkspacesTable.createdAt to SortOrder.ASC)
            .map { it.toWorkspace() }
    }

    /**
     * Conditional state transition. Returns false when the row was not in the
     * expected `from` state (a concurrent actor won the race).
     *
     * `open` for the test double that reproduces that lost race.
     */
    open fun transition(
        id: String,
        from: WorkspaceStatus,
        to: WorkspaceStatus,
        errorMessage: String? = null,
    ): Boolean {
        require(WorkspaceStatus.canTransition(from, to)) {
            "illegal workspace transition: $from -> $to"
        }
        return transaction {
            val now = nowUtc()
            val updated = WorkspacesTable.update(
                { (WorkspacesTable.id eq id) and (WorkspacesTable.status eq from.name) },
            ) { stmt ->
                stmt[WorkspacesTable.status] = to.name
                stmt[WorkspacesTable.updatedAt] = now
                when (to) {
                    WorkspaceStatus.ERROR -> stmt[WorkspacesTable.errorMessage] = errorMessage
                    WorkspaceStatus.ARCHIVED -> stmt[WorkspacesTable.archivedAt] = now
                    else -> Unit
                }
            }
            updated > 0
        }
    }

    /**
     * Records the PVC name once it has been created (null until then).
     *
     * Guarded by `status = 'CREATING'` so a workspace that moved on between the
     * reconciler's read and this write (READY / DELETING / DELETED) does not get
     * its pvc_name resurrected. Returns whether a row was updated.
     */
    fun setPvcName(id: String, pvcName: String): Boolean = transaction {
        // Within CREATING the write is idempotent: the PVC name is deterministic
        // (ws-<workspace id>).
        val updated = WorkspacesTable.update({
            (WorkspacesTable.id eq id) and (WorkspacesTable.status eq WorkspaceStatus.CREATING.name)
        }) { stmt ->
            stmt[WorkspacesTable.pvcName] = pvcName
            stmt[WorkspacesTable.updatedAt] = nowUtc()
        }
        updated > 0
    }

    private fun findByIdOrThrow(id: String): Workspace =
        findById(id) ?: throw IllegalStateException("workspace vanished after insert: $id")

    private fun org.jetbrains.exposed.sql.ResultRow.toWorkspace(): Workspace = Workspace(
        id = this[WorkspacesTable.id],
        name = this[WorkspacesTable.name],
        status = WorkspaceStatus.valueOf(this[WorkspacesTable.status]),
        storageClass = this[WorkspacesTable.storageClass],
        storageSizeBytes = this[WorkspacesTable.storageSize],
        pvcName = this[WorkspacesTable.pvcName],
        errorMessage = this[WorkspacesTable.errorMessage],
        labels = this[WorkspacesTable.labelsJson]?.let { json.decodeFromString(it) } ?: emptyMap(),
        archivedAt = this[WorkspacesTable.archivedAt],
        createdAt = this[WorkspacesTable.createdAt],
        updatedAt = this[WorkspacesTable.updatedAt],
    )

    companion object {
        fun nowUtc(): Instant = Instant.now()
    }
}

/**
 * Whether the failure is a unique-constraint violation on `workspaces.name`.
 *
 * Every other integrity violation must NOT be reported as a duplicate name:
 * a NOT NULL / CHECK / FK violation is a bug or a schema mismatch, and turning
 * it into a 409 CONFLICT ("workspace name already exists") would hide it
 * behind a successful-looking response. So the check looks for the constraint
 * name (`uk_workspaces_name`) rather than for the exception type.
 *
 * H2 (MODE=MySQL) does not put the constraint name in the message; it phrases
 * unique/PK violations as "Unique index or primary key violation", which NOT
 * NULL violations never use, so that phrasing is an equally safe signal.
 *
 * `internal` so the classification can be unit tested without a live DB.
 */
internal fun isDuplicateWorkspaceKeyViolation(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }
        .mapNotNull { it.message }
        .any { message ->
            message.contains("uk_workspaces_name", ignoreCase = true) ||
                message.contains("Duplicate entry", ignoreCase = true) ||
                message.contains("Unique index or primary key violation", ignoreCase = true)
        }
