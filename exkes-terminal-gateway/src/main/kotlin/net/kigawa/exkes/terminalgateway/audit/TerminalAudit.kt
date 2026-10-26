package net.kigawa.exkes.terminalgateway.audit

import net.kigawa.exkes.terminalgateway.auth.RejectReason
import net.kigawa.exkes.terminalgateway.relay.RelayEnd
import org.slf4j.LoggerFactory

/**
 * Audit trail of Terminal connections, written to the dedicated logger `TerminalGatewayAudit`.
 *
 * Only identifiers, reason codes, timings and byte counts are written. The token, the auth
 * frame, and every relayed payload (keystrokes and terminal output) are never passed to this
 * object, so they cannot reach the log through it. Callers must not pass them either.
 */
object TerminalAudit {
    private val logger = LoggerFactory.getLogger("TerminalGatewayAudit")

    fun accepted(sessionId: String, runtimeId: String) {
        logger.info("terminal connection accepted sessionId={} runtimeId={}", sessionId, runtimeId)
    }

    fun rejected(reason: RejectReason, runtimeId: String?, detail: String?) {
        logger.warn(
            "terminal connection rejected reason={} runtimeId={} detail={}",
            reason.code,
            runtimeId ?: "none",
            detail ?: "none",
        )
    }

    fun closed(
        sessionId: String,
        runtimeId: String,
        end: RelayEnd,
        durationMillis: Long,
        clientToAgentBytes: Long,
        agentToClientBytes: Long,
    ) {
        logger.info(
            "terminal connection closed sessionId={} runtimeId={} end={} durationMs={} clientToAgentBytes={} agentToClientBytes={}",
            sessionId,
            runtimeId,
            end.code,
            durationMillis,
            clientToAgentBytes,
            agentToClientBytes,
        )
    }

    /** An unexpected failure during admission. Only the exception class is logged. */
    fun admissionFailed(exceptionClass: String) {
        logger.error("terminal admission failed exception={}", exceptionClass)
    }
}
