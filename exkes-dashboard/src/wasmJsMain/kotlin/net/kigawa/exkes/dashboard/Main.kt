package net.kigawa.exkes.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val statusColors = mapOf(
    "CREATING" to Color(0xFFE65100),   // Orange
    "READY" to Color(0xFF2E7D32),      // Green
    "ARCHIVED" to Color(0xFF546E7A),   // Blue Grey
    "DELETING" to Color(0xFFC62828),   // Red
    "ERROR" to Color(0xFFB71C1C),      // Dark Red
)

private val statusIcons = mapOf(
    "CREATING" to "⏳",
    "READY" to "✓",
    "ARCHIVED" to "📦",
    "DELETING" to "🗑",
    "ERROR" to "✗",
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow(canvasElementId = "exkes-dashboard") {
        DashboardApp()
    }
}

@Composable
fun DashboardApp() {
    var workspaces by remember { mutableStateOf<List<WorkspaceItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        doLoadWorkspaces { items, err ->
            workspaces = items
            error = err
            loading = false
        }
        // Auto-refresh every 10 seconds
        while (true) {
            delay(10_000)
            doLoadWorkspaces { items, err ->
                workspaces = items
                error = err
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "exkes",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            doLoadWorkspaces { items, err ->
                                workspaces = items
                                error = err
                                loading = false
                            }
                        }
                    }
                ) {
                    Text("🔄", fontSize = 20.sp)
                }
            }

            // Stats cards
            if (workspaces != null) {
                StatsRow(workspaces = workspaces!!)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                AnimatedContent(targetState = loading, label = "Loading state") { isLoading ->
                    if (isLoading && workspaces == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Loading workspaces...")
                        }
                    } else if (error != null && workspaces == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text("⚠", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Failed to connect to API")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error ?: "Unknown error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Make sure exkes-api is running and accessible",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (workspaces.isNullOrEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text("📋", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No workspaces yet")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Create a workspace via POST /v1/workspaces",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        WorkspaceTable(workspaces = workspaces!!)
                    }
                }
            }
        }
    }
}

@Composable
fun StatsRow(workspaces: List<WorkspaceItem>) {
    val statusCounts = workspaces.groupingBy { it.status }.eachCount()
    val total = workspaces.size

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Total card
        item {
            StatCard(
                title = "Total",
                count = total,
                color = MaterialTheme.colorScheme.primary,
                icon = "📊"
            )
        }
        // Per-status cards
        items(statusCounts.entries.toList()) { entry ->
            StatCard(
                title = entry.key,
                count = entry.value,
                color = statusColors[entry.key] ?: MaterialTheme.colorScheme.onSurface,
                icon = statusIcons[entry.key] ?: "•"
            )
        }
    }
}

@Composable
fun StatCard(title: String, count: Int, color: Color, icon: String) {
    Card(
        modifier = Modifier.height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = icon, fontSize = 14.sp)
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
        }
    }
}

@Composable
fun WorkspaceTable(workspaces: List<WorkspaceItem>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Name", modifier = Modifier.weight(2f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold)
                Text("Status", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold)
                Text("Storage", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold)
                Text("Created", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold)
                Text("PVC", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold)
            }
        }

        items(workspaces) { ws ->
            WorkspaceRow(workspace = ws)
        }
    }
}

@Composable
fun WorkspaceRow(workspace: WorkspaceItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Name
                Column(modifier = Modifier.weight(2f)) {
                    Text(
                        text = workspace.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = workspace.id.take(8),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status badge
                Column(modifier = Modifier.weight(1f)) {
                    val statusColor = statusColors[workspace.status]
                        ?: MaterialTheme.colorScheme.onSurface
                    val statusIcon = statusIcons[workspace.status] ?: "•"
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = statusIcon, fontSize = 10.sp)
                            Text(
                                text = workspace.status.lowercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (workspace.errorMessage != null) {
                        Text(
                            text = workspace.errorMessage.take(30) + if (workspace.errorMessage.length > 30) "..." else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // Storage
                Text(
                    text = formatBytes(workspace.storageSizeBytes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Created
                Text(
                    text = formatDateTime(workspace.createdAt),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // PVC
                Text(
                    text = workspace.pvcName?.take(12) ?: "—",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "${(bytes / 1_073_741_824.0).toString().take(4)} GiB"
        bytes >= 1_048_576 -> "${(bytes / 1_048_576.0).toString().take(4)} MiB"
        bytes >= 1_024 -> "${(bytes / 1_024.0).toString().take(4)} KiB"
        else -> "$bytes B"
    }
}

fun formatDateTime(isoString: String): String {
    return try {
        // Simple ISO 8601 parsing for display
        val s = isoString.replace("T", " ").replace("Z", "").trimEnd('.')
        val parts = s.split("[.+-]".toRegex()).filter { it.isNotBlank() }
        if (parts.size >= 3) {
            val date = parts[0]  // yyyy-MM-dd
            val time = if (parts.size >= 2) parts[1] else "" // HH:mm:ss
            "$date $time"
        } else {
            isoString.take(10)
        }
    } catch (_: Exception) {
        isoString.take(16)
    }
}

/**
 * Load workspaces and call the callback with results.
 * This is a top-level suspend function so it can be called from LaunchedEffect.
 */
suspend fun doLoadWorkspaces(callback: (List<WorkspaceItem>?, String?) -> Unit) {
    fetchWorkspaces().fold(
        onSuccess = { response ->
            callback(response.items, null)
        },
        onFailure = { e ->
            callback(null, e.message ?: "Failed to load workspaces")
        }
    )
}
