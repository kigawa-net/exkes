package net.kigawa.exkes.api.routes

import java.time.Instant
import kotlinx.serialization.Serializable
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.RuntimeTemplate

@Serializable
data class CreateRuntimeRequest(
    val templateId: String,
    val resourcesJson: String? = null,
    val envJson: String? = null,
    val ttlSeconds: Long? = null,
    val labels: Map<String, String>? = null,
)

@Serializable
data class RuntimeResponse(
    val id: String,
    val workspaceId: String,
    val templateId: String,
    val status: String,
    val resourcesJson: String?,
    val envJson: String?,
    val ttlSeconds: Long?,
    val podName: String?,
    val errorMessage: String?,
    val labels: Map<String, String>,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant,
)

@Serializable
data class RuntimeListResponse(
    val items: List<RuntimeResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class CreateExecutionRequest(
    val type: String,  // COMMAND, AGENT, SYSTEM
    val commandJson: String? = null,
    val agentConfigJson: String? = null,
)

@Serializable
data class ExecutionResponse(
    val id: String,
    val runtimeId: String,
    val type: String,
    val status: String,
    val commandJson: String?,
    val agentConfigJson: String?,
    val exitCode: Int?,
    val errorMessage: String?,
    val logObjectKey: String?,
    val artifactObjectKeysJson: String?,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val startedAt: Instant?,
    @Serializable(with = InstantIsoSerializer::class)
    val finishedAt: Instant?,
)

@Serializable
data class ExecutionListResponse(
    val items: List<ExecutionResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class RuntimeTemplateResponse(
    val id: String,
    val name: String,
    val description: String?,
    val image: String,
    val defaultResourcesJson: String?,
    val defaultEnvJson: String?,
    val defaultTtlSeconds: Long?,
    val labels: Map<String, String>,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant,
)

fun Runtime.toResponse(): RuntimeResponse {
    val labels = labelsJson?.let { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
    return RuntimeResponse(
        id = id,
        workspaceId = workspaceId,
        templateId = templateId,
        status = status.name,
        resourcesJson = resourcesJson,
        envJson = envJson,
        ttlSeconds = ttlSeconds,
        podName = podName,
        errorMessage = errorMessage,
        labels = labels,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun RuntimeTemplate.toResponse(): RuntimeTemplateResponse {
    val labels = labelsJson?.let { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
    return RuntimeTemplateResponse(
        id = id,
        name = name,
        description = description,
        image = image,
        defaultResourcesJson = defaultResourcesJson,
        defaultEnvJson = defaultEnvJson,
        defaultTtlSeconds = defaultTtlSeconds,
        labels = labels,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun net.kigawa.exkes.common.domain.Execution.toResponse(): ExecutionResponse = ExecutionResponse(
    id = id,
    runtimeId = runtimeId,
    type = type.name,
    status = status.name,
    commandJson = commandJson,
    agentConfigJson = agentConfigJson,
    exitCode = exitCode,
    errorMessage = errorMessage,
    logObjectKey = logObjectKey,
    artifactObjectKeysJson = artifactObjectKeysJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
)
