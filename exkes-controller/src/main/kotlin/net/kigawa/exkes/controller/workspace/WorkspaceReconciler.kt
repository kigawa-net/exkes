package net.kigawa.exkes.controller.workspace

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspaceStatus
import org.slf4j.LoggerFactory

/**
 * Polls workspaces in CREATING/DELETING state and drives them forward:
 *
 * - CREATING: ensure PVC exists -> BOUND => READY. Failures retry up to
 *   maxAttempts, and a PVC that stays not-Bound for longer than
 *   `creatingTimeoutSeconds` (measured from `created_at`) => ERROR.
 * - DELETING: delete PVC -> gone => DELETED
 *
 * This loop runs in exkes-controller, the only Control Plane process that holds
 * Kubernetes credentials: exkes-api writes desired state to MariaDB and nothing
 * else (issue #2 "Controller RBAC").
 *
 * Concurrency: the loop assumes a single active instance. platform currently
 * deploys it with `replicas: 1`, which is fine because every DB write is a
 * conditional UPDATE (`WHERE id = ? AND status = ?`) and the PVC API is
 * idempotent, but two active replicas would double the reconcile rate and the
 * in-memory attempt counters. A lease-based leader election has not been
 * discussed yet; it is the follow-up once replicas > 1 becomes possible.
 */
class WorkspaceReconciler(
    private val repository: WorkspaceRepository,
    private val provisioner: WorkspaceProvisioner,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val creatingTimeoutSeconds: Long = DEFAULT_CREATING_TIMEOUT_SECONDS,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(WorkspaceReconciler::class.java)

    // In-memory failure counters; a restart resets them (acceptable for now,
    // no schema column exists for attempt counting yet).
    private val attempts = ConcurrentHashMap<String, Int>()
    private var executor: ScheduledExecutorService? = null

    /** Runs one reconcile cycle synchronously (also used directly by tests). */
    fun reconcileOnce() {
        val creating = repository.findByStatus(WorkspaceStatus.CREATING)
        val deleting = repository.findByStatus(WorkspaceStatus.DELETING)
        processCreating(creating)
        processDeleting(deleting)

        // Drop counters of workspaces that are no longer being provisioned (e.g. a
        // workspace that was DELETED while its provisioning was still failing,
        // or a DELETING row whose PVC removal never gets confirmed). [attempts]
        // is only ever written for CREATING rows, so a counter whose workspace
        // left that set is dead weight; without this the map grows for the
        // lifetime of the process.
        val provisioning = creating.mapTo(HashSet(creating.size)) { it.id }
        attempts.keys.retainAll(provisioning)
    }

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "workspace-reconciler").apply { isDaemon = true }
        }.also { scheduled ->
            scheduled.scheduleWithFixedDelay(
                {
                    try {
                        reconcileOnce()
                    } catch (e: Exception) {
                        logger.error("reconcile cycle failed", e)
                    }
                },
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS,
            )
        }
        logger.info(
            "workspace reconciler started (interval={}s, maxAttempts={}, creatingTimeout={}s)",
            intervalSeconds,
            maxAttempts,
            creatingTimeoutSeconds,
        )
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /**
     * Number of workspaces with a pending provisioning-failure counter.
     * Internal: used by tests only, to prove the map does not leak.
     */
    internal fun trackedAttempts(): Int = attempts.size

    private fun processCreating(workspaces: List<Workspace>) {
        for (workspace in workspaces) {
            if (isCreatingTimedOut(workspace)) {
                moveToErrorForTimeout(workspace)
                continue
            }
            try {
                val outcome = provisioner.ensurePvc(workspace)
                if (workspace.pvcName == null && !repository.setPvcName(workspace.id, outcome.pvcName)) {
                    // The workspace moved out of CREATING between the read and
                    // the write; nothing to record.
                    logger.info(
                        "skipped pvc_name update for workspace {} (no longer CREATING)",
                        workspace.id,
                    )
                }
                if (outcome.state == PvcState.BOUND) {
                    if (repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY)) {
                        attempts.remove(workspace.id)
                        logger.info("workspace {} is READY (PVC {})", workspace.id, outcome.pvcName)
                    }
                }
            } catch (e: Exception) {
                handleCreatingFailure(workspace, e)
            }
        }
    }

    /**
     * A PVC that is accepted but never Bound (missing CSI driver, quota, wrong
     * storage class) raises no exception, so the attempt counter never grows and
     * the row would sit in CREATING forever. The age is measured from
     * `created_at` rather than from a process-local timer so a controller
     * restart does not hand the workspace a fresh grace period.
     */
    private fun isCreatingTimedOut(workspace: Workspace): Boolean {
        if (creatingTimeoutSeconds <= 0) return false
        val ageSeconds = Duration.between(workspace.createdAt, clock()).seconds
        return ageSeconds >= creatingTimeoutSeconds
    }

    private fun moveToErrorForTimeout(workspace: Workspace) {
        val message = "PVC ${workspace.resolvePvcName()} was not bound within " +
            "${creatingTimeoutSeconds}s (workspace age ${Duration.between(workspace.createdAt, clock()).seconds}s)"
        if (repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.ERROR, message)) {
            attempts.remove(workspace.id)
            logger.error("workspace {} moved to ERROR: {}", workspace.id, message)
        }
    }

    private fun handleCreatingFailure(workspace: Workspace, e: Exception) {
        val count = attempts.merge(workspace.id, 1) { old, one -> old + one } ?: 1
        logger.warn(
            "provisioning attempt {}/{} failed for workspace {}: {}",
            count,
            maxAttempts,
            workspace.id,
            e.message,
        )
        if (count >= maxAttempts) {
            val moved = repository.transition(
                workspace.id,
                WorkspaceStatus.CREATING,
                WorkspaceStatus.ERROR,
                errorMessage = e.message ?: e.toString(),
            )
            if (moved) {
                attempts.remove(workspace.id)
                logger.error("workspace {} moved to ERROR after {} attempts", workspace.id, count)
            }
        }
    }

    private fun processDeleting(workspaces: List<Workspace>) {
        for (workspace in workspaces) {
            try {
                // Two-phase protocol: the first call requests the deletion and
                // answers false; only a later cycle observes the PVC gone (404)
                // and answers true.
                if (provisioner.deletePvc(workspace)) {
                    if (repository.transition(workspace.id, WorkspaceStatus.DELETING, WorkspaceStatus.DELETED)) {
                        attempts.remove(workspace.id)
                        logger.info("workspace {} is DELETED", workspace.id)
                    }
                }
            } catch (e: Exception) {
                // DELETING has no terminal error state; retry on the next cycle.
                logger.error("failed to delete PVC of workspace {}, will retry", workspace.id, e)
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** 5 minutes; long enough for a cold CephFS volume, short enough to surface a stuck row. */
        const val DEFAULT_CREATING_TIMEOUT_SECONDS = 300L
    }
}
