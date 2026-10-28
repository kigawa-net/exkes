package net.kigawa.exkes.runtimeagent.domain

import java.time.Instant

/**
 * Lightweight execution domain for runtime-agent.
 * Does NOT depend on exkes-common (sandbox side only).
 * State is reported to controller via JSON/HTTP.
 *
 * @see <a href="https://github.com/kigawa-net/exkes/issues/8">Issue #8</a>
 */
data class Execution(
    val id: String,                          // UUID
    val runtimeId: String,                   // Parent Runtime ID
    val `type`: String,                      // COMMAND, AGENT, SYSTEM
    var status: String,                      // QUEUED, STARTING, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
    val commandJson: String?,                // The command to execute (JSON-serialized)
    val agentConfigJson: String?,           // Agent-specific configuration (JSON)
    var exitCode: Int?,                      // Exit code when completed
    var errorMessage: String?,               // Error message when failed
    val logObjectKey: String?,               // Object key for log storage (S3/RGW)
    val artifactObjectKeysJson: String?,     // Artifact object keys (JSON)
    val createdAt: Instant,                  // Creation timestamp
    var updatedAt: Instant,                  // Last update timestamp
    var startedAt: Instant?,                 // When execution started
    var finishedAt: Instant?,                // When execution finished
    var timeoutSeconds: Int?,                // Timeout in seconds (nullable for no timeout)
    var cancelRequested: Boolean = false     // Cancel flag
)