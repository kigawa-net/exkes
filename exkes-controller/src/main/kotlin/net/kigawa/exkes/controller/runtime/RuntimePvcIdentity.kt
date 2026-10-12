package net.kigawa.exkes.controller.runtime

import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.controller.workspace.verifyPvcIdentity

/**
 * A database Runtime/Workspace placement snapshot is not enough when the PVC
 * name has been reused. Require the *live* Kubernetes UID and Bound status.
 *
 * This is a read-only admission guard, not an atomic PVC/Pod lease or proof
 * that an old Pod/Writer has terminated (#31/#55).
 */
internal fun verifyRuntimeLivePvc(
    runtime: Runtime,
    workspace: Workspace,
    pvc: V1PersistentVolumeClaim,
    resourceNamespace: String,
) {
    require(runtime.matchesWorkspacePlacement(workspace)) {
        "Runtime ${runtime.id} has an unverified or stale Workspace placement snapshot"
    }
    require(workspace.pvcNamespace == resourceNamespace) {
        "Workspace ${workspace.id} is not placed in the Runtime resource namespace"
    }
    val liveUid = verifyPvcIdentity(workspace, pvc, resourceNamespace)
    require(liveUid == runtime.workspacePvcUid) {
        "Runtime ${runtime.id} PVC UID differs from the live Kubernetes PVC"
    }
    require(pvc.status?.phase == "Bound") {
        "Workspace PVC ${workspace.pvcName} is not Bound"
    }
}
