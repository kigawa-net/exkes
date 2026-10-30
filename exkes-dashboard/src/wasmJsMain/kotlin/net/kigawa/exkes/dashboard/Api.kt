package net.kigawa.exkes.dashboard

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * API response models matching exkes-api's Workspace DTOs.
 */

@Serializable
data class WorkspaceItem(
    val id: String,
    val name: String,
    val status: String,
    val storageClass: String,
    val storageSizeBytes: Long,
    val pvcName: String?,
    val errorMessage: String?,
    val labels: Map<String, String>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class WorkspaceListResponse(
    val items: List<WorkspaceItem>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)

private val apiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// WASM runs in the browser; use its Fetch API engine instead of CIO, which
// targets native/JVM environments and cannot make browser HTTP requests.
private val httpClient = HttpClient(Js) {
    install(ContentNegotiation) {
        json(apiJson)
    }
}

private val isApiUrlDefined: Boolean = js("typeof __EXKES_API_URL__ !== 'undefined'")
private val rawApiUrl: String = js("__EXKES_API_URL__ || ''")

val apiBaseUrl: String
    get() = if (isApiUrlDefined) rawApiUrl else ""

suspend fun fetchWorkspaces(): Result<WorkspaceListResponse> {
    val baseUrl = apiBaseUrl
    val path = if (baseUrl.isNotBlank()) "$baseUrl/v1/workspaces" else "/v1/workspaces"
    return try {
        val response = httpClient.get(path)
        val body = response.body<WorkspaceListResponse>()
        Result.success(body)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
