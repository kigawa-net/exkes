package net.kigawa.exkes.api.runtime

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.kigawa.exkes.common.db.IdempotentResult
import net.kigawa.exkes.common.db.IdempotencyRepository
import net.kigawa.exkes.common.db.ExecutionListResult
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.db.RuntimeListResult
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.domain.Execution
import net.kigawa.exkes.common.domain.ExecutionStatus
import net.kigawa.exkes.common.domain.ExecutionType
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.RuntimeTemplate

class RuntimeNotFoundException(id: String) : RuntimeException("runtime not found: $id")

class ExecutionNotFoundException(id: String) : RuntimeException("execution not found: $id")

class InvalidRuntimeRequestException(message: String) : RuntimeException(message)

class RuntimeConflictException(id: String) :
    RuntimeException("runtime is being modified concurrently: $id")

class ExecutionConflictException(id: String) :
    RuntimeException("execution is being modified concurrently: $id")

/**
 * Result of [RuntimeService.cancelExecution]. [accepted] is true only when this call moved the
 * execution to CANCELLING; false when it was already CANCELLING or terminal (state returned as is).
 */
data class CancelResult(val execution: Execution, val accepted: Boolean)

/**
 * The runtime is RUNNING in the DB but is not admitting Executions: no fresh Ready
 * observation from exkes-controller, or the TTL has passed. Retryable (503).
 */
class RuntimeNotAdmittingException(id: String, reason: String) :
    RuntimeException("runtime $id is not admitting executions: $reason")

/**
 * Application service for /v1/workspaces/{workspaceId}/runtimes and /v1/runtimes/{runtimeId}/executions.
 *
 * Owns validation and is the boundary between the Ktor event loop and the
 * blocking JDBC layer (plan chapter 3.2): every method is `suspend` and runs its
 * work on [Dispatchers.IO]. Exposed's `transaction {}` blocks the calling
 * thread, and Ktor handlers run on the Netty event loop, so without the
 * hop a single request would stall all connections handled by that loop.
 *
 * The service only writes **desired state**: [createRuntime] inserts a CREATING row,
 * [startRuntime]/[stopRuntime] transition to STARTING/STOPPING, and
 * [requestRuntimeDeletion] transitions to DELETING. Turning those rows into Pods
 * is exkes-controller's job (issue #2 "Controller RBAC").
 */
class RuntimeService(
    private val runtimeRepository: RuntimeRepository,
    private val executionRepository: ExecutionRepository,
    private val templateRepository: RuntimeTemplateRepository,
    private val workspaceRepository: WorkspaceRepository,
    /**
     * A RUNNING runtime admits Executions only if exkes-controller observed its Pod Ready
     * within this many seconds. Must be a few reconcile intervals (controller default 5s).
     */
    private val admissionFreshnessSeconds: Long = DEFAULT_ADMISSION_FRESHNESS_SECONDS,
    private val clock: () -> Instant = Instant::now,
    private val idempotencyRepository: IdempotencyRepository = IdempotencyRepository(),
) {
    init {
        require(admissionFreshnessSeconds > 0) { "admissionFreshnessSeconds must be positive: $admissionFreshnessSeconds" }
    }

    /**
     * Creates a runtime. With [idempotencyKey], a repeated request with the same key and
     * body returns the runtime created first (replayed = true). Validation runs only for a
     * fresh create, so a replay still answers after the workspace has left READY.
     */
    suspend fun createRuntime(
        workspaceId: String,
        templateId: String,
        resourcesJson: String?,
        envJson: String?,
        ttlSeconds: Long?,
        labels: Map<String, String>?,
        idempotencyKey: String? = null,
    ): IdempotentResult<Runtime> = onIoThread {
        val createFresh = {
            // Validate workspace exists and is attachable
            val workspace = workspaceRepository.findById(workspaceId)
                ?: throw RuntimeNotFoundException("workspace not found: $workspaceId")
            if (!workspace.canAttachRuntime()) {
                throw InvalidRuntimeRequestException("workspace ${workspaceId} is not attachable (status=${workspace.status})")
            }

            // Validate template exists
            templateRepository.findById(templateId)
                ?: throw InvalidRuntimeRequestException("runtime template not found: $templateId")

            // Validate TTL
            if (ttlSeconds != null && ttlSeconds <= 0) {
                throw InvalidRuntimeRequestException("ttlSeconds must be positive: $ttlSeconds")
            }

            runtimeRepository.create(
                workspaceId = workspaceId,
                templateId = templateId,
                resourcesJson = resourcesJson,
                envJson = envJson,
                ttlSeconds = ttlSeconds,
                labelsJson = labels?.let { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.encodeToString(it) },
            )
        }
        if (idempotencyKey == null) {
            IdempotentResult(createFresh(), replayed = false)
        } else {
            idempotencyRepository.execute(
                scope = "runtimes:$workspaceId",
                key = idempotencyKey,
                requestDigest = IdempotencyRepository.digestOf(
                    buildJsonObject {
                        put("workspaceId", workspaceId)
                        put("templateId", templateId)
                        put("resourcesJson", resourcesJson?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("envJson", envJson?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("ttlSeconds", ttlSeconds?.let { JsonPrimitive(it) } ?: JsonNull)
                        putJsonObject("labels") { (labels ?: emptyMap()).toSortedMap().forEach { (k, v) -> put(k, v) } }
                    },
                ),
                load = { runtimeRepository.findById(it) },
                create = createFresh,
                idOf = { it.id },
            )
        }
    }

    suspend fun listRuntimes(
        workspaceId: String,
        status: RuntimeStatus?,
        limit: Int,
        offset: Int,
    ): RuntimeListResult = onIoThread {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw InvalidRuntimeRequestException("limit must be between 1 and $MAX_LIMIT: $limit")
        }
        if (offset < 0) {
            throw InvalidRuntimeRequestException("offset must not be negative: $offset")
        }
        runtimeRepository.list(workspaceId = workspaceId, status = status, limit = limit, offset = offset)
    }

    /** Returns the runtime; DELETED rows count as gone (404, plan chapter 6.2). */
    suspend fun getRuntime(workspaceId: String, id: String): Runtime = onIoThread {
        val runtime = runtimeRepository.findById(id)
        if (runtime == null || runtime.status == RuntimeStatus.DELETED || runtime.workspaceId != workspaceId) {
            throw RuntimeNotFoundException(id)
        }
        runtime
    }

    /**
     * Requests runtime start and returns the row after the transition.
     */
    suspend fun startRuntime(workspaceId: String, id: String): Runtime = onIoThread {
        repeat(MAX_TRANSITION_ATTEMPTS) {
            val runtime = getBlocking(workspaceId, id)
            when {
                runtime.status == RuntimeStatus.STARTING -> return@onIoThread runtime
                runtime.status == RuntimeStatus.RUNNING -> return@onIoThread runtime
                !runtime.canTransitionTo(RuntimeStatus.STARTING) ->
                    throw InvalidRuntimeRequestException("runtime in ${runtime.status} cannot be started")
                runtimeRepository.transition(id, runtime.status, RuntimeStatus.STARTING) ->
                    return@onIoThread runtime.copy(status = RuntimeStatus.STARTING)
            }
        }
        throw RuntimeConflictException(id)
    }

    /**
     * Requests runtime stop and returns the row after the transition.
     */
    suspend fun stopRuntime(workspaceId: String, id: String): Runtime = onIoThread {
        repeat(MAX_TRANSITION_ATTEMPTS) {
            val runtime = getBlocking(workspaceId, id)
            when {
                runtime.status == RuntimeStatus.STOPPING -> return@onIoThread runtime
                runtime.status == RuntimeStatus.STOPPED -> return@onIoThread runtime
                !runtime.canTransitionTo(RuntimeStatus.STOPPING) ->
                    throw InvalidRuntimeRequestException("runtime in ${runtime.status} cannot be stopped")
                runtimeRepository.transition(id, runtime.status, RuntimeStatus.STOPPING) ->
                    return@onIoThread runtime.copy(status = RuntimeStatus.STOPPING)
            }
        }
        throw RuntimeConflictException(id)
    }

    /**
     * Requests runtime deletion and returns the row after the transition.
     */
    suspend fun requestRuntimeDeletion(workspaceId: String, id: String): Runtime = onIoThread {
        repeat(MAX_TRANSITION_ATTEMPTS) {
            val runtime = getBlocking(workspaceId, id)
            when {
                runtime.status == RuntimeStatus.DELETING -> return@onIoThread runtime
                runtime.status == RuntimeStatus.DELETED -> throw RuntimeNotFoundException(id)
                !runtime.canTransitionTo(RuntimeStatus.DELETING) ->
                    throw InvalidRuntimeRequestException("runtime in ${runtime.status} cannot be deleted")
                runtimeRepository.transition(id, runtime.status, RuntimeStatus.DELETING) ->
                    return@onIoThread runtime.copy(status = RuntimeStatus.DELETING)
            }
        }
        throw RuntimeConflictException(id)
    }

    // --- Execution API ---

    /**
     * Creates an execution. With [idempotencyKey], a repeated request with the same key and
     * body returns the execution created first (replayed = true, no second execution).
     */
    suspend fun createExecution(
        runtimeId: String,
        type: ExecutionType,
        commandJson: String?,
        agentConfigJson: String?,
        labels: Map<String, String>? = null,
        idempotencyKey: String? = null,
    ): IdempotentResult<Execution> = onIoThread {
        val effectiveLabels = labels ?: emptyMap()
        validateLabels(effectiveLabels)
        // Runs only for a fresh create. A replay (same Idempotency-Key) is answered from the
        // stored record before this lambda is reached, so a closed Admission never produces a
        // new Execution, and a replay of an existing one still returns it.
        val createFresh = {
            // Validate runtime exists and is in a state that can run executions
            val runtime = runtimeRepository.findById(runtimeId)
                ?: throw RuntimeNotFoundException(runtimeId)
            if (runtime.status != RuntimeStatus.RUNNING) {
                throw InvalidRuntimeRequestException("runtime ${runtimeId} is not RUNNING (status=${runtime.status})")
            }
            // The DB status alone is not enough: the controller may be down, or the Pod may have
            // turned NotReady since the last observation. Require a fresh Ready observation.
            // Compare full Duration values: Duration.seconds truncates, so 15.9999s would read as 15s and a stale
            // observation could still be admitted at the boundary.
            val now = clock()
            val readyAt = runtime.lastReadyObservedAt
                ?: throw RuntimeNotAdmittingException(runtimeId, "no Ready observation")
            if (Duration.between(readyAt, now) > Duration.ofSeconds(admissionFreshnessSeconds)) {
                throw RuntimeNotAdmittingException(runtimeId, "last Ready observation is stale")
            }
            if (readyAt.isAfter(now.plusSeconds(admissionFreshnessSeconds))) {
                throw RuntimeNotAdmittingException(runtimeId, "Ready observation is in the future")
            }
            // Same TTL rule as the controller: a runtime past its TTL must not start new work
            // while the controller has not yet moved it to EXPIRED.
            val ttl = runtime.ttlSeconds
            if (ttl != null && ttl > 0 && Duration.between(runtime.createdAt, now) >= Duration.ofSeconds(ttl.toLong())) {
                throw RuntimeNotAdmittingException(runtimeId, "TTL has expired")
            }

            // Validate type-specific payload
            when (type) {
                ExecutionType.COMMAND -> {
                    if (commandJson == null || commandJson.isBlank()) {
                        throw InvalidRuntimeRequestException("commandJson is required for COMMAND type")
                    }
                }
                ExecutionType.AGENT -> {
                    // agentConfigJson is optional for AGENT
                }
                ExecutionType.SYSTEM -> {
                    // No payload required
                }
            }

            executionRepository.create(
                runtimeId = runtimeId,
                type = type,
                commandJson = commandJson,
                agentConfigJson = agentConfigJson,
                labels = effectiveLabels,
            )
        }
        if (idempotencyKey == null) {
            IdempotentResult(createFresh(), replayed = false)
        } else {
            idempotencyRepository.execute(
                scope = "executions:$runtimeId",
                key = idempotencyKey,
                requestDigest = IdempotencyRepository.digestOf(
                    buildJsonObject {
                        put("runtimeId", runtimeId)
                        put("type", type.name)
                        put("commandJson", commandJson?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("agentConfigJson", agentConfigJson?.let { JsonPrimitive(it) } ?: JsonNull)
                        putJsonObject("labels") { effectiveLabels.toSortedMap().forEach { (k, v) -> put(k, v) } }
                    },
                ),
                load = { executionRepository.findById(it) },
                create = createFresh,
                idOf = { it.id },
            )
        }
    }

    suspend fun listExecutions(
        runtimeId: String,
        status: ExecutionStatus?,
        limit: Int,
        offset: Int,
        labels: Map<String, String> = emptyMap(),
    ): ExecutionListResult = onIoThread {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw InvalidRuntimeRequestException("limit must be between 1 and $MAX_LIMIT: $limit")
        }
        if (offset < 0) {
            throw InvalidRuntimeRequestException("offset must not be negative: $offset")
        }
        executionRepository.list(runtimeId = runtimeId, status = status, limit = limit, offset = offset, labels = labels)
    }

    /**
     * Records a cancel request: a QUEUED / STARTING / RUNNING execution moves to CANCELLING
     * (conditional UPDATE). CANCELLING and terminal executions are returned unchanged
     * (accepted = false), so repeated cancels are harmless.
     *
     * This never sets CANCELLED. CANCELLING means "stop requested, not confirmed"; the process
     * stop is performed by runtime-agent and confirmed via `ExecutionRepository.confirmCancelled`,
     * which is not called from here.
     */
    suspend fun cancelExecution(runtimeId: String, id: String): CancelResult = onIoThread {
        repeat(MAX_TRANSITION_ATTEMPTS) {
            val execution = getExecutionBlocking(runtimeId, id)
            when {
                execution.isTerminal || execution.status == ExecutionStatus.CANCELLING ->
                    return@onIoThread CancelResult(execution, accepted = false)
                executionRepository.transition(id, execution.status, ExecutionStatus.CANCELLING) ->
                    return@onIoThread CancelResult(getExecutionBlocking(runtimeId, id), accepted = true)
                // Lost the race: loop re-reads the status and re-decides.
            }
        }
        throw ExecutionConflictException(id)
    }

    /** Returns the execution; validates it belongs to the runtime. */
    suspend fun getExecution(runtimeId: String, id: String): Execution = onIoThread {
        getExecutionBlocking(runtimeId, id)
    }

    private fun getExecutionBlocking(runtimeId: String, id: String): Execution {
        val execution = executionRepository.findById(id)
        if (execution == null || execution.runtimeId != runtimeId) {
            throw ExecutionNotFoundException(id)
        }
        return execution
    }

    /** Execution labels: key `[A-Za-z0-9][A-Za-z0-9._/-]{0,62}`, value up to 256 chars, at most 32 entries. */
    private fun validateLabels(labels: Map<String, String>) {
        if (labels.size > MAX_LABELS) {
            throw InvalidRuntimeRequestException("labels must have at most $MAX_LABELS entries: ${labels.size}")
        }
        labels.forEach { (key, value) ->
            if (!LABEL_KEY_PATTERN.matches(key)) {
                throw InvalidRuntimeRequestException("invalid label key: $key")
            }
            if (value.length > MAX_LABEL_VALUE_LENGTH) {
                throw InvalidRuntimeRequestException("label value for $key must be at most $MAX_LABEL_VALUE_LENGTH chars")
            }
        }
    }

    private fun getBlocking(workspaceId: String, id: String): Runtime {
        val runtime = runtimeRepository.findById(id)
        if (runtime == null || runtime.status == RuntimeStatus.DELETED || runtime.workspaceId != workspaceId) {
            throw RuntimeNotFoundException(id)
        }
        return runtime
    }

    companion object {
        const val MAX_LIMIT = 500
        const val MAX_TRANSITION_ATTEMPTS = 3
        const val DEFAULT_ADMISSION_FRESHNESS_SECONDS = 15L
        const val MAX_LABELS = 32
        const val MAX_LABEL_VALUE_LENGTH = 256
        private val LABEL_KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,62}")

        /** Blocking JDBC must not run on a Netty event loop thread. */
        private suspend fun <T> onIoThread(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    }
}