package net.kigawa.exkes.controller.workspace

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements
import net.kigawa.exkes.common.domain.Workspace
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
 * This is the only Kubernetes write path of the Control Plane. exkes-api has no
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
        val name = workspace.resolvePvcName()
        val existing = readPvc(name)
        val pvc = existing ?: createPvc(workspace, name)

        val state = when (pvc.status?.phase) {
            "Bound" -> PvcState.BOUND
            // "Lost" means the backing volume is gone; surface it as a failure.
            "Lost" -> throw IllegalStateException("PVC $name is in phase Lost")
            else -> PvcState.PENDING
        }
        return ProvisionOutcome(name, state)
    }

    override fun deletePvc(workspace: Workspace): Boolean {
        val name = workspace.resolvePvcName()
        try {
            api.deleteNamespacedPersistentVolumeClaim(name, config.namespace)
                .propagationPolicy("Foreground")
                .execute()
            logger.info("delete requested for PVC {} of workspace {}", name, workspace.id)
            // Deletion is confirmed by the next reconcile cycle (404).
            return false
        } catch (e: ApiException) {
            if (e.code == 404) return true
            throw e
        }
    }

    private fun readPvc(name: String): V1PersistentVolumeClaim? = try {
        api.readNamespacedPersistentVolumeClaim(name, config.namespace).execute()
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
                    .namespace(config.namespace)
                    .labels(
                        mapOf(
                            MANAGED_BY_LABEL to MANAGED_BY_VALUE,
                            WORKSPACE_ID_LABEL to workspace.id,
                        ),
                    ),
            )
            .spec(spec)

        return try {
            api.createNamespacedPersistentVolumeClaim(config.namespace, pvc).execute()
                .also { logger.info("created PVC {} for workspace {}", name, workspace.id) }
        } catch (e: ApiException) {
            if (e.code == 409) {
                // Lost a creation race; the next read observes the winner.
                readPvc(name) ?: throw e
            } else {
                throw e
            }
        }
    }

    companion object {
        const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
        const val MANAGED_BY_VALUE = "exkes"
        const val WORKSPACE_ID_LABEL = "exkes.workspace-id"
    }
}
