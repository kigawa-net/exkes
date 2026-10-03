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
 *                     |           |          |
 *                     |           |          +-> FAILED (final)
 *                     |           +------------> CANCELLED (final)
 *                     +----------------------> TIMED_OUT (final)
 * ```
 */
enum class ExecutionStatus {
    QUEUED,
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT; // semicolon required before companion object in enum class

    companion object {
        private val ALLOWED_TRANSITIONS: Map<ExecutionStatus, Set<ExecutionStatus>> = mapOf(
            QUEUED to setOf(STARTING, CANCELLED),
            STARTING to setOf(RUNNING, FAILED, CANCELLED),
            RUNNING to setOf(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT),
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
) {
    fun canTransitionTo(to: ExecutionStatus): Boolean = ExecutionStatus.canTransition(status, to)

    companion object {
        const val MAX_COMMAND_LENGTH: Int = 32768
    }
}