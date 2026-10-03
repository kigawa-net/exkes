package net.kigawa.exkes.terminalgateway

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

/**
 * exkes-terminal-gateway skeleton.
 *
 * OUT OF SCOPE for issue #2: Terminal Gateway is listed in that issue's
 * "対象外" section. Starts and answers /health only; the WebSocket relay,
 * session validation and audit logging are follow-up work after #1
 * (plan chapter 3.1).
 */
fun Application.module() {
    logger.info("exkes-terminal-gateway is a milestone-5 skeleton; no relay yet")
    routing {
        get("/health") {
            call.respondText("ok")
        }
    }
}
