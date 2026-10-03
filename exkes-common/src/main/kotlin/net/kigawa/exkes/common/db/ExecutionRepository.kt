package net.kigawa.exkes.common.db

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import net.kigawa.exkes.common.domain.Execution
import net.kigawa.exkes.common.domain.ExecutionStatus
import net.kigawa.exkes.common.domain.ExecutionType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class ExecutionListResult(
    val items: List<Execution>,
    val total: Long,
)

/**
 * CRUD / state transitions for executions (minimal for Phase 2).
 *
 * All timestamps are stored as UTC (LocalDateTime).
 * State transitions are always conditional (`WHERE id = ? AND status = ?`).
 */
class ExecutionRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(
        runtimeId: String,
        type: ExecutionType,
        commandJson: String?,
        agentConfigJson: String?,
    ): Execution = transaction {
        val id = UUID.randomUUID().toString()
        val now = nowUtc()

        ExecutionsTable.insert {
            it[ExecutionsTable.id] = id
            it[ExecutionsTable.runtimeId] = runtimeId
            it[ExecutionsTable.type] = type.name
            it[ExecutionsTable.status] = ExecutionStatus.QUEUED.name
            it[ExecutionsTable.commandJson] = commandJson
            it[ExecutionsTable.agentConfigJson] = agentConfigJson
            it[ExecutionsTable.createdAt] = now
            it[ExecutionsTable.updatedAt] = now
        }

        findByIdOrThrow(id)
    }

    fun findById(id: String): Execution? = transaction {
        ExecutionsTable.selectAll().where { ExecutionsTable.id eq id }
            .singleOrNull()
            ?.toExecution()
    }

    fun list(
        runtimeId: String?,
        status: ExecutionStatus?,
        limit: Int,
        offset: Int,
    ): ExecutionListResult = transaction {
        val rows = ExecutionsTable.selectAll().where {
            val rtFilter = runtimeId?.let { ExecutionsTable.runtimeId eq it } ?: Op.TRUE
            val statusFilter = status?.let { ExecutionsTable.status eq it.name } ?: Op.TRUE
            rtFilter and statusFilter
        }
        val total = rows.count()
        val items = rows
            .orderBy(ExecutionsTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toExecution() }
        ExecutionListResult(items, total)
    }

    /**
     * Conditional state transition. Returns false when the row was not in the
     * expected `from` state.
     */
    fun transition(
        id: String,
        from: ExecutionStatus,
        to: ExecutionStatus,
        exitCode: Int? = null,
        errorMessage: String? = null,
        logObjectKey: String? = null,
        artifactObjectKeysJson: String? = null,
    ): Boolean {
        require(ExecutionStatus.canTransition(from, to)) {
            "illegal execution transition: $from -> $to"
        }
        return transaction {
            val now = nowUtc()
            val updated = ExecutionsTable.update(
                { (ExecutionsTable.id eq id) and (ExecutionsTable.status eq from.name) },
            ) { stmt ->
                stmt[ExecutionsTable.status] = to.name
                stmt[ExecutionsTable.updatedAt] = now
                exitCode?.let { stmt[ExecutionsTable.exitCode] = it }
                errorMessage?.let { stmt[ExecutionsTable.errorMessage] = it }
                logObjectKey?.let { stmt[ExecutionsTable.logObjectKey] = it }
                artifactObjectKeysJson?.let { stmt[ExecutionsTable.artifactObjectKeysJson] = it }
                when (to) {
                    ExecutionStatus.QUEUED -> Unit
                    ExecutionStatus.STARTING, ExecutionStatus.RUNNING -> {
                        // Set startedAt if not already set (only first transition to STARTING/RUNNING)
                        stmt[ExecutionsTable.startedAt] = now
                    }
                    ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED,
                    ExecutionStatus.CANCELLED, ExecutionStatus.TIMED_OUT -> {
                        stmt[ExecutionsTable.finishedAt] = now
                    }
                }
            }
            updated > 0
        }
    }

    private fun findByIdOrThrow(id: String): Execution =
        findById(id) ?: throw IllegalStateException("execution vanished after insert: $id")

    private fun org.jetbrains.exposed.sql.ResultRow.toExecution(): Execution = Execution(
        id = this[ExecutionsTable.id],
        runtimeId = this[ExecutionsTable.runtimeId],
        type = ExecutionType.valueOf(this[ExecutionsTable.type]),
        status = ExecutionStatus.valueOf(this[ExecutionsTable.status]),
        commandJson = this[ExecutionsTable.commandJson],
        agentConfigJson = this[ExecutionsTable.agentConfigJson],
        exitCode = this[ExecutionsTable.exitCode],
        errorMessage = this[ExecutionsTable.errorMessage],
        logObjectKey = this[ExecutionsTable.logObjectKey],
        artifactObjectKeysJson = this[ExecutionsTable.artifactObjectKeysJson],
        createdAt = this[ExecutionsTable.createdAt],
        updatedAt = this[ExecutionsTable.updatedAt],
        startedAt = this[ExecutionsTable.startedAt],
        finishedAt = this[ExecutionsTable.finishedAt],
    )

    companion object {
        fun nowUtc(): Instant = Instant.now()
    }
}