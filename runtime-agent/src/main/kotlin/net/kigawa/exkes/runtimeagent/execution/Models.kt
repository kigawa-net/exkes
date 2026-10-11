package net.kigawa.exkes.runtimeagent.execution

import kotlinx.serialization.Serializable

/**
 * Execution lifecycle as seen by the agent. Mirrors the control plane's state
 * machine (issue #8) but is declared here because runtime-agent must not depend
 * on exkes-common.
 */
@Serializable
enum class ExecutionStatus {
    QUEUED, STARTING, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT;

    val isTerminal: Boolean get() = this in TERMINAL

    companion object {
        private val TERMINAL = setOf(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT)

        private val ALLOWED: Map<ExecutionStatus, Set<ExecutionStatus>> = mapOf(
            QUEUED to setOf(STARTING, CANCELLED, FAILED),
            STARTING to setOf(RUNNING, FAILED, CANCELLED),
            RUNNING to setOf(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT),
        )

        fun canTransition(from: ExecutionStatus, to: ExecutionStatus): Boolean =
            ALLOWED[from]?.contains(to) == true
    }
}

@Serializable
enum class LogStream { STDOUT, STDERR }

/** One ordered piece of output. [seq] starts at 1 and is contiguous per execution. */
@Serializable
data class LogChunk(
    val seq: Long,
    val stream: LogStream,
    val data: String,
    val timestamp: String,
)

/** Request to start a COMMAND execution. [command] is exec'd directly (no shell). */
@Serializable
data class StartExecutionRequest(
    val id: String? = null,
    val command: List<String>,
    val workingDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val timeoutSeconds: Long? = null,
)

/** Externally visible, immutable view of an execution. */
@Serializable
data class ExecutionSnapshot(
    val id: String,
    val command: List<String>,
    val workingDir: String?,
    val status: ExecutionStatus,
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val timeoutSeconds: Long? = null,
    val createdAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val lastLogSeq: Long = 0,
)

class ExecutionNotFoundException(id: String) : RuntimeException("execution not found: $id")
class ExecutionConflictException(message: String) : RuntimeException(message)
class InvalidExecutionRequestException(message: String) : RuntimeException(message)
