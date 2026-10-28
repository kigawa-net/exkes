package net.kigawa.exkes.runtimeagent.repository

import net.kigawa.exkes.runtimeagent.domain.Execution
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

object ExecutionRepository {

    private val storage = ConcurrentHashMap<String, Execution>()
    private val pendingQueue = ConcurrentLinkedQueue<Execution>()
    private val counter = AtomicInteger(0)

    fun getExecution(id: String): Execution? = storage[id]

    fun createExecution(
        runtimeId: String,
        `type`: String,
        commandJson: String?,
        agentConfigJson: String?,
        timeoutSeconds: Int?,
    ): Execution {
        val id = "exec-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        val now = Instant.now()
        val execution = Execution(
            id = id,
            runtimeId = runtimeId,
            `type` = `type`,
            status = "QUEUED",
            commandJson = commandJson,
            agentConfigJson = agentConfigJson,
            exitCode = null,
            errorMessage = null,
            logObjectKey = null,
            artifactObjectKeysJson = null,
            createdAt = now,
            updatedAt = now,
            startedAt = null,
            finishedAt = null,
            timeoutSeconds = timeoutSeconds,
            cancelRequested = false
        )
        storage[id] = execution
        pendingQueue.add(execution)
        return execution
    }

    fun startExecution(id: String): Boolean {
        val execution = storage.remove(id) ?: return false
        execution.status = "STARTING"
        execution.updatedAt = Instant.now()
        execution.startedAt = Instant.now()
        storage[id] = execution
        return true
    }

    fun setStatus(id: String, status: String, errorMessage: String? = null, exitCode: Int? = null): Execution? {
        val execution = storage[id] ?: return null
        execution.status = status
        execution.errorMessage = errorMessage
        execution.exitCode = exitCode
        execution.updatedAt = Instant.now()
        if (status in listOf("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT")) {
            execution.finishedAt = Instant.now()
        }
        return execution
    }

    fun cancelExecution(id: String): Execution? {
        val execution = storage[id] ?: return null
        execution.cancelRequested = true
        if (execution.status == "QUEUED") {
            execution.status = "CANCELLED"
            execution.finishedAt = Instant.now()
        }
        execution.updatedAt = Instant.now()
        return execution
    }

    fun getExecutionsByRuntime(runtimeId: String): List<Execution> {
        val result = mutableListOf<Execution>()
        for ((_, execution) in storage) {
            if (execution.runtimeId == runtimeId) {
                result.add(execution)
            }
        }
        return result
    }

    fun getPendingExecutions(): List<Execution> {
        val result = mutableListOf<Execution>()
        for ((_, execution) in storage) {
            if (execution.status == "QUEUED" || execution.status == "STARTING") {
                result.add(execution)
            }
        }
        return result
    }

    fun countByStatus(status: String): Int {
        var count = 0
        for ((_, execution) in storage) {
            if (execution.status == status) {
                count++
            }
        }
        return count
    }

    fun clear(): Unit {
        storage.clear()
        pendingQueue.clear()
    }

    fun exists(id: String): Boolean = storage.containsKey(id)

    fun size(): Int = storage.size

}
