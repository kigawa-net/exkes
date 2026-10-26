package net.kigawa.exkes.controller.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuntimeAgentConfigTest {
    private fun config(image: String = "harbor.kigawa.net/private/exkes-runtime-agent:main-abc", secret: String = "harbor-registry") =
        MapApplicationConfig(
            "exkes.runtime.agentImage" to image,
            "exkes.runtime.imagePullSecret" to secret,
        )

    @Test
    fun `reads the agent image and the pull secret`() {
        val parsed = RuntimeAgentConfig.fromConfig(config())
        assertEquals("harbor.kigawa.net/private/exkes-runtime-agent:main-abc", parsed.image)
        assertEquals("harbor-registry", parsed.imagePullSecret)
    }

    @Test
    fun `empty pull secret means no imagePullSecrets`() {
        assertNull(RuntimeAgentConfig.fromConfig(config(secret = "  ")).imagePullSecret)
    }

    @Test
    fun `empty agent image fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeAgentConfig.fromConfig(config(image = " "))
        }
    }
}
