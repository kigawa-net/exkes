package net.kigawa.exkes.controller

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspacePlacementState
import net.kigawa.exkes.common.domain.WorkspaceStatus
import net.kigawa.exkes.controller.workspace.KubernetesWorkspaceProvisioner
import net.kigawa.exkes.controller.workspace.verifyPvcIdentity

class PvcIdentityTest {
    private val namespace = "platform-exkes-stg-sandbox"

    private fun workspace(uid: String? = null): Workspace = Workspace(
        id = "00000000-0000-0000-0000-000000000041",
        name = "identity-test",
        status = WorkspaceStatus.CREATING,
        storageClass = "rook-cephfs",
        storageSizeBytes = 1024L,
        pvcName = "ws-00000000-0000-0000-0000-000000000041",
        errorMessage = null,
        labels = emptyMap(),
        archivedAt = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        pvcNamespace = namespace,
        pvcUid = uid,
        placementGeneration = if (uid == null) 1 else 2,
        placementState = if (uid == null) WorkspacePlacementState.RESERVED else WorkspacePlacementState.VERIFIED,
    )

    private fun pvc(workspace: Workspace): V1PersistentVolumeClaim = V1PersistentVolumeClaim()
        .metadata(V1ObjectMeta()
            .name(workspace.pvcName)
            .namespace(namespace)
            .uid("pvc-uid-1")
            .labels(mapOf(
                KubernetesWorkspaceProvisioner.MANAGED_BY_LABEL to KubernetesWorkspaceProvisioner.MANAGED_BY_VALUE,
                KubernetesWorkspaceProvisioner.WORKSPACE_ID_LABEL to workspace.id,
            )))
        .spec(V1PersistentVolumeClaimSpec()
            .storageClassName("rook-cephfs")
            .accessModes(listOf("ReadWriteMany"))
            .resources(V1VolumeResourceRequirements()
                .requests(mapOf("storage" to Quantity("2048")))))

    @Test
    fun `verified PVC identity is accepted`() {
        val workspace = workspace("pvc-uid-1")
        assertEquals("pvc-uid-1", verifyPvcIdentity(workspace, pvc(workspace), namespace))
    }

    @Test
    fun `foreign owner and workspace identity are rejected`() {
        val ws = workspace()
        val foreignManager = pvc(ws)
        foreignManager.metadata!!.putLabelsItem(KubernetesWorkspaceProvisioner.MANAGED_BY_LABEL, "other")
        assertFailsWith<IllegalArgumentException> { verifyPvcIdentity(ws, foreignManager, namespace) }

        val foreignWorkspace = pvc(ws)
        foreignWorkspace.metadata!!.putLabelsItem(KubernetesWorkspaceProvisioner.WORKSPACE_ID_LABEL, "someone-else")
        assertFailsWith<IllegalArgumentException> { verifyPvcIdentity(ws, foreignWorkspace, namespace) }
    }

    @Test
    fun `recreated UID and foreign namespace are rejected`() {
        val ws = workspace("expected-uid")
        assertFailsWith<IllegalArgumentException> { verifyPvcIdentity(ws, pvc(ws), namespace) }
        assertFailsWith<IllegalArgumentException> {
            verifyPvcIdentity(workspace(), pvc(workspace()).metadata(
                V1ObjectMeta().name(workspace().pvcName).namespace("platform-exkes-stg")
                    .uid("pvc-uid-1").labels(mapOf(
                        KubernetesWorkspaceProvisioner.MANAGED_BY_LABEL to KubernetesWorkspaceProvisioner.MANAGED_BY_VALUE,
                        KubernetesWorkspaceProvisioner.WORKSPACE_ID_LABEL to ws.id,
                    ))), namespace)
        }
    }

    @Test
    fun `wrong storage class access mode and capacity are rejected`() {
        val ws = workspace()
        val wrongClass = pvc(ws)
        wrongClass.spec!!.storageClassName("not-cephfs")
        assertFailsWith<IllegalArgumentException> { verifyPvcIdentity(ws, wrongClass, namespace) }

        val wrongAccessMode = pvc(ws)
        wrongAccessMode.spec!!.accessModes(listOf("ReadWriteOnce"))
        assertFailsWith<IllegalArgumentException> { verifyPvcIdentity(ws, wrongAccessMode, namespace) }

        val tooSmall = pvc(ws)
        tooSmall.spec!!.resources!!.requests(mapOf("storage" to Quantity("512")))
        assertFailsWith<IllegalArgumentException> { verifyPvcIdentity(ws, tooSmall, namespace) }
    }

    @Test
    fun `PVC with missing UID is never trusted`() {
        val ws = workspace()
        val noUid = pvc(ws)
        noUid.metadata!!.uid(null)
        assertFailsWith<IllegalArgumentException> { verifyPvcIdentity(ws, noUid, namespace) }
    }
}
