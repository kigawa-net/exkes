package net.kigawa.exkes.common.domain

import java.time.Duration
import java.time.Instant

/**
 * Admission guard shared by exkes-api (Executions, Terminal session issuance) and
 * exkes-terminal-gateway (Terminal connections). Lives in exkes-common so that the
 * gateway can apply the exact same rule without depending on exkes-api.
 *
 * The caller has already checked that the runtime is RUNNING. The DB status alone is not
 * enough: the controller may be down, or the Pod may have turned NotReady since the last
 * observation.
 */
object RuntimeAdmission {
    /**
     * Returns the reason the runtime must not admit new work, or null when it may.
     * Checks, in order: Workspace placement verified, a fresh Ready observation (not older
     * than [freshnessSeconds], not in the future), and the TTL.
     */
    fun denialReason(
        runtime: Runtime,
        workspace: Workspace?,
        now: Instant,
        freshnessSeconds: Long,
    ): String? {
        // A fresh Pod Ready timestamp does not prove that the Workspace PVC
        // is still attached/verified. This is an additional fail-closed guard;
        // atomic generation-fenced admission is tracked in #50/#52.
        if (workspace?.canAttachRuntime() != true) {
            return "Workspace PVC placement is not verified"
        }
        // Require a fresh Ready observation. Compare full Duration values: Duration.seconds
        // truncates, so 15.9999s would read as 15s and a stale observation could still be
        // admitted at the boundary.
        val readyAt = runtime.lastReadyObservedAt ?: return "no Ready observation"
        if (Duration.between(readyAt, now) > Duration.ofSeconds(freshnessSeconds)) {
            return "last Ready observation is stale"
        }
        if (readyAt.isAfter(now.plusSeconds(freshnessSeconds))) {
            return "Ready observation is in the future"
        }
        // Same TTL rule as the controller: a runtime past its TTL must not start new work
        // while the controller has not yet moved it to EXPIRED.
        val ttl = runtime.ttlSeconds
        if (ttl != null && ttl > 0 && Duration.between(runtime.createdAt, now) >= Duration.ofSeconds(ttl)) {
            return "TTL has expired"
        }
        return null
    }
}
