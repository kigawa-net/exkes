package net.kigawa.exkes.api.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    suspend fun createRuntime(
        workspaceId: String,
        templateId: String,
        resourcesJson: String?,
        envJson: String?,
        ttlSeconds: Long?,
        labels: Map<String, String>?,
    ): Runtime = onIoThread {
        // Validate workspace exists and is attachable
        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw RuntimeNotFoundException("workspace not found: $workspaceId")
        if (!workspace.canAttachRuntime()) {
            throw InvalidRuntimeRequestException("workspace ${workspaceId} is not attachable (status=${workspace.status})")
        }

        // Validate template exists
        val template = templateRepository.findById(templateId)
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

    suspend fun createExecution(
        runtimeId: String,
        type: ExecutionType,
        commandJson: String?,
        agentConfigJson: String?,
    ): Execution = onIoThread {
        // Validate runtime exists and is in a state that can run executions
        val runtime = runtimeRepository.findById(runtimeId)
            ?: throw RuntimeNotFoundException(runtimeId)
        if (runtime.status != RuntimeStatus.RUNNING) {
            throw InvalidRuntimeRequestException("runtime ${runtimeId} is not RUNNING (status=${runtime.status})")
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
        )
    }

    suspend fun listExecutions(
        runtimeId: String,
        status: ExecutionStatus?,
        limit: Int,
        offset: Int,
    ): ExecutionListResult = onIoThread {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw InvalidRuntimeRequestException("limit must be between 1 and $MAX_LIMIT: $limit")
        }
        if (offset < 0) {
            throw InvalidRuntimeRequestException("offset must not be negative: $offset")
        }
        executionRepository.list(runtimeId = runtimeId, status = status, limit = limit, offset = offset)
    }

    /** Returns the execution; validates it belongs to the runtime. */
    suspend fun getExecution(runtimeId: String, id: String): Execution = onIoThread {
        val execution = executionRepository.findById(id)
        if (execution == null || execution.runtimeId != runtimeId) {
            throw ExecutionNotFoundException(id)
        }
        execution
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

        /** Blocking JDBC must not run on a Netty event loop thread. */
        private suspend fun <T> onIoThread(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    }
}