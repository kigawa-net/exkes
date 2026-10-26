package net.kigawa.exkes.terminalgateway.auth

import java.time.Instant
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.TerminalSessionRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.domain.RuntimeAdmission
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.TerminalSession

/**
 * Why a Terminal connection was refused. [code] goes to the audit log; the client only ever
 * receives one generic message, so the reason cannot be used to probe tokens or runtimes.
 */
enum class RejectReason(val code: String) {
    AUTH_TIMEOUT("auth_timeout"),
    MALFORMED_AUTH("malformed_auth"),
    CLIENT_CLOSED_BEFORE_AUTH("client_closed_before_auth"),
    /** Unknown, already consumed, or expired. Deliberately not split further. */
    TOKEN_REJECTED("token_rejected"),
    RUNTIME_NOT_FOUND("runtime_not_found"),
    RUNTIME_NOT_RUNNING("runtime_not_running"),
    RUNTIME_NOT_ADMITTING("runtime_not_admitting"),
    NO_AGENT_ADDRESS("no_agent_address"),
}

sealed interface AdmissionResult {
    /** The session is CONSUMED and the runtime passed the admission rule. */
    class Admitted(
        val session: TerminalSession,
        val agentUrl: String,
    ) : AdmissionResult

    /** [detail] is an internal explanation for the audit log only; never sent to the client. */
    class Rejected(
        val reason: RejectReason,
        val runtimeId: String? = null,
        val detail: String? = null,
    ) : AdmissionResult
}

/**
 * Turns a presented token into an admitted connection (plan chapter 5 / issue #1 "Session
 * validation" and "Runtime validation").
 *
 * Order matters:
 * 1. [TerminalSessionRepository.consume] is the only place a token is checked. It is a
 *    single conditional UPDATE, so the token is spent even if the runtime check below fails
 *    (fail closed; the client must request a new session).
 * 2. The Runtime is re-read and must be RUNNING, then pass [RuntimeAdmission] (placement,
 *    fresh Ready observation, TTL). Issuance checked the same rule, but the runtime may have
 *    changed since.
 * 3. The agent URL is derived from the runtime's own `pod_name`. Nothing from the client
 *    chooses the target.
 *
 * [admit] blocks on JDBC; callers run it on Dispatchers.IO.
 */
class TerminalAuthorizer(
    private val terminalSessions: TerminalSessionRepository,
    private val runtimes: RuntimeRepository,
    private val workspaces: WorkspaceRepository,
    private val agentUrls: AgentUrlResolver,
    private val admissionFreshnessSeconds: Long,
    private val clock: () -> Instant = Instant::now,
) {
    fun admit(token: String): AdmissionResult {
        val now = clock()
        val session = terminalSessions.consume(token, now)
            ?: return AdmissionResult.Rejected(RejectReason.TOKEN_REJECTED)
        val runtimeId = session.runtimeId

        val runtime = runtimes.findById(runtimeId)
        if (runtime == null || runtime.status == RuntimeStatus.DELETED) {
            return AdmissionResult.Rejected(RejectReason.RUNTIME_NOT_FOUND, runtimeId)
        }
        if (runtime.status != RuntimeStatus.RUNNING) {
            return AdmissionResult.Rejected(RejectReason.RUNTIME_NOT_RUNNING, runtimeId, runtime.status.name)
        }
        val denial = RuntimeAdmission.denialReason(
            runtime = runtime,
            workspace = workspaces.findById(runtime.workspaceId),
            now = now,
            freshnessSeconds = admissionFreshnessSeconds,
        )
        if (denial != null) {
            return AdmissionResult.Rejected(RejectReason.RUNTIME_NOT_ADMITTING, runtimeId, denial)
        }
        val agentUrl = agentUrls.resolve(runtime)
            ?: return AdmissionResult.Rejected(RejectReason.NO_AGENT_ADDRESS, runtimeId)
        return AdmissionResult.Admitted(session, agentUrl)
    }
}
