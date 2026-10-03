package net.kigawa.exkes.common.domain

import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * RuntimeTemplate: a reusable specification for creating Runtimes.
 * Stored in MariaDB; the controller reads templates by ID when creating a Runtime.
 * This is the "plan" that turns into a Pod spec (plan chapter 7.2 / issue #1 "Runtime Provider").
 */
data class RuntimeTemplate(
    val id: String,
    val name: String,
    val description: String?,
    val image: String,
    val defaultResourcesJson: String?,   // JSON: default CPU/memory/ephemeral storage requests & limits
    val defaultEnvJson: String?,         // JSON: default environment variables
    val defaultTtlSeconds: Long?,        // Default TTL for runtimes using this template
    val labelsJson: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultResourcesJson(resources: Map<String, Any>): String = json.encodeToString(resources)
        fun defaultEnvJson(env: Map<String, String>): String = json.encodeToString(env)
        fun labelsJson(labels: Map<String, String>): String = json.encodeToString(labels)
    }
}