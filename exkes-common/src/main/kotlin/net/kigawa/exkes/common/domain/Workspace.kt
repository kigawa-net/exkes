package net.kigawa.exkes.common.domain

import java.time.Instant

/**
 * Workspace lifecycle states (plan chapter 7.1).
 *
 * ```text
 * (none) -> CREATING -> READY -> ARCHIVED
 *              |          |          |
 *              |          +----------+--> DELETING -> DELETED (final)
 *              +-------> ERROR -------+
 * ```
 */
enum class WorkspaceStatus {
    CREATING,
    READY,
    ARCHIVED,
    DELETING,
    DELETED,
    ERROR,
    ;

    companion object {
        // Allowed transitions. Every status change must go through
        // WorkspaceRepository#transition which enforces this table with a
        // conditional UPDATE (WHERE id = ? AND status = ?).
        private val ALLOWED_TRANSITIONS: Map<WorkspaceStatus, Set<WorkspaceStatus>> = mapOf(
            // DELETE during CREATING is accepted: the reconciler finishes the
            // transition once PVC creation settles (plan chapter 6.2).
            CREATING to setOf(READY, ERROR, DELETING),
            READY to setOf(ARCHIVED, DELETING),
            ARCHIVED to setOf(DELETING),
            ERROR to setOf(DELETING),
            DELETING to setOf(DELETED),
            DELETED to emptySet(),
        )

        fun canTransition(from: WorkspaceStatus, to: WorkspaceStatus): Boolean =
            ALLOWED_TRANSITIONS[from]?.contains(to) == true
    }
}

/**
 * Workspace: a filesystem that outlives Runtimes and is persisted on CephFS.
 * The MariaDB row is the source of truth; the PVC is derived from it.
 */
data class Workspace(
    val id: String,
    val name: String,
    val status: WorkspaceStatus,
    val storageClass: String,
    val storageSizeBytes: Long,
    val pvcName: String?,
    val errorMessage: String?,
    val labels: Map<String, String>,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun canTransitionTo(to: WorkspaceStatus): Boolean = WorkspaceStatus.canTransition(status, to)

    /** True if a Runtime can be attached (PVC is bound and workspace is usable). */
    fun canAttachRuntime(): Boolean = status == WorkspaceStatus.READY || status == WorkspaceStatus.ARCHIVED

    companion object {
        const val DEFAULT_STORAGE_SIZE_BYTES: Long = 1024L * 1024 * 1024
        const val PVC_NAME_PREFIX: String = "ws-"

        /** PVC name derived from the workspace id (plan chapter 7.2). */
        fun pvcNameFor(id: String): String = PVC_NAME_PREFIX + id
    }
}
