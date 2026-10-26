package net.kigawa.exkes.terminalgateway.config

import io.ktor.server.config.ApplicationConfig
import java.time.Duration
import net.kigawa.exkes.common.config.DatabaseConfig

/**
 * Settings of exkes-terminal-gateway, read from the `exkes` block of application.conf.
 * Every value validates itself at startup, so a bad override fails the process early.
 */
data class TerminalGatewayConfig(
    val database: DatabaseConfig,
    /** Template for the runtime-agent WebSocket URL; contains [POD_NAME_PLACEHOLDER]. */
    val agentUrlTemplate: String,
    /** Same meaning as exkes-api's `exkes.admission.freshnessSeconds`. */
    val admissionFreshnessSeconds: Long,
    val authTimeout: Duration,
    val idleTimeout: Duration,
    val maxLifetime: Duration,
    val maxMessageBytes: Int,
) {
    init {
        require(agentUrlTemplate.contains(POD_NAME_PLACEHOLDER)) {
            "agentUrlTemplate must contain $POD_NAME_PLACEHOLDER"
        }
        require(agentUrlTemplate.startsWith("ws://") || agentUrlTemplate.startsWith("wss://")) {
            "agentUrlTemplate must be a ws:// or wss:// URL"
        }
        require(admissionFreshnessSeconds > 0) { "admissionFreshnessSeconds must be positive" }
        require(!authTimeout.isNegative && !authTimeout.isZero) { "authTimeoutSeconds must be positive" }
        require(!idleTimeout.isNegative && !idleTimeout.isZero) { "idleTimeoutSeconds must be positive" }
        require(!maxLifetime.isNegative && !maxLifetime.isZero) { "maxLifetimeSeconds must be positive" }
        require(maxMessageBytes in MIN_MESSAGE_BYTES..MAX_MESSAGE_BYTES) {
            "maxMessageBytes must be between $MIN_MESSAGE_BYTES and $MAX_MESSAGE_BYTES"
        }
    }

    companion object {
        const val POD_NAME_PLACEHOLDER = "{podName}"
        const val MIN_MESSAGE_BYTES = 1024
        const val MAX_MESSAGE_BYTES = 1024 * 1024

        fun fromConfig(config: ApplicationConfig): TerminalGatewayConfig = TerminalGatewayConfig(
            database = DatabaseConfig.fromConfig(config),
            agentUrlTemplate = config.property("exkes.terminalGateway.agentUrlTemplate").getString(),
            admissionFreshnessSeconds = config.property("exkes.admission.freshnessSeconds").getString().toLong(),
            authTimeout = Duration.ofSeconds(
                config.property("exkes.terminalGateway.authTimeoutSeconds").getString().toLong(),
            ),
            idleTimeout = Duration.ofSeconds(
                config.property("exkes.terminalGateway.idleTimeoutSeconds").getString().toLong(),
            ),
            maxLifetime = Duration.ofSeconds(
                config.property("exkes.terminalGateway.maxLifetimeSeconds").getString().toLong(),
            ),
            maxMessageBytes = config.property("exkes.terminalGateway.maxMessageBytes").getString().toInt(),
        )
    }
}
