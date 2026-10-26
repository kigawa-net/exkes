package net.kigawa.exkes.terminalgateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.TerminalSessionRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.closeDataSource
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.terminalgateway.auth.AgentUrlResolver
import net.kigawa.exkes.terminalgateway.auth.TerminalAuthorizer
import net.kigawa.exkes.terminalgateway.config.TerminalGatewayConfig
import net.kigawa.exkes.terminalgateway.relay.TerminalRelay
import net.kigawa.exkes.terminalgateway.routes.terminalRoutes
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TerminalGatewayApplication")

/**
 * exkes-terminal-gateway: the WebSocket entry point for Terminals (plan chapter 3.1, 第5MS).
 *
 * It reads the schema but does NOT migrate it: exkes-api and exkes-controller own the Flyway
 * history, and this process starts independently of them. It never calls the Kubernetes API.
 * Its only outbound connections are the MariaDB pool and the runtime-agent WebSockets.
 */
fun Application.module() {
    val config = TerminalGatewayConfig.fromConfig(environment.config)

    val dataSource = createDataSource(config.database)
    try {
        connectDatabase(dataSource)
    } catch (e: Throwable) {
        // Startup aborts before the ApplicationStopping hook exists; release the pool here.
        closeDataSource(dataSource)
        throw e
    }

    val authorizer = TerminalAuthorizer(
        terminalSessions = TerminalSessionRepository(),
        runtimes = RuntimeRepository(),
        workspaces = WorkspaceRepository(),
        agentUrls = AgentUrlResolver(config.agentUrlTemplate),
        admissionFreshnessSeconds = config.admissionFreshnessSeconds,
    )
    val agentClient = HttpClient(CIO) {
        install(ClientWebSockets) {
            maxFrameSize = config.maxMessageBytes.toLong()
        }
    }
    val relay = TerminalRelay(agentClient, config)

    install(WebSockets) {
        // Frames above the limit make Ktor close the connection (1009) before any parsing.
        maxFrameSize = config.maxMessageBytes.toLong()
        pingPeriodMillis = 30_000L
        timeoutMillis = 60_000L
    }

    routing {
        get("/health") {
            call.respondText("ok")
        }
        terminalRoutes(config, authorizer, relay)
    }

    monitor.subscribe(ApplicationStopping) {
        agentClient.close()
        closeDataSource(dataSource)
    }

    logger.info(
        "exkes-terminal-gateway started (idleTimeout={}s, maxLifetime={}s, maxMessageBytes={})",
        config.idleTimeout.seconds,
        config.maxLifetime.seconds,
        config.maxMessageBytes,
    )
}
