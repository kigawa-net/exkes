package net.kigawa.exkes.controller

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1PodSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.RuntimeTemplate
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspacePlacementState
import net.kigawa.exkes.common.domain.WorkspaceStatus
import net.kigawa.exkes.controller.config.RuntimeAgentConfig
import net.kigawa.exkes.controller.runtime.RUNTIME_AGENT_PORT
import net.kigawa.exkes.controller.runtime.RUNTIME_AGENT_STATE_PATH
import net.kigawa.exkes.controller.runtime.RUNTIME_UID
import net.kigawa.exkes.controller.runtime.RUNTIME_WORKSPACE_PATH
import net.kigawa.exkes.controller.runtime.buildRuntimePodSpec
import net.kigawa.exkes.controller.workspace.resolvePvcName

class RuntimePodSpecTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val agentImage = "harbor.kigawa.net/private/exkes-runtime-agent:main-0123456789abcdef"
    private val agent = RuntimeAgentConfig(image = agentImage, imagePullSecret = "harbor-registry")

    private fun workspace(): Workspace = Workspace(
        id = "00000000-0000-0000-0000-000000000077",
        name = "pod-spec",
        status = WorkspaceStatus.READY,
        storageClass = "rook-cephfs",
        storageSizeBytes = 1024,
        pvcName = "ws-00000000-0000-0000-0000-000000000077",
        errorMessage = null,
        labels = emptyMap(),
        archivedAt = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        pvcNamespace = "sandbox",
        pvcUid = "uid",
        placementGeneration = 1,
        placementState = WorkspacePlacementState.VERIFIED,
    )

    private fun template(): RuntimeTemplate = RuntimeTemplate(
        id = "tpl",
        name = "tpl",
        description = null,
        image = "harbor.kigawa.net/private/user-image:1",
        defaultResourcesJson = """{"cpu":"500m","memory":"512Mi"}""",
        defaultEnvJson = """{"A":"template","B":"template-only"}""",
        defaultTtlSeconds = null,
        labelsJson = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun runtime(resources: String? = null, env: String? = null): Runtime = Runtime(
        id = "rt-1",
        workspaceId = workspace().id,
        templateId = "tpl",
        status = RuntimeStatus.STARTING,
        resourcesJson = resources,
        envJson = env,
        ttlSeconds = null,
        podName = null,
        errorMessage = null,
        labelsJson = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun spec(
        runtime: Runtime = runtime(),
        agentConfig: RuntimeAgentConfig = agent,
    ): V1PodSpec = buildRuntimePodSpec(runtime, template(), workspace(), agentConfig, json)

    @Test
    fun `both containers run as the numeric non-root uid without privilege escalation`() {
        val podSpec = spec()
        assertTrue(podSpec.securityContext.runAsNonRoot)
        assertEquals(RUNTIME_UID, podSpec.securityContext.runAsUser)
        assertEquals("RuntimeDefault", podSpec.securityContext.seccompProfile.type)
        for (container in podSpec.containers) {
            val sc = container.securityContext
            assertEquals(false, sc.allowPrivilegeEscalation, container.name)
            assertEquals(false, sc.privileged, container.name)
            assertEquals(true, sc.runAsNonRoot, container.name)
            assertEquals(RUNTIME_UID, sc.runAsUser, container.name)
            assertEquals(listOf("ALL"), sc.capabilities.drop, container.name)
        }
    }

    @Test
    fun `user container keeps a writable root filesystem and the agent gets a read-only one`() {
        val byName = spec().containers.associateBy { it.name }
        assertEquals(false, byName.getValue("user").securityContext.readOnlyRootFilesystem)
        assertEquals(true, byName.getValue("runtime-agent").securityContext.readOnlyRootFilesystem)
    }

    @Test
    fun `agent writes only to tmp, the workspace and its state directory`() {
        val agentContainer = spec().containers.single { it.name == "runtime-agent" }
        val mounts = agentContainer.volumeMounts.associate { it.mountPath to it.name }
        assertEquals(
            mapOf(
                RUNTIME_WORKSPACE_PATH to "workspace",
                "/tmp" to "tmp",
                RUNTIME_AGENT_STATE_PATH to "agent-state",
            ),
            mounts,
        )
    }

    @Test
    fun `agent uses the configured image and points at the workspace and its state dir`() {
        val agentContainer = spec().containers.single { it.name == "runtime-agent" }
        assertEquals(agentImage, agentContainer.image)
        val env = agentContainer.env.associate { it.name to it.value }
        assertEquals(RUNTIME_WORKSPACE_PATH, env["EXKES_AGENT_WORKING_DIR"])
        assertEquals(RUNTIME_AGENT_STATE_PATH, env["EXKES_AGENT_STATE_DIR"])
    }

    @Test
    fun `agent readiness is the runtime-agent health endpoint on the agent port`() {
        val probe = spec().containers.single { it.name == "runtime-agent" }.readinessProbe
        assertEquals("/health", probe.httpGet.path)
        assertEquals(RUNTIME_AGENT_PORT, probe.httpGet.port.intValue)
    }

    @Test
    fun `user container runs the template image`() {
        val user = spec().containers.single { it.name == "user" }
        assertEquals("harbor.kigawa.net/private/user-image:1", user.image)
    }

    @Test
    fun `image pull secret is attached when configured`() {
        assertEquals(listOf("harbor-registry"), spec().imagePullSecrets.map { it.name })
    }

    @Test
    fun `no imagePullSecrets when the secret is not configured`() {
        assertNull(spec(agentConfig = agent.copy(imagePullSecret = null)).imagePullSecrets)
    }

    @Test
    fun `volumes are limited to the workspace PVC and emptyDirs`() {
        val volumes = spec().volumes
        assertEquals(setOf("workspace", "tmp", "agent-state"), volumes.map { it.name }.toSet())
        assertEquals(workspace().resolvePvcName(), volumes.single { it.name == "workspace" }.persistentVolumeClaim.claimName)
        assertTrue(volumes.filter { it.name != "workspace" }.all { it.emptyDir != null })
        assertFalse(volumes.any { it.hostPath != null })
    }

    @Test
    fun `template resource defaults with numeric values are decoded`() {
        val numeric = Runtime(
            id = "rt-2", workspaceId = workspace().id, templateId = "tpl", status = RuntimeStatus.STARTING,
            resourcesJson = """{"cpu":2,"memory":"1Gi"}""", envJson = null, ttlSeconds = null, podName = null,
            errorMessage = null, labelsJson = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val user = spec(runtime = numeric).containers.single { it.name == "user" }
        assertEquals(Quantity("2"), user.resources.limits.getValue("cpu"))
        assertEquals(Quantity("1Gi"), user.resources.limits.getValue("memory"))
    }

    @Test
    fun `runtime env overrides template env and runtime resources override template defaults`() {
        val user = spec(runtime = runtime(resources = """{"cpu":"2"}""", env = """{"A":"runtime"}"""))
            .containers.single { it.name == "user" }
        assertEquals(mapOf("A" to "runtime", "B" to "template-only"), user.env.associate { it.name to it.value })
        assertEquals(Quantity("2"), user.resources.limits.getValue("cpu"))
        assertNull(user.resources.limits["memory"])
    }
}
