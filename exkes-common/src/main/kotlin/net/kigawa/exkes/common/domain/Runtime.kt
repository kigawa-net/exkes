package net.kigawa.exkes.common.domain

import java.time.Instant

/**
 * Runtime lifecycle states (plan chapter 7.1 / issue #1 "Runtime" section).
 *
 * ```text
 * (none) -> CREATING -> STARTING -> RUNNING <-> STOPPING -> STOPPED -> DELETING -> DELETED (final)
 *    |         |          |           |         |           |          |
 *    |         |          |           |         |           +----------+--> ERROR
 *    |         |          |           |         +-----------> FAILED <-----+
 *    |         |          |           +---------> EXPIRED (TTL) -----------+
 *    |         |          +-------------------------> FAILED ---------------+
 *    |         +---------------------------------> FAILED ------------------+
 *    +-----------------------------------------> FAILED --------------------+
 * ```
 *
 * All transitions are enforced by [RuntimeRepository#transition] via a
 * conditional UPDATE (`WHERE id = ? AND status = ?`).
 */
enum class RuntimeStatus {
    CREATING,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
    DELETING,
    DELETED,
    EXPIRED; // semicolon required before companion object in enum class

    companion object {
        private val ALLOWED_TRANSITIONS: Map<RuntimeStatus, Set<RuntimeStatus>> = mapOf(
            CREATING to setOf(STARTING, FAILED, DELETING),
            STARTING to setOf(RUNNING, FAILED, DELETING),
            RUNNING to setOf(STOPPING, FAILED, EXPIRED, DELETING),
            STOPPING to setOf(STOPPED, FAILED, DELETING),
            STOPPED to setOf(STARTING, DELETING),
            FAILED to setOf(DELETING),
            EXPIRED to setOf(DELETING),
            DELETING to setOf(DELETED),
            DELETED to emptySet(),
        )

        fun canTransition(from: RuntimeStatus, to: RuntimeStatus): Boolean =
            ALLOWED_TRANSITIONS[from]?.contains(to) == true
    }
}

/**
 * Runtime: an isolated execution environment that runs on top of a Workspace.
 * The MariaDB row is the source of truth; the Kubernetes Pod is derived from it.
 */
data class Runtime(
    val id: String,
    val workspaceId: String,
    val templateId: String,
    val status: RuntimeStatus,
    val resourcesJson: String?,      // JSON: CPU/memory/ephemeral storage requests & limits
    val envJson: String?,            // JSON: environment variables
    val ttlSeconds: Long?,           // Time-to-live from createdAt; null = no TTL
    val podName: String?,
    val errorMessage: String?,
    val labelsJson: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun canTransitionTo(to: RuntimeStatus): Boolean = RuntimeStatus.canTransition(status, to)

    companion object {
        const val POD_NAME_PREFIX: String = "rt-"

        /** Pod name derived from the runtime id (plan chapter 7.2). */
        fun podNameFor(id: String): String = POD_NAME_PREFIX + id
    }
}