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
    val namespace: String,
    val useInCluster: Boolean,
    val kubeConfigPath: String,
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): KubernetesConfig = KubernetesConfig(
            namespace = config.property("exkes.kubernetes.namespace").getString(),
            useInCluster = config.property("exkes.kubernetes.useInCluster").getString().toBoolean(),
            kubeConfigPath = config.property("exkes.kubernetes.kubeConfigPath").getString(),
        )
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
 */
data class ReconcilerConfig(
    val intervalSeconds: Long,
    val maxAttempts: Int,
    val creatingTimeoutSeconds: Long,
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): ReconcilerConfig = ReconcilerConfig(
            intervalSeconds = config.property("exkes.reconciler.intervalSeconds").getString().toLong(),
            maxAttempts = config.property("exkes.reconciler.maxAttempts").getString().toInt(),
            creatingTimeoutSeconds = config.property("exkes.reconciler.creatingTimeoutSeconds").getString().toLong(),
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
