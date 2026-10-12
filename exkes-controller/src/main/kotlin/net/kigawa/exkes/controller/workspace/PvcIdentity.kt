package net.kigawa.exkes.controller.workspace

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import net.kigawa.exkes.common.domain.Workspace

/**
 * Validates that a PVC is compatible with the workspace's trusted placement.
 * This is necessary but not sufficient for first-time adoption: matching labels
 * are user-mutable, so an existing PVC without a previously recorded UID is
 * still untrusted and must be reconciled manually.
 */
internal fun verifyPvcIdentity(
    workspace: Workspace,
    pvc: V1PersistentVolumeClaim,
    namespace: String,
): String {
    val name = workspace.resolvePvcName()
    require(workspace.pvcNamespace == namespace) {
        "Workspace ${workspace.id} is not placed in namespace $namespace"
    }
    val metadata = requireNotNull(pvc.metadata) { "PVC $name has no metadata" }
    require(metadata.name == name && metadata.namespace == namespace) {
        "PVC identity/name/namespace mismatch for Workspace ${workspace.id}"
    }
    require(metadata.deletionTimestamp == null) { "PVC $name is being deleted" }
    require(metadata.labels?.get(KubernetesWorkspaceProvisioner.MANAGED_BY_LABEL) ==
        KubernetesWorkspaceProvisioner.MANAGED_BY_VALUE
    ) { "PVC $name has an untrusted manager label" }
    require(metadata.labels?.get(KubernetesWorkspaceProvisioner.WORKSPACE_ID_LABEL) == workspace.id) {
        "PVC $name belongs to a different Workspace"
    }
    val uid = requireNotNull(metadata.uid?.takeIf { it.isNotBlank() }) {
        "PVC $name has no Kubernetes UID"
    }
    if (workspace.pvcUid != null) {
        require(workspace.pvcUid == uid) { "PVC $name UID has changed; refuse adoption/deletion" }
    }
    val spec = requireNotNull(pvc.spec) { "PVC $name has no spec" }
    require(spec.storageClassName == workspace.storageClass) { "PVC $name storageClass mismatch" }
    require("ReadWriteMany" in spec.accessModes.orEmpty()) { "PVC $name is not RWX" }
    val requested = requireNotNull(spec.resources?.requests?.get("storage")) {
        "PVC $name has no storage request"
    }
    require(requested.number.compareTo(Quantity(workspace.storageSizeBytes.toString()).number) >= 0) {
        "PVC $name capacity is smaller than the Workspace specification"
    }
    return uid
}
