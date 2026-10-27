package net.kigawa.exkes.controller.runtime

import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Capabilities
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource
import io.kubernetes.client.openapi.models.V1EnvVar
import io.kubernetes.client.openapi.models.V1HTTPGetAction
import io.kubernetes.client.openapi.models.V1LocalObjectReference
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodSpec
import io.kubernetes.client.openapi.models.V1PodSecurityContext
import io.kubernetes.client.openapi.models.V1Probe
import io.kubernetes.client.openapi.models.V1ResourceRequirements
import io.kubernetes.client.openapi.models.V1SeccompProfile
import io.kubernetes.client.openapi.models.V1SecurityContext
import io.kubernetes.client.openapi.models.V1Volume
import io.kubernetes.client.openapi.models.V1VolumeMount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import net.kigawa.exkes.common.config.WorkspaceConfig
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeSpec
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.RuntimeTemplate
import net.kigawa.exkes.common.domain.RuntimeTemplateNotFoundException
import net.kigawa.exkes.common.domain.KubernetesRuntimeException
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.controller.config.KubernetesConfig
import net.kigawa.exkes.controller.config.RuntimeAgentConfig
import net.kigawa.exkes.controller.k8s.KubernetesClientFactory
import net.kigawa.exkes.controller.workspace.resolvePvcName
import org.slf4j.LoggerFactory

/**
 * Kubernetes implementation of [RuntimeProvisioner].
 *
 * Creates a Pod with:
 * - User container (from RuntimeTemplate.image)
 * - runtime-agent sidecar (image from [RuntimeAgentConfig], for process/PTY/log management)
 * - Workspace PVC mount at /workspace (both containers)
 * - Resource limits/requests from RuntimeSpec or template defaults
 * - Environment variables from RuntimeSpec or template defaults
 * - SecurityContext: non-root, drop ALL capabilities, seccomp RuntimeDefault; the agent
 *   additionally runs with a read-only root filesystem (see [buildRuntimePodSpec])
 * - imagePullSecrets from [RuntimeAgentConfig.imagePullSecret] (the Secret must exist in the Sandbox namespace)
 *
 * Also manages the Runtime's ClusterIP Service (see [RuntimeServiceManager]): name = Pod name,
 * selects only that Pod, port 8080 -> runtime-agent.
 *
 * The controller's ServiceAccount must have Pod create/get/list/watch/delete permissions
 * and Service get/create/delete permissions in the Sandbox namespace (issue #2 "Controller RBAC").
 */
class KubernetesRuntimeProvider(
    private val kubernetesConfig: KubernetesConfig,
    private val templateRepository: RuntimeTemplateRepository,
    private val workspaceConfig: WorkspaceConfig,
    private val workspaceRepository: WorkspaceRepository,
    private val runtimeAgent: RuntimeAgentConfig,
) : RuntimeProvisioner {
    private val logger = LoggerFactory.getLogger(KubernetesRuntimeProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val api: CoreV1Api by lazy { CoreV1Api(KubernetesClientFactory.createClient(kubernetesConfig)) }
    private val namespace: String = kubernetesConfig.resourceNamespace
    private val services: RuntimeServiceManager by lazy {
        RuntimeServiceManager(CoreV1RuntimeServiceStore(api, namespace))
    }

    override fun ensureService(runtime: Runtime) = services.ensure(runtime)

    override fun deleteService(runtime: Runtime): Boolean = services.delete(runtime)

    override fun createRuntime(runtime: Runtime): String {
        val workspace = verifiedWorkspace(runtime)
        verifyLivePvc(runtime, workspace)
        val template = templateRepository.findById(runtime.templateId)
            ?: throw RuntimeTemplateNotFoundException(runtime.templateId)

        val podName = resolvePodName(runtime)
        val pod = buildPod(runtime, template, podName, workspace)

        return try {
            api.createNamespacedPod(namespace, pod).execute()
                .also { logger.info("created Pod {} for runtime {}", podName, runtime.id) }
            podName
        } catch (e: ApiException) {
            if (e.code == 409) {
                // Lost a creation race (another actor created the same name). Adopt the Pod
                // only when it belongs to this runtime; a foreign Pod is never attached.
                val existing = readPod(podName) ?: throw e
                if (!isPodOwnedBy(existing, runtime.id)) {
                    throw KubernetesRuntimeException("Pod $podName exists but is not owned by runtime ${runtime.id}", e)
                }
                podName
            } else {
                throw KubernetesRuntimeException("failed to create Pod $podName: ${e.message}", e)
            }
        }
    }

    override fun startRuntime(runtime: Runtime) {
        verifyLivePvc(runtime, verifiedWorkspace(runtime))
        // Restart path: stop deleted the Pod but kept the Workspace PVC, so a missing
        // Pod is recreated here (re-attaching the PVC). An existing Pod is left alone.
        val podName = resolvePodName(runtime)
        val existing = readPod(podName)
        if (existing != null) {
            if (!isPodOwnedBy(existing, runtime.id)) {
                throw KubernetesRuntimeException("Pod $podName exists but is not owned by runtime ${runtime.id}")
            }
            logger.debug("startRuntime called for {} (Pod {} exists)", runtime.id, podName)
            return
        }
        createRuntime(runtime)
    }

    override fun stopRuntime(runtime: Runtime): Boolean {
        val podName = resolvePodName(runtime)
        try {
            api.deleteNamespacedPod(podName, namespace)
                .propagationPolicy("Foreground")
                .gracePeriodSeconds(30)
                .execute()
            logger.info("delete requested for Pod {} of runtime {}", podName, runtime.id)
            // Deletion is confirmed by the next reconcile cycle (404).
            return false
        } catch (e: ApiException) {
            if (e.code == 404) return true
            throw KubernetesRuntimeException("failed to delete Pod $podName: ${e.message}", e)
        }
    }

    override fun deleteRuntime(runtime: Runtime): Boolean {
        // Same as stopRuntime for Kubernetes - delete the Pod
        return stopRuntime(runtime)
    }

    override fun getRuntimeStatus(runtime: Runtime): RuntimeStatus {
        // A Pod can remain Ready after its PVC was recreated with the same name.
        // Do not issue a Ready observation against an unverified live claim.
        verifyLivePvc(runtime, verifiedWorkspace(runtime))
        val podName = resolvePodName(runtime)
        val pod = readPod(podName)
        return when {
            pod == null -> RuntimeStatus.FAILED // Pod vanished unexpectedly
            !isPodOwnedBy(pod, runtime.id) -> RuntimeStatus.FAILED // a name clash is not this runtime's Pod
            else -> mapPodPhaseToRuntimeStatus(pod)
        }
    }

    override fun streamRuntimeLogs(runtime: Runtime): String {
        val podName = resolvePodName(runtime)
        // Read logs from the user container (first container)
        return try {
            api.readNamespacedPodLog(podName, namespace)
                .container("user")
                .tailLines(100)
                .execute()
        } catch (e: ApiException) {
            if (e.code == 404) {
                throw KubernetesRuntimeException("Pod $podName not found", e)
            }
            throw KubernetesRuntimeException("failed to read logs for Pod $podName: ${e.message}", e)
        }
    }

    private fun verifiedWorkspace(runtime: Runtime): Workspace {
        val workspace = workspaceRepository.findById(runtime.workspaceId)
            ?: throw KubernetesRuntimeException("Workspace not found for runtime ${runtime.id}")
        if (!runtime.matchesWorkspacePlacement(workspace) || workspace.pvcNamespace != namespace) {
            throw KubernetesRuntimeException("Workspace placement is not verified in the Runtime namespace for ${runtime.id}")
        }
        return workspace
    }

    /** Fail closed on Kubernetes API errors, missing PVC, UID drift or unbound claims. */
    private fun verifyLivePvc(runtime: Runtime, workspace: Workspace) {
        val name = workspace.resolvePvcName()
        val pvc = try {
            api.readNamespacedPersistentVolumeClaim(name, namespace).execute()
        } catch (e: ApiException) {
            throw RuntimePvcUnverifiedException("failed to read live PVC for runtime ${runtime.id} (HTTP ${e.code})", e)
        }
        try {
            verifyRuntimeLivePvc(runtime, workspace, pvc, namespace)
        } catch (e: IllegalArgumentException) {
            throw RuntimePvcUnverifiedException("unverified live PVC for runtime ${runtime.id}: ${e.message}", e)
        }
    }

    private fun buildPod(runtime: Runtime, template: RuntimeTemplate, podName: String, workspace: Workspace): V1Pod {
        val spec = buildRuntimePodSpec(runtime, template, workspace, runtimeAgent, json)

        return V1Pod()
            .metadata(
                V1ObjectMeta()
                    .name(podName)
                    .namespace(namespace)
                    .labels(
                        // Defense in depth for older rows: system-owned identity wins
                        // even if unvalidated client labels already exist in MariaDB.
                        (runtime.labelsJson?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()) +
                            runtimeSystemLabels(runtime),
                    ),
            )
            .spec(spec)
    }

    private fun mapPodPhaseToRuntimeStatus(pod: V1Pod): RuntimeStatus = mapPodToRuntimeStatus(pod)

    private fun readPod(name: String): V1Pod? = try {
        api.readNamespacedPod(name, namespace).execute()
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }
}

/** UID both containers run as. Numeric so runAsNonRoot can be verified without the image's USER name. */
internal const val RUNTIME_UID: Long = 1000L

/** Where the workspace PVC is mounted in both containers. Also the runtime-agent working directory. */
internal const val RUNTIME_WORKSPACE_PATH = "/workspace"

/** runtime-agent state directory (execution metadata). An emptyDir, so the agent root filesystem can be read-only. */
internal const val RUNTIME_AGENT_STATE_PATH = "/var/lib/exkes-agent"

/**
 * PodSpec of a Runtime: user container (template image) + runtime-agent sidecar, both mounting
 * the workspace PVC at [RUNTIME_WORKSPACE_PATH]. Written against the Pod Security `restricted`
 * profile: runAsNonRoot with a numeric UID, no privilege escalation, all capabilities dropped,
 * RuntimeDefault seccomp, no host namespaces, only PVC and emptyDir volumes.
 */
internal fun buildRuntimePodSpec(
    runtime: Runtime,
    template: RuntimeTemplate,
    workspace: Workspace,
    agent: RuntimeAgentConfig,
    json: Json,
): V1PodSpec {
    // Override (Runtime) wins over the template default, as a whole value.
    val resources = runtime.resourcesJson ?: template.defaultResourcesJson
    val envVars = mergeRuntimeEnv(template.defaultEnvJson, runtime.envJson, json)

    val workspaceMount = V1VolumeMount().name("workspace").mountPath(RUNTIME_WORKSPACE_PATH).readOnly(false)
    val tmpMount = V1VolumeMount().name("tmp").mountPath("/tmp").readOnly(false)

    val userContainer = V1Container()
        .name("user")
        .image(template.image)
        .resources(buildResourceRequirements(resources, json))
        .env(envVars)
        .securityContext(buildContainerSecurityContext(readOnlyRootFilesystem = false))
        .volumeMounts(listOf(workspaceMount, tmpMount))

    val agentContainer = V1Container()
        .name("runtime-agent")
        .image(agent.image)
        .env(listOf(
            V1EnvVar().name("EXKES_AGENT_WORKING_DIR").value(RUNTIME_WORKSPACE_PATH),
            V1EnvVar().name("EXKES_AGENT_STATE_DIR").value(RUNTIME_AGENT_STATE_PATH),
        ))
        .resources(V1ResourceRequirements()
            .requests(mapOf("cpu" to Quantity("100m"), "memory" to Quantity("64Mi")))
            .limits(mapOf("cpu" to Quantity("500m"), "memory" to Quantity("256Mi"))))
        .readinessProbe(V1Probe()
            .httpGet(V1HTTPGetAction().path("/health").port(IntOrString(RUNTIME_AGENT_PORT)))
            .periodSeconds(5))
        // The agent writes only to /tmp (JNI libraries, JVM) and its state directory.
        .securityContext(buildContainerSecurityContext(readOnlyRootFilesystem = true))
        .volumeMounts(listOf(
            workspaceMount,
            tmpMount,
            V1VolumeMount().name("agent-state").mountPath(RUNTIME_AGENT_STATE_PATH).readOnly(false),
        ))

    val volumes = listOf(
        V1Volume()
            .name("workspace")
            .persistentVolumeClaim(V1PersistentVolumeClaimVolumeSource().claimName(workspace.resolvePvcName())),
        V1Volume().name("tmp").emptyDir(V1EmptyDirVolumeSource()),
        V1Volume().name("agent-state").emptyDir(V1EmptyDirVolumeSource()),
    )

    return V1PodSpec()
        .containers(listOf(userContainer, agentContainer))
        .volumes(volumes)
        .imagePullSecrets(agent.imagePullSecret?.let { listOf(V1LocalObjectReference().name(it)) })
        .securityContext(V1PodSecurityContext()
            .runAsNonRoot(true)
            .runAsUser(RUNTIME_UID)
            .seccompProfile(V1SeccompProfile().type("RuntimeDefault")))
        .automountServiceAccountToken(false)
        .restartPolicy("Never")
        // client-java defaults this map to empty and serializes it as `overhead: {}`, which the
        // API server rejects unless a RuntimeClass defines an overhead.
        .overhead(null)
}

private fun buildContainerSecurityContext(readOnlyRootFilesystem: Boolean): V1SecurityContext =
    V1SecurityContext()
        .allowPrivilegeEscalation(false)
        .privileged(false)
        .readOnlyRootFilesystem(readOnlyRootFilesystem)
        .runAsNonRoot(true)
        .runAsUser(RUNTIME_UID)
        .capabilities(V1Capabilities().drop(listOf("ALL")))

private fun buildResourceRequirements(resourcesJson: String?, json: Json): V1ResourceRequirements {
    val reqs = V1ResourceRequirements()
    // Values may be JSON strings ("500m") or numbers (2); JsonPrimitive.content covers both.
    resourcesJson?.let { json.decodeFromString<Map<String, JsonPrimitive>>(it) }?.let { resources ->
        val requests = mutableMapOf<String, Quantity>()
        val limits = mutableMapOf<String, Quantity>()
        resources.forEach { entry ->
            val key = entry.key
            val qty = Quantity(entry.value.content)
            when (key.lowercase()) {
                "cpu", "memory", "ephemeral-storage" -> {
                    requests[key] = qty
                    limits[key] = qty
                }
            }
        }
        if (requests.isNotEmpty()) reqs.requests(requests)
        if (limits.isNotEmpty()) reqs.limits(limits)
    }
    return reqs
}

private fun mergeRuntimeEnv(templateJson: String?, overrideJson: String?, json: Json): List<V1EnvVar> {
    val merged = linkedMapOf<String, String>()
    templateJson?.let { json.decodeFromString<Map<String, String>>(it) }?.forEach { merged[it.key] = it.value }
    overrideJson?.let { json.decodeFromString<Map<String, String>>(it) }?.forEach { merged[it.key] = it.value }
    return merged.map { V1EnvVar().name(it.key).value(it.value) }
}

/**
 * True when [pod] carries the labels exkes sets on every Pod it creates for [runtimeId].
 * Used before adopting an existing Pod (409 on create, restart) so exkes never attaches
 * to a Pod it did not create for this runtime.
 */
internal fun isPodOwnedBy(pod: V1Pod, runtimeId: String): Boolean {
    val labels = pod.metadata?.labels ?: return false
    return labels["app.kubernetes.io/managed-by"] == "exkes" && labels["exkes.runtime-id"] == runtimeId
}

/**
 * Maps an observed Pod to a RuntimeStatus. A Running Pod is only RUNNING once its
 * Ready condition is True; until then it is still STARTING.
 */
internal fun mapPodToRuntimeStatus(pod: V1Pod): RuntimeStatus {
    val phase = pod.status?.phase?.uppercase() ?: return RuntimeStatus.FAILED
    return when (phase) {
        "PENDING" -> RuntimeStatus.STARTING
        "RUNNING" -> {
            val ready = pod.status?.conditions?.any { it.type == "Ready" && it.status == "True" } == true
            if (ready) RuntimeStatus.RUNNING else RuntimeStatus.STARTING
        }
        "SUCCEEDED" -> RuntimeStatus.STOPPED
        else -> RuntimeStatus.FAILED
    }
}
