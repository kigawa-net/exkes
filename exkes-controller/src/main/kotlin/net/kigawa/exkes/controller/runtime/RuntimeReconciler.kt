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
import net.kigawa.exkes.common.domain.WorkspaceStatus
import org.slf4j.LoggerFactory

/**
 * Polls runtimes in non-terminal states and drives them forward:
 *
 * - CREATING: create Pod -> STARTING
 * - STARTING: wait for Pod Running -> RUNNING
 * - RUNNING: monitor for Pod failure/TTL expiry -> FAILED/EXPIRED
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
 * Every DB write is a conditional UPDATE and Pod operations are idempotent.
 */
class RuntimeReconciler(
    private val runtimeRepository: RuntimeRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val provisioner: RuntimeProvisioner,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(RuntimeReconciler::class.java)

    // In-memory failure counters; a restart resets them.
    private val attempts = ConcurrentHashMap<String, Int>()
    private var executor: ScheduledExecutorService? = null

    /** Runs one reconcile cycle synchronously (also used directly by tests). */
    fun reconcileOnce() {
        val creating = runtimeRepository.findByStatus(RuntimeStatus.CREATING)
        val starting = runtimeRepository.findByStatus(RuntimeStatus.STARTING)
        val running = runtimeRepository.findByStatus(RuntimeStatus.RUNNING)
        val stopping = runtimeRepository.findByStatus(RuntimeStatus.STOPPING)
        val expired = runtimeRepository.findByStatus(RuntimeStatus.EXPIRED)
        val deleting = runtimeRepository.findByStatus(RuntimeStatus.DELETING)

        processCreating(creating)
        processStarting(starting)
        processRunning(running)
        processStopping(stopping)
        processExpired(expired)
        processDeleting(deleting)

        // Drop counters of runtimes that are no longer being processed
        val processing = (creating + starting + running + stopping + expired + deleting)
            .mapTo(mutableSetOf<String>()) { it.id }
        attempts.keys.retainAll(processing)
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

    private fun processCreating(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            // Verify workspace exists and is attachable
            val workspace = workspaceRepository.findById(runtime.workspaceId)
            if (workspace == null || !workspace.status.canAttachRuntime()) {
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
                handleCreatingFailure(runtime, e)
            }
        }
    }

    private fun processStarting(runtimes: List<Runtime>) {
        for (runtime in runtimes) {
            try {
                val status = provisioner.getRuntimeStatus(runtime)
                when (status) {
                    RuntimeStatus.RUNNING -> {
                        if (runtimeRepository.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING)) {
                            attempts.remove(runtime.id)
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
                handleCreatingFailure(runtime, e)
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

            try {
                val status = provisioner.getRuntimeStatus(runtime)
                if (status == RuntimeStatus.FAILED) {
                    val message = "Pod failed while running"
                    if (runtimeRepository.transition(runtime.id, RuntimeStatus.RUNNING, RuntimeStatus.FAILED, message)) {
                        attempts.remove(runtime.id)
                        logger.error("runtime {} moved to FAILED: {}", runtime.id, message)
                    }
                }
                // If RUNNING, nothing to do
            } catch (e: Exception) {
                logger.warn("failed to check status for runtime {}: {}", runtime.id, e.message)
            }
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

    private fun handleCreatingFailure(runtime: Runtime, e: Exception) {
        val count = attempts.merge(runtime.id, 1) { old, one -> old + one } ?: 1
        logger.warn(
            "provisioning attempt {}/{} failed for runtime {}: {}",
            count,
            maxAttempts,
            runtime.id,
            e.message,
        )
        if (count >= maxAttempts) {
            val moved = runtimeRepository.transition(
                runtime.id,
                RuntimeStatus.CREATING,
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
    }
}

private fun WorkspaceStatus.canAttachRuntime(): Boolean =
    this == WorkspaceStatus.READY || this == WorkspaceStatus.ARCHIVED