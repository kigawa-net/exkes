package net.kigawa.exkes.common.db

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class RuntimeListResult(
    val items: List<Runtime>,
    val total: Long,
)

/**
 * CRUD / state transitions for runtimes.
 *
 * All timestamps are stored as UTC (LocalDateTime).
 * State transitions are always conditional (`WHERE id = ? AND status = ?`) so
 * the API and the reconciler can run concurrently (plan chapter 7.1).
 *
 * `open` so tests can wrap it and simulate a lost conditional UPDATE; only
 * [transition] and the reads used by RuntimeService need overriding.
 */
open class RuntimeRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(
        workspaceId: String,
        templateId: String,
        resourcesJson: String?,
        envJson: String?,
        ttlSeconds: Long?,
        labelsJson: String?,
    ): Runtime = transaction {
        val id = UUID.randomUUID().toString()
        val now = nowUtc()

        RuntimesTable.insert {
            it[RuntimesTable.id] = id
            it[RuntimesTable.workspaceId] = workspaceId
            it[RuntimesTable.templateId] = templateId
            it[RuntimesTable.status] = RuntimeStatus.CREATING.name
            it[RuntimesTable.resourcesJson] = resourcesJson
            it[RuntimesTable.envJson] = envJson
            it[RuntimesTable.ttlSeconds] = ttlSeconds
            it[RuntimesTable.labelsJson] = labelsJson
            it[RuntimesTable.createdAt] = now
            it[RuntimesTable.updatedAt] = now
        }

        findByIdOrThrow(id)
    }

    fun findById(id: String): Runtime? = transaction {
        RuntimesTable.selectAll().where { RuntimesTable.id eq id }
            .singleOrNull()
            ?.toRuntime()
    }

    fun list(
        workspaceId: String?,
        status: RuntimeStatus?,
        limit: Int,
        offset: Int,
    ): RuntimeListResult = transaction {
        val rows = RuntimesTable.selectAll().where {
            val wsFilter = workspaceId?.let { RuntimesTable.workspaceId eq it } ?: Op.TRUE
            val statusFilter = status?.let { RuntimesTable.status eq it.name } ?: Op.TRUE
            wsFilter and statusFilter and (RuntimesTable.status neq RuntimeStatus.DELETED.name)
        }
        val total = rows.count()
        val items = rows
            .orderBy(RuntimesTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toRuntime() }
        RuntimeListResult(items, total)
    }

    /** Rows the reconciler has to process, oldest first (so TTL age is visible). */
    fun findByStatus(vararg statuses: RuntimeStatus): List<Runtime> = transaction {
        RuntimesTable.selectAll()
            .where { RuntimesTable.status inList statuses.map { it.name } }
            .orderBy(RuntimesTable.createdAt to SortOrder.ASC)
            .map { it.toRuntime() }
    }

    /**
     * Conditional state transition. Returns false when the row was not in the
     * expected `from` state (a concurrent actor won the race).
     *
     * `open` for the test double that reproduces that lost race.
     */
    open fun transition(
        id: String,
        from: RuntimeStatus,
        to: RuntimeStatus,
        errorMessage: String? = null,
    ): Boolean {
        require(RuntimeStatus.canTransition(from, to)) {
            "illegal runtime transition: $from -> $to"
        }
        return transaction {
            val now = nowUtc()
            val updated = RuntimesTable.update(
                { (RuntimesTable.id eq id) and (RuntimesTable.status eq from.name) },
            ) { stmt ->
                stmt[RuntimesTable.status] = to.name
                stmt[RuntimesTable.updatedAt] = now
                if (to == RuntimeStatus.FAILED) {
                    stmt[RuntimesTable.errorMessage] = errorMessage
                }
            }
            updated > 0
        }
    }

    /**
     * Records the Pod name once it has been created (null until then).
     *
     * Guarded by `status IN ('CREATING', 'STARTING')` so a runtime that moved on
     * between the reconciler's read and this write does not get its pod_name
     * resurrected. Returns whether a row was updated.
     */
    fun setPodName(id: String, podName: String): Boolean = transaction {
        val updated = RuntimesTable.update({
            (RuntimesTable.id eq id) and (RuntimesTable.status inList listOf(RuntimeStatus.CREATING.name, RuntimeStatus.STARTING.name))
        }) { stmt ->
            stmt[RuntimesTable.podName] = podName
            stmt[RuntimesTable.updatedAt] = nowUtc()
        }
        updated > 0
    }

    private fun findByIdOrThrow(id: String): Runtime =
        findById(id) ?: throw IllegalStateException("runtime vanished after insert: $id")

    private fun org.jetbrains.exposed.sql.ResultRow.toRuntime(): Runtime = Runtime(
        id = this[RuntimesTable.id],
        workspaceId = this[RuntimesTable.workspaceId],
        templateId = this[RuntimesTable.templateId],
        status = RuntimeStatus.valueOf(this[RuntimesTable.status]),
        resourcesJson = this[RuntimesTable.resourcesJson],
        envJson = this[RuntimesTable.envJson],
        ttlSeconds = this[RuntimesTable.ttlSeconds],
        podName = this[RuntimesTable.podName],
        errorMessage = this[RuntimesTable.errorMessage],
        labelsJson = this[RuntimesTable.labelsJson],
        createdAt = this[RuntimesTable.createdAt],
        updatedAt = this[RuntimesTable.updatedAt],
    )

    companion object {
        fun nowUtc(): Instant = Instant.now()
    }
}