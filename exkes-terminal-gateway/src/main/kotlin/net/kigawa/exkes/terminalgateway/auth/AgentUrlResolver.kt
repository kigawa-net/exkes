package net.kigawa.exkes.terminalgateway.auth

import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.terminalgateway.config.TerminalGatewayConfig

/**
 * Derives the runtime-agent WebSocket URL of a Runtime from the template in
 * `exkes.terminalGateway.agentUrlTemplate`.
 *
 * The only per-Runtime input is `runtimes.pod_name`, which the controller derives from the
 * runtime id (`rt-<id>`). It is validated as a DNS-1123 label before substitution, so a
 * malformed DB value can never change the host part of the URL.
 *
 * Why not the Pod IP: the DB does not store it, and reading it would need the Kubernetes API,
 * which this process must not call (issue #2). The template therefore assumes a Service per
 * Runtime whose name equals the Pod name. See docs/terminal-gateway.md.
 */
class AgentUrlResolver(private val template: String) {
    init {
        require(template.contains(TerminalGatewayConfig.POD_NAME_PLACEHOLDER))
    }

    /** Returns null when the runtime has no usable pod name. */
    fun resolve(runtime: Runtime): String? {
        val podName = runtime.podName?.takeIf { DNS_LABEL.matches(it) } ?: return null
        return template.replace(TerminalGatewayConfig.POD_NAME_PLACEHOLDER, podName)
    }

    private companion object {
        val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
    }
}
