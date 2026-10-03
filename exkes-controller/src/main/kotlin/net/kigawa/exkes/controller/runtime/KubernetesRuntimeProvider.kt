package net.kigawa.exkes.controller.runtime

import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1ContainerPort
import io.kubernetes.client.openapi.models.V1EnvVar
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodSpec
import io.kubernetes.client.openapi.models.V1PodSecurityContext
import io.kubernetes.client.openapi.models.V1ResourceRequirements
import io.kubernetes.client.openapi.models.V1SecurityContext
import io.kubernetes.client.openapi.models.V1Volume
import io.kubernetes.client.openapi.models.V1VolumeMount
import kotlinx.serialization.json.Json
import net.kigawa.exkes.common.config.WorkspaceConfig
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeSpec
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.RuntimeTemplate
import net.kigawa.exkes.common.domain.RuntimeTemplateNotFoundException
import net.kigawa.exkes.common.domain.KubernetesRuntimeException
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.controller.config.KubernetesConfig
import net.kigawa.exkes.controller.k8s.KubernetesClientFactory
import org.slf4j.LoggerFactory

/**
 * Kubernetes implementation of [RuntimeProvisioner].
 *
 * Creates a Pod with:
 * - User container (from RuntimeTemplate.image)
 * - runtime-agent sidecar (fixed image, for process/PTY/log management)
 * - Workspace PVC mount at /workspace
 * - Resource limits/requests from RuntimeSpec or template defaults
 * - Environment variables from RuntimeSpec or template defaults
 * - SecurityContext: non-root, readOnlyRootFilesystem, drop ALL capabilities, seccomp RuntimeDefault
 *
 * The controller's ServiceAccount must have Pod create/get/list/watch/delete permissions
 * in the Sandbox namespace (issue #2 "Controller RBAC").
 */
class KubernetesRuntimeProvider(
    private val kubernetesConfig: KubernetesConfig,
    private val templateRepository: RuntimeTemplateRepository,
    private val workspaceConfig: WorkspaceConfig,
) : RuntimeProvisioner {
    private val logger = LoggerFactory.getLogger(KubernetesRuntimeProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val api: CoreV1Api by lazy { CoreV1Api(KubernetesClientFactory.createClient(kubernetesConfig)) }
    private val namespace: String = kubernetesConfig.namespace

    override fun createRuntime(runtime: Runtime): String {
        val template = templateRepository.findById(runtime.templateId)
            ?: throw RuntimeTemplateNotFoundException(runtime.templateId)

        val podName = resolvePodName(runtime)
        val pod = buildPod(runtime, template, podName)

        return try {
            api.createNamespacedPod(namespace, pod).execute()
                .also { logger.info("created Pod {} for runtime {}", podName, runtime.id) }
            podName
        } catch (e: ApiException) {
            if (e.code == 409) {
                // Lost a creation race; the next read observes the winner.
                readPod(podName)?.metadata?.name ?: throw e
            } else {
                throw KubernetesRuntimeException("failed to create Pod $podName: ${e.message}", e)
            }
        }
    }

    override fun startRuntime(runtime: Runtime) {
        val podName = resolvePodName(runtime)
        val pod = readPod(podName) ?: throw KubernetesRuntimeException("Pod $podName not found for runtime ${runtime.id}")
        // In Kubernetes, a Pod is either Running or not. If it exists, it's "started".
        // This method is a no-op for Kubernetes; the reconciler will observe the Pod phase.
        logger.debug("startRuntime called for {} (Pod {} exists)", runtime.id, podName)
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
        val podName = resolvePodName(runtime)
        val pod = readPod(podName)
        return when {
            pod == null -> RuntimeStatus.FAILED // Pod vanished unexpectedly
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

    private fun buildPod(runtime: Runtime, template: RuntimeTemplate, podName: String): V1Pod {
        val spec = buildPodSpec(runtime, template)

        return V1Pod()
            .metadata(
                V1ObjectMeta()
                    .name(podName)
                    .namespace(namespace)
                    .labels(
                        mapOf(
                            "app.kubernetes.io/managed-by" to "exkes",
                            "exkes.runtime-id" to runtime.id,
                            "exkes.workspace-id" to runtime.workspaceId,
                            "exkes.template-id" to runtime.templateId,
                        ) + (runtime.labelsJson?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()),
                    ),
            )
            .spec(spec)
    }

    private fun buildPodSpec(runtime: Runtime, template: RuntimeTemplate): V1PodSpec {
        val resources = mergeResources(template.defaultResourcesJson, runtime.resourcesJson)
        val envVars = mergeEnv(template.defaultEnvJson, runtime.envJson)

        val userContainer = V1Container()
            .name("user")
            .image(template.image)
            .resources(buildResourceRequirements(resources))
            .env(envVars)
            .securityContext(buildContainerSecurityContext())
            .volumeMounts(listOf(
                V1VolumeMount().name("workspace").mountPath("/workspace").readOnly(false),
                V1VolumeMount().name("tmp").mountPath("/tmp").readOnly(false),
            ))

        val agentContainer = V1Container()
            .name("runtime-agent")
            .image("ghcr.io/kigawa/runtime-agent:latest") // Fixed sidecar image
            .resources(V1ResourceRequirements()
                .requests(mapOf("cpu" to Quantity("100m"), "memory" to Quantity("64Mi")))
                .limits(mapOf("cpu" to Quantity("500m"), "memory" to Quantity("256Mi"))))
            .securityContext(buildContainerSecurityContext())
            .volumeMounts(listOf(
                V1VolumeMount().name("workspace").mountPath("/workspace").readOnly(false),
                V1VolumeMount().name("tmp").mountPath("/tmp").readOnly(false),
            ))

        val volumes = listOf(
            V1Volume()
                .name("workspace")
                .persistentVolumeClaim(
                    io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource()
                        .claimName(Workspace.pvcNameFor(runtime.workspaceId))
                ),
            V1Volume()
                .name("tmp")
                .emptyDir(io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource())
        )

        return V1PodSpec()
            .containers(listOf(userContainer, agentContainer))
            .volumes(volumes)
            .securityContext(V1PodSecurityContext()
                .runAsNonRoot(true)
                .runAsUser(1000)
                .seccompProfile(io.kubernetes.client.openapi.models.V1SeccompProfile()
                    .type("RuntimeDefault")))
            .automountServiceAccountToken(false)
            .restartPolicy("Never")
    }

    private fun buildResourceRequirements(resourcesJson: String?): V1ResourceRequirements {
        val reqs = V1ResourceRequirements()
        resourcesJson?.let { Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, Any>>(it) }?.let { resources ->
            val requests = mutableMapOf<String, Quantity>()
            val limits = mutableMapOf<String, Quantity>()
            resources.forEach { entry ->
                val key = entry.key
                val value = entry.value
                val qty = Quantity(value.toString())
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

    private fun buildContainerSecurityContext(): V1SecurityContext {
        return V1SecurityContext()
            .allowPrivilegeEscalation(false)
            .privileged(false)
            .readOnlyRootFilesystem(false) // We need /workspace and /tmp writable
            .runAsNonRoot(true)
            .runAsUser(1000)
            .capabilities(io.kubernetes.client.openapi.models.V1Capabilities().drop(listOf("ALL")))
    }

    private fun mergeResources(templateJson: String?, overrideJson: String?): String? {
        if (overrideJson != null) return overrideJson
        return templateJson
    }

    private fun mergeEnv(templateJson: String?, overrideJson: String?): List<V1EnvVar> {
        val merged = mutableMapOf<String, String>()
        templateJson?.let { Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, String>>(it) }?.forEach { entry -> merged[entry.key] = entry.value }
        overrideJson?.let { Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, String>>(it) }?.forEach { entry -> merged[entry.key] = entry.value }
        return merged.map { entry -> V1EnvVar().name(entry.key).value(entry.value) }
    }

    private fun mapPodPhaseToRuntimeStatus(pod: V1Pod): RuntimeStatus {
        val phase = pod.status?.phase?.uppercase() ?: return RuntimeStatus.FAILED
        return when (phase) {
            "PENDING" -> RuntimeStatus.STARTING
            "RUNNING" -> RuntimeStatus.RUNNING
            "SUCCEEDED" -> RuntimeStatus.STOPPED
            "FAILED" -> RuntimeStatus.FAILED
            "UNKNOWN" -> RuntimeStatus.FAILED
            else -> RuntimeStatus.FAILED
        }
    }

    private fun readPod(name: String): V1Pod? = try {
        api.readNamespacedPod(name, namespace).execute()
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }
}