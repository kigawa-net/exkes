package net.kigawa.exkes.controller

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimStatus
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspacePlacementState
import net.kigawa.exkes.common.domain.WorkspaceStatus
import net.kigawa.exkes.controller.runtime.verifyRuntimeLivePvc
import net.kigawa.exkes.controller.workspace.KubernetesWorkspaceProvisioner

class RuntimePvcIdentityTest {
    private val namespace = "platform-exkes-stg-sandbox"
    private val workspaceId = "00000000-0000-0000-0000-000000000055"
    private val claimName = "ws-$workspaceId"

    private fun workspace(): Workspace = Workspace(
        id = workspaceId,
        name = "live-uid-test",
        status = WorkspaceStatus.READY,
        storageClass = "rook-cephfs",
        storageSizeBytes = 1024,
        pvcName = claimName,
        errorMessage = null,
        labels = emptyMap(),
        archivedAt = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        pvcNamespace = namespace,
        pvcUid = "original-pvc-uid",
        placementGeneration = 2,
        placementState = WorkspacePlacementState.VERIFIED,
    )

    private fun runtime(): Runtime = Runtime(
        id = "00000000-0000-0000-0000-000000000056",
        workspaceId = workspaceId,
        templateId = "template",
        status = RuntimeStatus.RUNNING,
        resourcesJson = null,
        envJson = null,
        ttlSeconds = null,
        podName = "rt-00000000-0000-0000-0000-000000000056",
        errorMessage = null,
        labelsJson = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        workspacePlacementGeneration = 2,
        workspacePvcNamespace = namespace,
        workspacePvcName = claimName,
        workspacePvcUid = "original-pvc-uid",
    )

    private fun pvc(uid: String = "original-pvc-uid", phase: String = "Bound"): V1PersistentVolumeClaim =
        V1PersistentVolumeClaim()
            .metadata(V1ObjectMeta().name(claimName).namespace(namespace).uid(uid).labels(
                mapOf(
                    KubernetesWorkspaceProvisioner.MANAGED_BY_LABEL to KubernetesWorkspaceProvisioner.MANAGED_BY_VALUE,
                    KubernetesWorkspaceProvisioner.WORKSPACE_ID_LABEL to workspaceId,
                ),
            ))
            .spec(V1PersistentVolumeClaimSpec()
                .storageClassName("rook-cephfs")
                .accessModes(listOf("ReadWriteMany"))
                .resources(V1VolumeResourceRequirements().requests(mapOf("storage" to Quantity("2048")))))
            .status(V1PersistentVolumeClaimStatus().phase(phase))

    @Test
    fun `matching live PVC UID permits verification`() {
        verifyRuntimeLivePvc(runtime(), workspace(), pvc(), namespace)
    }

    @Test
    fun `same name but recreated PVC UID is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            verifyRuntimeLivePvc(runtime(), workspace(), pvc(uid = "replacement-pvc-uid"), namespace)
        }
    }

    @Test
    fun `pending PVC cannot authorize Runtime Ready`() {
        assertFailsWith<IllegalArgumentException> {
            verifyRuntimeLivePvc(runtime(), workspace(), pvc(phase = "Pending"), namespace)
        }
    }

    @Test
    fun `wrong namespace or foreign ownership cannot authorize Runtime`() {
        assertFailsWith<IllegalArgumentException> {
            verifyRuntimeLivePvc(runtime(), workspace(), pvc(), "foreign-namespace")
        }
        val foreign = pvc()
        foreign.metadata!!.putLabelsItem(KubernetesWorkspaceProvisioner.WORKSPACE_ID_LABEL, "foreign-workspace")
        assertFailsWith<IllegalArgumentException> {
            verifyRuntimeLivePvc(runtime(), workspace(), foreign, namespace)
        }
    }

    @Test
    fun `legacy Runtime without placement snapshot cannot authorize Runtime`() {
        assertFailsWith<IllegalArgumentException> {
            verifyRuntimeLivePvc(runtime().copy(workspacePvcUid = null), workspace(), pvc(), namespace)
        }
    }
}
