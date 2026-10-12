package net.kigawa.exkes.common.db

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import net.kigawa.exkes.common.domain.Execution
import net.kigawa.exkes.common.domain.ExecutionStatus
import net.kigawa.exkes.common.domain.ExecutionType
import net.kigawa.exkes.common.domain.TerminationEvidence
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
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
        labels: Map<String, String> = emptyMap(),
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
            it[ExecutionsTable.labelsJson] = if (labels.isEmpty()) null else json.encodeToString(labels)
            it[ExecutionsTable.createdAt] = now
            it[ExecutionsTable.updatedAt] = now
        }
        labels.forEach { (key, value) ->
            ExecutionLabelsTable.insert {
                it[ExecutionLabelsTable.executionId] = id
                it[ExecutionLabelsTable.labelKey] = key
                it[ExecutionLabelsTable.labelValue] = value
            }
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
        labels: Map<String, String> = emptyMap(),
    ): ExecutionListResult = transaction {
        // Each label is a separate EXISTS-style subquery, so multiple labels are ANDed.
        val labelFilter = labels.entries.fold<Map.Entry<String, String>, Op<Boolean>>(Op.TRUE) { acc, (key, value) ->
            val matching = ExecutionLabelsTable
                .select(ExecutionLabelsTable.executionId)
                .where { (ExecutionLabelsTable.labelKey eq key) and (ExecutionLabelsTable.labelValue eq value) }
            acc and ExecutionsTable.id.inSubQuery(matching)
        }
        val rows = ExecutionsTable.selectAll().where {
            val rtFilter = runtimeId?.let { ExecutionsTable.runtimeId eq it } ?: Op.TRUE
            val statusFilter = status?.let { ExecutionsTable.status eq it.name } ?: Op.TRUE
            rtFilter and statusFilter and labelFilter
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
     *
     * Entering [ExecutionStatus.CANCELLING] records `cancel_requested_at`. Entering
     * [ExecutionStatus.CANCELLED] requires [terminationEvidence] (see [confirmCancelled]).
     */
    fun transition(
        id: String,
        from: ExecutionStatus,
        to: ExecutionStatus,
        exitCode: Int? = null,
        errorMessage: String? = null,
        logObjectKey: String? = null,
        artifactObjectKeysJson: String? = null,
        terminationEvidence: TerminationEvidence? = null,
    ): Boolean {
        require(to != ExecutionStatus.CANCELLED || terminationEvidence != null) {
            "CANCELLED requires termination evidence; use confirmCancelled"
        }
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
                terminationEvidence?.let {
                    stmt[ExecutionsTable.terminationObservedBy] = it.observedBy
                    stmt[ExecutionsTable.terminationProcessIdentity] = it.processIdentity
                    stmt[ExecutionsTable.terminationObservedAt] = it.observedAt
                }
                when (to) {
                    ExecutionStatus.QUEUED -> Unit
                    ExecutionStatus.CANCELLING -> stmt[ExecutionsTable.cancelRequestedAt] = now
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

    /**
     * Moves a CANCELLING execution to CANCELLED, recording the evidence that the process
     * exited. Intended ONLY for the stop-confirming side (runtime-agent / controller).
     * The cancel API must never call this: a cancel request is not a termination proof.
     * Returns false when the row is not CANCELLING.
     */
    fun confirmCancelled(id: String, evidence: TerminationEvidence): Boolean =
        transition(
            id = id,
            from = ExecutionStatus.CANCELLING,
            to = ExecutionStatus.CANCELLED,
            terminationEvidence = evidence,
        )

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
        labels = this[ExecutionsTable.labelsJson]?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap(),
        cancelRequestedAt = this[ExecutionsTable.cancelRequestedAt],
        terminationEvidence = terminationEvidenceOrNull(),
    )

    /** Evidence columns are written together by [transition]; all three present or none. */
    private fun org.jetbrains.exposed.sql.ResultRow.terminationEvidenceOrNull(): TerminationEvidence? {
        val by = this[ExecutionsTable.terminationObservedBy]
        val identity = this[ExecutionsTable.terminationProcessIdentity]
        val observedAt = this[ExecutionsTable.terminationObservedAt]
        return if (by != null && identity != null && observedAt != null) {
            TerminationEvidence(by, identity, observedAt)
        } else {
            null
        }
    }

    companion object {
        fun nowUtc(): Instant = Instant.now()
    }
}