package net.kigawa.exkes.controller.k8s

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import java.io.FileReader
import net.kigawa.exkes.controller.config.KubernetesConfig

/**
 * Builds the official client-java ApiClient.
 *
 * In-cluster (ServiceAccount token) for production, kubeconfig for local
 * development (same pattern as keruta K8sClientFactory).
 *
 * Lives in exkes-controller, not exkes-common: only this module holds
 * Kubernetes credentials in production, so client-java must not be on
 * exkes-api's classpath at all (issue #2 "Controller RBAC").
 */
object KubernetesClientFactory {
    fun createClient(config: KubernetesConfig): ApiClient {
        val client = if (config.useInCluster) {
            ClientBuilder.cluster().build()
        } else {
            val path = config.kubeConfigPath.ifBlank {
                "${System.getProperty("user.home")}/.kube/config"
            }
            ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
        }

        Configuration.setDefaultApiClient(client)
        return client
    }
}
