package net.kigawa.exkes.controller.workspace

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1DeleteOptions
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Preconditions
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspacePlacementState
import net.kigawa.exkes.controller.config.KubernetesConfig
import net.kigawa.exkes.controller.k8s.KubernetesClientFactory
import org.slf4j.LoggerFactory

/**
 * CephFS PVC provisioning with the official client-java library.
 *
 * - PVC name: ws-<workspace id>
 * - storageClass: workspace.storageClass (rook-cephfs)
 * - accessMode: ReadWriteMany (multiple Runtimes may mount the same workspace)
 * - labels: app.kubernetes.io/managed-by=exkes, exkes.workspace-id=<id>
 *
 * Workspace PVCs are created in the sandbox resource namespace, alongside Runtime Pods. exkes-api has no
 * client-java dependency and runs without a ServiceAccount token, so this class
 * has to be moved together with its RBAC (issue #2 "Controller RBAC").
 *
 * The client is created lazily so the controller can start (and serve /health)
 * outside a cluster; provisioning failures surface as workspace ERROR states
 * instead of a startup crash.
 */
class KubernetesWorkspaceProvisioner(
    private val config: KubernetesConfig,
) : WorkspaceProvisioner {
    private val logger = LoggerFactory.getLogger(KubernetesWorkspaceProvisioner::class.java)

    private val api: CoreV1Api by lazy { CoreV1Api(KubernetesClientFactory.createClient(config)) }

    override fun ensurePvc(workspace: Workspace): ProvisionOutcome {
        require(workspace.pvcNamespace == config.resourceNamespace) {
            "Workspace ${workspace.id} PVC namespace is not the controller resource namespace"
        }
        require(workspace.placementState == WorkspacePlacementState.RESERVED ||
            workspace.placementState == WorkspacePlacementState.VERIFIED
        ) { "Workspace ${workspace.id} PVC placement is not reserved/verified" }

        val name = workspace.resolvePvcName()
        val existing = readPvc(name)
        if (existing == null && workspace.pvcUid != null) {
            // A previously verified PVC vanished. Recreating the same name
            // would silently attach an empty volume to an existing Workspace.
            throw IllegalStateException("verified PVC $name disappeared; refuse replacement")
        }
        if (existing != null && workspace.pvcUid == null) {
            // A matching label does NOT prove this PVC belongs to this Workspace.
            // This includes a create-response-loss after which the object exists:
            // operator reconciliation is required before binding the UID.
            throw IllegalStateException("PVC $name exists without a trusted recorded UID")
        }
        // createPvc throws on 409; never adopt an unknown winner of a name race.
        val created = existing == null
        val pvc = existing ?: createPvc(workspace, name)
        val uid = verifyPvcIdentity(workspace, pvc, config.resourceNamespace)
        val state = when (pvc.status?.phase) {
            "Bound" -> PvcState.BOUND
            "Lost" -> throw IllegalStateException("PVC $name is in phase Lost")
            else -> PvcState.PENDING
        }
        return ProvisionOutcome(name, state, uid, createdByThisAttempt = created)
    }

    override fun deletePvc(workspace: Workspace): Boolean {
        val namespace = requireNotNull(workspace.pvcNamespace) {
            "Workspace ${workspace.id} has no recorded PVC namespace; deletion blocked"
        }
        require(namespace == config.resourceNamespace) {
            "Workspace ${workspace.id} PVC namespace mismatch; deletion blocked"
        }
        require(workspace.placementState == WorkspacePlacementState.VERIFIED) {
            "Workspace ${workspace.id} PVC identity is not verified; deletion blocked"
        }
        val uid = requireNotNull(workspace.pvcUid?.takeIf { it.isNotBlank() }) {
            "Workspace ${workspace.id} has no verified PVC UID; deletion blocked"
        }
        val name = workspace.resolvePvcName()
        val pvc = readPvc(name) ?: return true
        verifyPvcIdentity(workspace, pvc, namespace)
        try {
            api.deleteNamespacedPersistentVolumeClaim(name, namespace)
                .body(V1DeleteOptions().preconditions(V1Preconditions().uid(uid)))
                .propagationPolicy("Foreground")
                .execute()
            logger.info("UID-preconditioned delete requested for PVC {} of workspace {}", name, workspace.id)
            return false
        } catch (e: ApiException) {
            if (e.code == 404) return true
            // 409/422 precondition mismatch is never interpreted as success.
            throw e
        }
    }

    private fun readPvc(name: String): V1PersistentVolumeClaim? = try {
        api.readNamespacedPersistentVolumeClaim(name, config.resourceNamespace).execute()
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    private fun createPvc(workspace: Workspace, name: String): V1PersistentVolumeClaim {
        val spec = V1PersistentVolumeClaimSpec()
            .accessModes(listOf("ReadWriteMany"))
            // A plain integer Quantity is interpreted as bytes, matching
            // workspaces.storage_size (BIGINT, bytes).
            .resources(
                V1VolumeResourceRequirements()
                    .requests(mapOf("storage" to Quantity(workspace.storageSizeBytes.toString()))),
            )
            .storageClassName(workspace.storageClass)

        val pvc = V1PersistentVolumeClaim()
            .metadata(
                V1ObjectMeta()
                    .name(name)
                    .namespace(config.resourceNamespace)
                    .labels(
                        mapOf(
                            MANAGED_BY_LABEL to MANAGED_BY_VALUE,
                            WORKSPACE_ID_LABEL to workspace.id,
                        ),
                    ),
            )
            .spec(spec)

        return try {
            api.createNamespacedPersistentVolumeClaim(config.resourceNamespace, pvc).execute()
                .also { logger.info("created PVC {} for workspace {}", name, workspace.id) }
        } catch (e: ApiException) {
            // An already existing PVC may be foreign, even with matching labels.
            // Never adopt a 409 winner until its UID has been bound by trusted evidence.
            throw e
        }
    }

    companion object {
        const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
        const val MANAGED_BY_VALUE = "exkes"
        const val WORKSPACE_ID_LABEL = "exkes.workspace-id"
    }
}
