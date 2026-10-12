package net.kigawa.exkes.controller.config

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig

/**
 * `exkes.kubernetes.*` section of application.conf.
 *
 * It lives in exkes-controller rather than exkes-common because exkes-controller
 * is the only process allowed to call the Kubernetes API: platform grants the
 * Runtime/PVC permissions to the controller's ServiceAccount only and runs
 * exkes-api with `automountServiceAccountToken: false` (issue #2).
 *
 * Readable via `EXKES_KUBERNETES_*` environment variables (plan chapter 4).
 */
data class KubernetesConfig(
    /** Namespace hosting the controller ServiceAccount and control-plane Deployments. */
    val controlNamespace: String,
    /** Namespace containing BOTH Workspace PVCs and Runtime Pods. */
    val resourceNamespace: String,
    val useInCluster: Boolean,
    val kubeConfigPath: String,
) {
    companion object {
        private val NAMESPACE_PATTERN = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")

        fun fromConfig(config: ApplicationConfig): KubernetesConfig {
            // Compatibility for the first rollout: old platform manifests only set
            // EXKES_KUBERNETES_NAMESPACE. They continue to target the same namespace
            // until BOTH explicit values are supplied by the platform rollout.
            val legacy = config.propertyOrNull("exkes.kubernetes.namespace")?.getString()
            val control = config.propertyOrNull("exkes.kubernetes.controlNamespace")?.getString()
            val resource = config.propertyOrNull("exkes.kubernetes.resourceNamespace")?.getString()
            require((control == null) == (resource == null)) {
                "controlNamespace and resourceNamespace must be configured together"
            }
            val controlNamespace = (control ?: legacy).orEmpty().trim()
            val resourceNamespace = (resource ?: legacy).orEmpty().trim()
            for (namespace in listOf(controlNamespace, resourceNamespace)) {
                require(namespace.length in 1..63 && NAMESPACE_PATTERN.matches(namespace)) {
                    "invalid Kubernetes namespace: $namespace"
                }
            }
            if (control != null) {
                require(controlNamespace != resourceNamespace) {
                    "controlNamespace and resourceNamespace must be different in sandbox mode"
                }
            }
            return KubernetesConfig(
                controlNamespace = controlNamespace,
                resourceNamespace = resourceNamespace,
                useInCluster = config.property("exkes.kubernetes.useInCluster").getString().toBoolean(),
                kubeConfigPath = config.property("exkes.kubernetes.kubeConfigPath").getString(),
            )
        }
    }
}

/**
 * `exkes.reconciler.*` section of application.conf.
 *
 * @property creatingTimeoutSeconds a workspace must not stay in CREATING
 *   forever: a PVC that is accepted but never Bound (no Ceph CSI provisioner,
 *   quota exhausted, unreachable storage class) raises no exception, so the
 *   attempt counter alone would keep the row in CREATING for good. Measured from
 *   `workspaces.created_at`, so a controller restart does not reset the budget.
 *   `0` disables the timeout.
 * @property notReadyGraceSeconds how long a RUNNING runtime's Pod may stay NotReady
 *   (Ready=False) before the runtime moves to DEGRADED. Shorter blips keep it RUNNING.
 */
data class ReconcilerConfig(
    val intervalSeconds: Long,
    val maxAttempts: Int,
    val creatingTimeoutSeconds: Long,
    val notReadyGraceSeconds: Long,
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): ReconcilerConfig = ReconcilerConfig(
            intervalSeconds = config.property("exkes.reconciler.intervalSeconds").getString().toLong(),
            maxAttempts = config.property("exkes.reconciler.maxAttempts").getString().toInt(),
            creatingTimeoutSeconds = config.property("exkes.reconciler.creatingTimeoutSeconds").getString().toLong(),
            notReadyGraceSeconds = config.property("exkes.reconciler.notReadyGraceSeconds").getString().toLong(),
        )
    }
}

/** Everything exkes-controller reads out of application.conf. */
data class ControllerConfig(
    val kubernetes: KubernetesConfig,
    val reconciler: ReconcilerConfig,
) {
    companion object {
        fun fromApplication(application: Application): ControllerConfig =
            fromConfig(application.environment.config)

        fun fromConfig(config: ApplicationConfig): ControllerConfig = ControllerConfig(
            kubernetes = KubernetesConfig.fromConfig(config),
            reconciler = ReconcilerConfig.fromConfig(config),
        )
    }
}
