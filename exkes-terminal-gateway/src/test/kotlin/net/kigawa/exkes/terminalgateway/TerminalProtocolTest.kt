package net.kigawa.exkes.terminalgateway

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.terminalgateway.auth.AgentUrlResolver
import net.kigawa.exkes.terminalgateway.config.TerminalGatewayConfig
import net.kigawa.exkes.terminalgateway.protocol.AuthFrame

class TerminalProtocolTest {
    @Test
    fun `auth frame accepts token with optional size and ignores unknown fields`() {
        val parsed = AuthFrame.parse("""{"type":"auth","token":"abc","cols":120,"rows":40,"extra":true}""")
        assertEquals("abc", parsed!!.token)
        assertEquals(120, parsed.cols)
        assertEquals(40, parsed.rows)
    }

    @Test
    fun `auth frame without size leaves size unset`() {
        val parsed = AuthFrame.parse("""{"type":"auth","token":"abc"}""")!!
        assertNull(parsed.cols)
        assertNull(parsed.rows)
    }

    @Test
    fun `auth frame rejects wrong type, bad token, and out of range or quoted sizes`() {
        val invalid = listOf(
            """{"type":"input","token":"abc"}""",
            """{"type":"auth"}""",
            """{"type":"auth","token":""}""",
            """{"type":"auth","token":5}""",
            """{"type":"auth","token":"${"x".repeat(AuthFrame.MAX_TOKEN_LENGTH + 1)}"}""",
            """{"type":"auth","token":"abc","cols":0}""",
            """{"type":"auth","token":"abc","rows":1001}""",
            """{"type":"auth","token":"abc","cols":"80"}""",
            """["auth"]""",
        )
        invalid.forEach { assertNull(AuthFrame.parse(it), it) }
    }

    @Test
    fun `malformed json throws and the caller must not log its message`() {
        assertFailsWith<Exception> { AuthFrame.parse("""{"type":"auth","token":"abc" """) }
    }

    @Test
    fun `agent url substitutes a valid pod name and rejects anything else`() {
        val resolver = AgentUrlResolver("ws://{podName}.sandbox.svc.cluster.local:8080/terminal")
        assertEquals(
            "ws://rt-0a1b.sandbox.svc.cluster.local:8080/terminal",
            resolver.resolve(runtime(podName = "rt-0a1b")),
        )
        listOf(null, "", "RT-UPPER", "rt.with.dots", "rt/evil", "rt@host", "-leading").forEach { bad ->
            assertNull(resolver.resolve(runtime(podName = bad)), "pod name $bad")
        }
    }

    @Test
    fun `config rejects a template without the pod name placeholder or a non websocket scheme`() {
        val db = DatabaseConfig("jdbc:h2:mem:x", "sa", "")
        assertFailsWith<IllegalArgumentException> { gatewayConfig(db, template = "ws://fixed:8080/terminal") }
        assertFailsWith<IllegalArgumentException> { gatewayConfig(db, template = "http://{podName}:8080/terminal") }
    }

    @Test
    fun `config rejects non positive timeouts and out of range message size`() {
        val db = DatabaseConfig("jdbc:h2:mem:x", "sa", "")
        assertFailsWith<IllegalArgumentException> { gatewayConfig(db, idle = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { gatewayConfig(db, maxMessageBytes = 10) }
    }

    private fun runtime(podName: String?) = Runtime(
        id = "rt-id",
        workspaceId = "ws-id",
        templateId = "tpl",
        status = RuntimeStatus.RUNNING,
        resourcesJson = null,
        envJson = null,
        ttlSeconds = null,
        podName = podName,
        errorMessage = null,
        labelsJson = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun gatewayConfig(
        db: DatabaseConfig,
        template: String = "ws://{podName}:8080/terminal",
        idle: Duration = Duration.ofSeconds(60),
        maxMessageBytes: Int = 131072,
    ) = TerminalGatewayConfig(
        database = db,
        agentUrlTemplate = template,
        admissionFreshnessSeconds = 15,
        authTimeout = Duration.ofSeconds(10),
        idleTimeout = idle,
        maxLifetime = Duration.ofSeconds(60),
        maxMessageBytes = maxMessageBytes,
    )
}
