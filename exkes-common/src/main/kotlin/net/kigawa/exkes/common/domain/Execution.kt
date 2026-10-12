package net.kigawa.exkes.common.domain

import java.time.Instant

/**
 * Execution type (issue #1 "Execution" section).
 */
enum class ExecutionType {
    COMMAND,   // Arbitrary shell command
    AGENT,     // AI Agent (e.g., Claude Code)
    SYSTEM,    // Internal system execution (e.g., workspace init)
}

/**
 * Execution lifecycle states (issue #1 "Execution" section).
 *
 * ```text
 * (none) -> QUEUED -> STARTING -> RUNNING -> SUCCEEDED (final)
 *                     |           |          +-> FAILED (final)
 *                     |           |          +-> TIMED_OUT (final)
 *                     |           |
 *   QUEUED/STARTING/RUNNING --> CANCELLING (cancel requested, process stop NOT yet confirmed)
 *                                 |
 *                                 +-> CANCELLED (final, ONLY via confirmCancelled with termination evidence)
 *                                 +-> RUNNING / SUCCEEDED / FAILED / TIMED_OUT (process reported otherwise)
 * ```
 *
 * [CANCELLING] is non-terminal. A cancel request alone never yields [CANCELLED]: that state
 * means the runtime-agent observed the process/container actually exit, and it is reached
 * only through `ExecutionRepository.confirmCancelled`, which the cancel API never calls.
 */
enum class ExecutionStatus {
    QUEUED,
    STARTING,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT; // semicolon required before companion object in enum class

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT

    companion object {
        private val ALLOWED_TRANSITIONS: Map<ExecutionStatus, Set<ExecutionStatus>> = mapOf(
            QUEUED to setOf(STARTING, CANCELLING),
            STARTING to setOf(RUNNING, FAILED, CANCELLING),
            RUNNING to setOf(SUCCEEDED, FAILED, CANCELLING, TIMED_OUT),
            CANCELLING to setOf(CANCELLED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT),
            SUCCEEDED to emptySet(),
            FAILED to emptySet(),
            CANCELLED to emptySet(),
            TIMED_OUT to emptySet(),
        )

        fun canTransition(from: ExecutionStatus, to: ExecutionStatus): Boolean =
            ALLOWED_TRANSITIONS[from]?.contains(to) == true
    }
}

/**
 * Evidence that the runtime-agent observed the target process/container exit.
 * Required to move an Execution to [ExecutionStatus.CANCELLED]. Persisted with the Execution.
 * Writer generation / fencing epoch is not modelled yet.
 */
data class TerminationEvidence(
    val observedBy: String,
    val processIdentity: String,
    val observedAt: Instant,
) {
    init {
        require(observedBy.isNotBlank()) { "observedBy must not be blank" }
        require(processIdentity.isNotBlank()) { "processIdentity must not be blank" }
    }
}

/**
 * Execution: a single unit of work running inside a Runtime.
 * The MariaDB row is the source of truth.
 */
data class Execution(
    val id: String,
    val runtimeId: String,
    val type: ExecutionType,
    val status: ExecutionStatus,
    val commandJson: String?,       // JSON: command + args (for COMMAND type)
    val agentConfigJson: String?,   // JSON: agent-specific config (for AGENT type)
    val exitCode: Int?,
    val errorMessage: String?,
    val logObjectKey: String?,      // Ceph RGW object key for stdout/stderr
    val artifactObjectKeysJson: String?, // JSON: list of artifact object keys
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val labels: Map<String, String> = emptyMap(),
    val cancelRequestedAt: Instant? = null,
    val terminationEvidence: TerminationEvidence? = null,
) {
    fun canTransitionTo(to: ExecutionStatus): Boolean = ExecutionStatus.canTransition(status, to)

    val isTerminal: Boolean get() = status.isTerminal

    companion object {
        const val MAX_COMMAND_LENGTH: Int = 32768
    }
}