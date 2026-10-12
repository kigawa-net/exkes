package net.kigawa.exkes.controller.runtime

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import org.slf4j.LoggerFactory

/**
 * Polls runtimes in non-terminal states and drives them forward:
 *
 * - CREATING: create Pod -> STARTING
 * - STARTING: wait for Pod Running -> RUNNING
 * - RUNNING: monitor for Pod failure/TTL expiry -> FAILED/EXPIRED;
 *   Pod NotReady for longer than [notReadyGraceSeconds] -> DEGRADED
 * - DEGRADED: Pod Ready again -> RUNNING; Pod loss -> FAILED; TTL -> EXPIRED
 * - STOPPING: delete Pod -> STOPPED
 * - STOPPED: (idle, can be restarted via STARTING)
 * - FAILED: (terminal, can only go to DELETING)
 * - EXPIRED: (TTL expiry, transitions to DELETING)
 * - DELETING: delete Pod -> DELETED
 *
 * This loop runs in exkes-controller, the only Control Plane process that holds
 * Kubernetes credentials. exkes-api writes desired state to MariaDB and nothing else.
 *
 * Concurrency: the loop assumes a single active instance (platform deploys replicas: 1).
 * Every DB write is a conditional UPDATE (`WHERE id = ? AND status = <observed>`), so
 * a state change made by exkes-api (or a second controller) between the snapshot read
 * and the write makes the write a no-op instead of overwriting it. Pod operations are
 * idempotent: an existing Pod is reused, and a Pod that is not owned by the runtime is
 * never adopted.
 */
class RuntimeReconciler(
    private val runtimeRepository: RuntimeRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val provisioner: RuntimeProvisioner,
    private val resourceNamespace: String,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val notReadyGraceSeconds: Long = DEFAULT_NOT_READY_GRACE_SECONDS,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(RuntimeReconciler::class.java)

    // In-memory failure counters; a restart resets them.
    private val attempts = ConcurrentHashMap<String, Int>()
    // When a RUNNING/DEGRADED runtime's Pod was first seen NotReady. Reset on restart,
    // so a controller restart grants a fresh grace period (never a shorter one).
    private val notReadySince = ConcurrentHashMap<String, Instant>()
    private var executor: ScheduledExecutorService? = null

    /** Runs one reconcile cycle synchronously (also used directly by tests). */
    fun reconcileOnce() {
        val creating = runtimeRepository.findByStatus(RuntimeStatus.CREATING)
        val starting = runtimeRepository.findByStatus(RuntimeStatus.STARTING)
        val running = runtimeRepository.findByStatus(RuntimeStatus.RUNNING)
        val degraded = runtimeRepository.findByStatus(RuntimeStatus.DEGRADED)
        val stopping = runtimeRepository.findByStatus(RuntimeStatus.STOPPING)
        val expired = runtimeRepository.findByStatus(RuntimeStatus.EXPIRED)
        val stopped = runtimeRepository.findByStatus(RuntimeStatus.STOPPED)
        val deleting = runtimeRepository.findByStatus(RuntimeStatus.DELETING)

        processCreating(creating)
        processStarting(starting)
        processRunning(running)
        processDegraded(degraded)
        processStopping(stopping)
        processStopped(stopped)
        processExpired(expired)
        processDeleting(deleting)

        // Drop counters of runtimes that are no longer being processed
        val processing = (creating + starting + running + degraded + stopping + expired + deleting)
            .mapTo(mutableSetOf<String>()) { it.id }
        attempts.keys.retainAll(processing)
        notReadySince.keys.retainAll(processing)
    }

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "runtime-reconciler").apply { isDaemon = true }
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
            "runtime reconciler started (interval={}s, maxAttempts={})",
            intervalSeconds,
            maxAttempts,
        )
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /** Number of runtimes with a pending failure counter. Internal: used by tests only. */
    internal fun trackedAttempts(): Int = attempts.size

    /** Number of runtimes whose Pod is currently observed NotReady. Internal: used by tests only. */
    internal fun trackedNotReady(): Int = notReadySince.size

    private fun processCreating(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            // Verify workspace exists and is attachable
            val workspace = workspaceRepository.findById(runtime.workspaceId)
            if (workspace == null || !workspace.canAttachRuntime() || workspace.pvcNamespace != resourceNamespace) {
                val message = "workspace ${runtime.workspaceId} not attachable (${workspace?.status})"
                if (runtimeRepository.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.FAILED, message)) {
                    logger.error("runtime {} moved to FAILED: {}", runtime.id, message)
                }
                continue
            }

            try {
                val podName = provisioner.createRuntime(runtime)
                if (!runtimeRepository.setPodName(runtime.id, podName)) {
                    logger.info("skipped pod_name update for runtime {} (no longer CREATING)", runtime.id)
                }
                if (runtimeRepository.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING)) {
                    attempts.remove(runtime.id)
                    logger.info("runtime {} is STARTING (Pod {})", runtime.id, podName)
                }
            } catch (e: Exception) {
                handleProvisioningFailure(runtime, RuntimeStatus.CREATING, e)
            }
        }
    }

    private fun processStarting(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            // Restart (STOPPED -> STARTING) re-attaches the retained Workspace, so the
            // same attachability check as CREATING applies.
            val workspace = workspaceRepository.findById(runtime.workspaceId)
            if (workspace == null || !workspace.canAttachRuntime() || workspace.pvcNamespace != resourceNamespace) {
                val message = "workspace ${runtime.workspaceId} not attachable (${workspace?.status})"
                if (runtimeRepository.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.FAILED, message)) {
                    attempts.remove(runtime.id)
                    logger.error("runtime {} moved to FAILED: {}", runtime.id, message)
                }
                continue
            }

            try {
                // Recreates the Pod if it is gone (restart after stop); no-op otherwise.
                provisioner.startRuntime(runtime)
                val status = provisioner.getRuntimeStatus(runtime)
                when (status) {
                    RuntimeStatus.RUNNING -> {
                        if (runtimeRepository.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING)) {
                            attempts.remove(runtime.id)
                            recordReady(runtime)
                            logger.info("runtime {} is RUNNING", runtime.id)
                        }
                    }
                    RuntimeStatus.FAILED -> {
                        val message = "Pod failed during startup"
                        if (runtimeRepository.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.FAILED, message)) {
                            attempts.remove(runtime.id)
                            logger.error("runtime {} moved to FAILED: {}", runtime.id, message)
                        }
                    }
                    RuntimeStatus.STARTING -> {
                        // Still starting, wait for next cycle
                    }
                    else -> {
                        // Pod in unexpected state
                        val message = "Pod in unexpected state: $status"
                        if (runtimeRepository.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.FAILED, message)) {
                            attempts.remove(runtime.id)
                            logger.error("runtime {} moved to FAILED: {}", runtime.id, message)
                        }
                    }
                }
            } catch (e: Exception) {
                handleProvisioningFailure(runtime, RuntimeStatus.STARTING, e)
            }
        }
    }

    private fun processRunning(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            // Check TTL expiry
            if (isExpired(runtime)) {
                val message = "TTL expired (${runtime.ttlSeconds}s)"
                if (runtimeRepository.transition(runtime.id, RuntimeStatus.RUNNING, RuntimeStatus.EXPIRED, message)) {
                    attempts.remove(runtime.id)
                    logger.info("runtime {} moved to EXPIRED: {}", runtime.id, message)
                }
                continue
            }

            if (!isWorkspacePlacementValid(runtime)) {
                // Keep TTL/lifecycle cleanup independent of Workspace admission.
                // The old process may still be running: do not fake termination.
                clearReady(runtime)
                notReadySince.remove(runtime.id)
                logger.warn("closed Runtime {} admission: Workspace placement is not verified", runtime.id)
                continue
            }

            try {
                val observed = provisioner.getRuntimeStatus(runtime)
                when (observed) {
                    RuntimeStatus.RUNNING -> {
                        notReadySince.remove(runtime.id)
                        recordReady(runtime)
                    }
                    // Pod exists but Ready=False (mapPodToRuntimeStatus maps it to STARTING).
                    RuntimeStatus.STARTING -> observeNotReady(runtime)
                    RuntimeStatus.FAILED, RuntimeStatus.STOPPED ->
                        failObservedPod(runtime, RuntimeStatus.RUNNING, observed)
                    else -> Unit
                }
            } catch (e: Exception) {
                logger.warn("failed to check status for runtime {}: {}", runtime.id, e.message)
            }
        }
    }

    /**
     * DEGRADED: a runtime whose Pod was NotReady past the grace period. Ready=True again
     * returns it to RUNNING, so the Pod's readiness is observed continuously rather
     * than only at the first STARTING -> RUNNING transition.
     */
    private fun processDegraded(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            if (isExpired(runtime)) {
                val message = "TTL expired (${runtime.ttlSeconds}s)"
                if (runtimeRepository.transition(runtime.id, RuntimeStatus.DEGRADED, RuntimeStatus.EXPIRED, message)) {
                    attempts.remove(runtime.id)
                    notReadySince.remove(runtime.id)
                    logger.info("runtime {} moved to EXPIRED: {}", runtime.id, message)
                }
                continue
            }

            if (!isWorkspacePlacementValid(runtime)) {
                clearReady(runtime)
                notReadySince.remove(runtime.id)
                logger.warn("Runtime {} remains DEGRADED: Workspace placement is not verified", runtime.id)
                continue
            }

            try {
                val observed = provisioner.getRuntimeStatus(runtime)
                when (observed) {
                    RuntimeStatus.RUNNING -> {
                        if (runtimeRepository.transition(runtime.id, RuntimeStatus.DEGRADED, RuntimeStatus.RUNNING)) {
                            notReadySince.remove(runtime.id)
                            recordReady(runtime)
                            logger.info("runtime {} recovered to RUNNING (Pod Ready again)", runtime.id)
                        }
                    }
                    RuntimeStatus.FAILED, RuntimeStatus.STOPPED ->
                        failObservedPod(runtime, RuntimeStatus.DEGRADED, observed)
                    // still NotReady: stay DEGRADED; admission stays closed
                    RuntimeStatus.STARTING -> clearReady(runtime)
                    else -> Unit
                }
            } catch (e: Exception) {
                logger.warn("failed to check status for runtime {}: {}", runtime.id, e.message)
            }
        }
    }

    /**
     * Records how long the Pod has been NotReady and moves RUNNING to DEGRADED once
     * [notReadyGraceSeconds] has elapsed. Brief readiness blips inside the grace period
     * leave the runtime RUNNING.
     */
    private fun observeNotReady(runtime: Runtime) {
        // Admission closes on the first NotReady observation, regardless of the grace period.
        clearReady(runtime)
        val now = clock()
        val since = notReadySince.getOrPut(runtime.id) { now }
        val notReadyFor = Duration.between(since, now).seconds
        if (notReadyFor < notReadyGraceSeconds) return

        val message = "Pod not Ready for ${notReadyFor}s"
        if (runtimeRepository.transition(runtime.id, RuntimeStatus.RUNNING, RuntimeStatus.DEGRADED)) {
            logger.warn("runtime {} moved to DEGRADED: {}", runtime.id, message)
        }
    }

    /**
     * Records a Pod Ready=True observation. exkes-api admits new Executions only while the
     * runtime is RUNNING and this observation is fresh (see RuntimeService.createExecution).
     */
    private fun recordReady(runtime: Runtime) {
        // Re-evaluate after observing Pod status: otherwise a concurrent
        // Workspace DELETE can leave a newly refreshed Ready timestamp.
        if (!isWorkspacePlacementValid(runtime)) {
            clearReady(runtime)
            return
        }
        if (!runtimeRepository.recordReadyObserved(runtime.id, clock())) {
            logger.debug("skipped ready observation for runtime {} (no longer RUNNING/DEGRADED)", runtime.id)
        }
    }

    /** Read-only guard. Generation binding / transactional Ready CAS is #50/#52. */
    private fun isWorkspacePlacementValid(runtime: Runtime): Boolean {
        val workspace = workspaceRepository.findById(runtime.workspaceId)
        return workspace?.canAttachRuntime() == true && workspace.pvcNamespace == resourceNamespace
    }

    /** Closes admission: clears the Ready observation so no stale RUNNING row is admitted. */
    private fun clearReady(runtime: Runtime) {
        runtimeRepository.clearReadyObservation(runtime.id)
    }

    /**
     * Pod is gone ([observed] FAILED, 404) or terminated ([observed] STOPPED, Succeeded)
     * while the runtime expects it to run: FAILED, conditional on [from].
     */
    private fun failObservedPod(runtime: Runtime, from: RuntimeStatus, observed: RuntimeStatus) {
        val state = from.name.lowercase()
        val message = if (observed == RuntimeStatus.FAILED) "Pod failed or disappeared while $state"
        else "Pod terminated while $state"
        if (runtimeRepository.transition(runtime.id, from, RuntimeStatus.FAILED, message)) {
            attempts.remove(runtime.id)
            notReadySince.remove(runtime.id)
            logger.error("runtime {} moved to FAILED: {}", runtime.id, message)
        }
    }

    private fun processStopping(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            try {
                if (provisioner.stopRuntime(runtime)) {
                    if (runtimeRepository.transition(runtime.id, RuntimeStatus.STOPPING, RuntimeStatus.STOPPED)) {
                        attempts.remove(runtime.id)
                        logger.info("runtime {} is STOPPED", runtime.id)
                    }
                }
            } catch (e: Exception) {
                logger.error("failed to stop runtime {}, will retry", runtime.id, e)
            }
        }
    }

    /** TTL cleanup for idle runtimes: a STOPPED runtime past its TTL is deleted (Workspace is kept). */
    private fun processStopped(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            if (!isExpired(runtime)) continue
            if (runtimeRepository.transition(runtime.id, RuntimeStatus.STOPPED, RuntimeStatus.DELETING)) {
                logger.info("runtime {} moved to DELETING (TTL expired while STOPPED)", runtime.id)
            }
        }
    }

    private fun processExpired(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            // EXPIRED should transition to DELETING (cleanup)
            if (runtimeRepository.transition(runtime.id, RuntimeStatus.EXPIRED, RuntimeStatus.DELETING)) {
                attempts.remove(runtime.id)
                logger.info("runtime {} moved to DELETING after EXPIRED", runtime.id)
            }
        }
    }

    private fun processDeleting(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            try {
                if (provisioner.deleteRuntime(runtime)) {
                    if (runtimeRepository.transition(runtime.id, RuntimeStatus.DELETING, RuntimeStatus.DELETED)) {
                        attempts.remove(runtime.id)
                        logger.info("runtime {} is DELETED", runtime.id)
                    }
                }
            } catch (e: Exception) {
                logger.error("failed to delete runtime {}, will retry", runtime.id, e)
            }
        }
    }

    private fun isExpired(runtime: Runtime): Boolean {
        val ttl = runtime.ttlSeconds ?: return false
        if (ttl <= 0) return false
        val ageSeconds = Duration.between(runtime.createdAt, clock()).seconds
        return ageSeconds >= ttl
    }

    /**
     * Counts a failed Pod create/start for a runtime in [from] (CREATING or STARTING).
     * The FAILED transition is conditional on [from]: a restart (STARTING) that exhausts
     * its attempts must reach FAILED too, not be silently skipped.
     */
    private fun handleProvisioningFailure(runtime: Runtime, from: RuntimeStatus, e: Exception) {
        val count = attempts.merge(runtime.id, 1) { old, one -> old + one } ?: 1
        logger.warn(
            "provisioning attempt {}/{} failed for runtime {} ({}): {}",
            count,
            maxAttempts,
            runtime.id,
            from,
            e.message,
        )
        if (count >= maxAttempts) {
            val moved = runtimeRepository.transition(
                runtime.id,
                from,
                RuntimeStatus.FAILED,
                errorMessage = e.message ?: e.toString(),
            )
            if (moved) {
                attempts.remove(runtime.id)
                logger.error("runtime {} moved to FAILED after {} attempts", runtime.id, count)
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_NOT_READY_GRACE_SECONDS = 30L
    }
}

