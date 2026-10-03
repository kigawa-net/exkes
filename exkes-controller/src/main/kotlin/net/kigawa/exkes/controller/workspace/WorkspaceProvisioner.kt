package net.kigawa.exkes.controller.workspace

import net.kigawa.exkes.common.domain.Workspace

/** Observed state of the workspace PVC. */
enum class PvcState {
    ABSENT,
    PENDING,
    BOUND,
}

data class ProvisionOutcome(
    val pvcName: String,
    val state: PvcState,
)

/**
 * Name of the PVC backing a workspace: the recorded one, or the deterministic
 * `ws-<id>` fallback.
 *
 * The fallback matters when DELETE arrives while the workspace is still CREATING:
 * the reconciler may not have persisted pvc_name yet, but the PVC already exists
 * under the derived name and must be deleted, not leaked (plan chapter 6.2).
 * Both the provisioner and the test fake resolve it here so the rule has a
 * single implementation.
 */
fun Workspace.resolvePvcName(): String = pvcName ?: Workspace.pvcNameFor(id)

/**
 * PVC provisioning abstraction.
 *
 * The reconciler only reads DB -> calls provisioner -> writes DB, so it stays
 * K8s-free and testable with a fake.
 */
interface WorkspaceProvisioner {
    /**
     * Ensures the PVC for the workspace exists and reports its current state.
     * Throws when the underlying backend fails; the reconciler decides whether
     * to retry or move the workspace to ERROR.
     */
    fun ensurePvc(workspace: Workspace): ProvisionOutcome

    /**
     * Requests PVC deletion. Returns true when no PVC remains (deleted now, or
     * never created); false when deletion was requested but not confirmed yet.
     */
    fun deletePvc(workspace: Workspace): Boolean
}
