package net.kigawa.exkes.common.domain

import kotlinx.serialization.json.Json

/**
 * Input spec for creating a Runtime. Carried from exkes-api to exkes-controller
 * via the `runtimes` table (plan chapter 7.2).
 */
data class RuntimeSpec(
    val templateId: String,
    val workspaceId: String,
    val resourcesJson: String?,   // Override template defaults
    val envJson: String?,         // Override template defaults
    val ttlSeconds: Long?,        // Override template default; null = no TTL
    val labelsJson: String?,      // Additional labels for the Pod
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun resourcesJson(resources: Map<String, Any>): String = json.encodeToString(resources)
        fun envJson(env: Map<String, String>): String = json.encodeToString(env)
        fun labelsJson(labels: Map<String, String>): String = json.encodeToString(labels)
    }
}

/**
 * Exception thrown by KubernetesRuntimeProvider when a Kubernetes API call fails
 * in a way that should be surfaced as a Runtime state transition (e.g. to FAILED).
 */
class KubernetesRuntimeException(
    message: String,
    cause: Throwable? = null,
    val isTransient: Boolean = true,  // true = retryable (network, 5xx), false = permanent (4xx, quota)
) : RuntimeException(message, cause)

/**
 * Exception thrown when the requested RuntimeTemplate is not found.
 */
class RuntimeTemplateNotFoundException(templateId: String) :
    RuntimeException("runtime template not found: $templateId")

/**
 * Exception thrown when the Workspace referenced by a Runtime does not exist
 * or is not in a state that allows attachment (e.g. DELETING/DELETED).
 */
class WorkspaceNotAttachableException(workspaceId: String, status: String) :
    RuntimeException("workspace $workspaceId is not attachable (status=$status)")