package net.kigawa.exkes.runtimeagent

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import net.kigawa.exkes.runtimeagent.execution.ExecutionManager
import net.kigawa.exkes.runtimeagent.execution.ExecutionStore
import net.kigawa.exkes.runtimeagent.execution.FileExecutionStore
import net.kigawa.exkes.runtimeagent.routes.configureExecutionRoutes
import net.kigawa.exkes.runtimeagent.routes.installAgentErrorPages
import java.nio.file.Path

/**
 * runtime-agent: Process / Execution management inside a Runtime Pod (issue #8).
 * Never depends on exkes-common (plan chapter 3); speaks JSON over HTTP only.
 *
 * Config (all optional): `agent.stateDir` (execution metadata, default
 * /var/lib/exkes-agent), `agent.workingDir` (default cwd for commands),
 * `agent.maxLogBytes`.
 */
fun Application.module() {
    val cfg = environment.config
    val stateDir = cfg.propertyOrNull("agent.stateDir")?.getString() ?: "/var/lib/exkes-agent"
    val workingDir = cfg.propertyOrNull("agent.workingDir")?.getString()
    val maxLogBytes = cfg.propertyOrNull("agent.maxLogBytes")?.getString()?.toLongOrNull() ?: (16L * 1024 * 1024)
    agentModule(FileExecutionStore(Path.of(stateDir)), workingDir, maxLogBytes)
}

fun Application.agentModule(store: ExecutionStore, workingDir: String? = null, maxLogBytes: Long = 16L * 1024 * 1024) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    installAgentErrorPages()

    val scope = CoroutineScope(SupervisorJob())
    val manager = ExecutionManager(scope, store, workingDir, maxLogBytes)
    manager.recover()
    // Pod deletion / SIGTERM: kill children and record in-flight executions as FAILED.
    monitor.subscribe(ApplicationStopping) {
        manager.shutdown()
        scope.cancel()
    }

    routing {
        get("/health") { call.respondText("ok") }
    }
    configureExecutionRoutes(manager)
}
