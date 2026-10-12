package net.kigawa.exkes.controller.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KubernetesConfigTest {
    private fun config(vararg extra: Pair<String, String>): MapApplicationConfig =
        MapApplicationConfig(
            "exkes.kubernetes.namespace" to "platform-exkes-stg",
            "exkes.kubernetes.useInCluster" to "true",
            "exkes.kubernetes.kubeConfigPath" to "",
            *extra,
        )

    @Test
    fun `legacy namespace targets both resource types until platform rollout`() {
        val parsed = KubernetesConfig.fromConfig(config())
        assertEquals("platform-exkes-stg", parsed.controlNamespace)
        assertEquals("platform-exkes-stg", parsed.resourceNamespace)
    }

    @Test
    fun `explicit resource namespace places Pod and PVC in sandbox`() {
        val parsed = KubernetesConfig.fromConfig(
            config(
                "exkes.kubernetes.controlNamespace" to "platform-exkes-stg",
                "exkes.kubernetes.resourceNamespace" to "platform-exkes-stg-sandbox",
            )
        )
        assertEquals("platform-exkes-stg", parsed.controlNamespace)
        assertEquals("platform-exkes-stg-sandbox", parsed.resourceNamespace)
    }

    @Test
    fun `partial sandbox configuration fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            KubernetesConfig.fromConfig(
                config("exkes.kubernetes.resourceNamespace" to "platform-exkes-stg-sandbox")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesConfig.fromConfig(
                config("exkes.kubernetes.controlNamespace" to "platform-exkes-stg")
            )
        }
    }

    @Test
    fun `explicit same namespace configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KubernetesConfig.fromConfig(
                config(
                    "exkes.kubernetes.controlNamespace" to "platform-exkes-stg",
                    "exkes.kubernetes.resourceNamespace" to "platform-exkes-stg",
                )
            )
        }
    }

    @Test
    fun `invalid namespace values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KubernetesConfig.fromConfig(
                config(
                    "exkes.kubernetes.controlNamespace" to "platform-exkes-stg",
                    "exkes.kubernetes.resourceNamespace" to "",
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesConfig.fromConfig(
                config(
                    "exkes.kubernetes.controlNamespace" to "platform-exkes-stg",
                    "exkes.kubernetes.resourceNamespace" to "Platform-EXKES",
                )
            )
        }
    }
}
