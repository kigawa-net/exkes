package net.kigawa.exkes.terminalgateway.support

import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.TerminalSessionRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.closeDataSource
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.TerminalSession
import net.kigawa.exkes.common.domain.TerminalSessionToken
import net.kigawa.exkes.common.domain.WorkspaceStatus
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/** Upper bound for any wait in a test. Waits are conditional, never fixed sleeps. */
val WAIT_LIMIT = 15.seconds

/** Fresh in-memory H2 in MySQL mode, the same URL shape the other modules use. */
fun h2Url(name: String): String =
    "jdbc:h2:mem:exkes_gw_${name}_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"

/**
 * Creates the schema through Flyway and seeds Workspace / Runtime / session rows. The gateway
 * itself is pointed at the same JDBC URL; the module does not migrate, so the fixture must.
 */
class GatewayFixture(val jdbcUrl: String) : AutoCloseable {
    val dataSource: DataSource = createDataSource(DatabaseConfig(jdbcUrl = jdbcUrl, user = "sa", password = ""))
    val runtimes = RuntimeRepository()
    val sessions = TerminalSessionRepository()
    private val workspaces = WorkspaceRepository()
    private val templateId = "gw-template"
    private val workspaceId: String

    init {
        runMigrations(dataSource)
        connectDatabase(dataSource)
        RuntimeTemplateRepository().create(
            id = templateId,
            name = "gateway test template",
            description = null,
            image = "example/image:latest",
            defaultResourcesJson = null,
            defaultEnvJson = null,
            defaultTtlSeconds = null,
            labelsJson = null,
        )
        val ws = workspaces.create("ws-${System.nanoTime()}", "sc", 1024L, emptyMap())
        check(workspaces.reservePlacementForCreating(ws.id, NAMESPACE))
        check(workspaces.setPvcName(ws.id, "ws-${ws.id}"))
        check(workspaces.recordVerifiedPvcUid(ws.id, NAMESPACE, "uid-${ws.id}"))
        workspaces.transition(ws.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY)
        workspaceId = ws.id
    }

    /**
     * A RUNNING runtime whose Ready observation is [readyAt]. Its pod name is `localhost`, so the
     * agent URL template resolves to the loopback fake agent.
     */
    fun runningRuntime(readyAt: Instant = Instant.now()): Runtime {
        val rt = runtimes.create(workspaceId, templateId, null, null, null, null)
        check(runtimes.setPodName(rt.id, POD_NAME))
        check(runtimes.transition(rt.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        check(runtimes.transition(rt.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING))
        check(runtimes.recordReadyObserved(rt.id, readyAt))
        return runtimes.findById(rt.id)!!
    }

    /** Issues a session the way exkes-api does (hash only is stored). */
    fun issueSession(
        runtimeId: String,
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = issuedAt.plusSeconds(300),
    ): IssuedToken {
        val token = TerminalSessionToken.generate()
        val session = sessions.create(runtimeId, TerminalSessionToken.hash(token), issuedAt, expiresAt)
        return IssuedToken(session, token)
    }

    /**
     * Exposed has one global default database. The gateway's ApplicationStopping hook closes
     * its own pool, so assertions after a gateway run must point Exposed back at this pool.
     */
    fun reconnect() {
        connectDatabase(dataSource)
    }

    override fun close() {
        closeDataSource(dataSource)
    }

    companion object {
        const val NAMESPACE = "platform-exkes-stg-sandbox"
        const val POD_NAME = "localhost"
    }
}

class IssuedToken(val session: TerminalSession, val token: String)

/**
 * A stand-in for the runtime-agent of docs/runtime-agent-terminal.md. It echoes `input` as
 * `output`, answers `close` with `exit` and a normal close, and closes abnormally on `boom`.
 * Records every connection, its query parameters, and when the gateway disconnects.
 */
class FakeAgent : AutoCloseable {
    val connections = AtomicInteger(0)
    val queries = CopyOnWriteArrayList<Map<String, String>>()
    val disconnected = CompletableDeferred<Unit>()

    private val server: EmbeddedServer<*, *> = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        install(WebSockets) {
            maxFrameSize = 1L shl 20
        }
        routing {
            webSocket("/terminal") {
                connections.incrementAndGet()
                queries.add(call.request.queryParameters.entries().associate { (k, v) -> k to v.first() })
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val obj = Json.parseToJsonElement(frame.readText()).jsonObject
                        when (obj["type"]?.jsonPrimitive?.content) {
                            "input" -> {
                                val data = obj["data"]?.jsonPrimitive?.content ?: ""
                                send(Frame.Text(true, outputJson(data)))
                            }
                            "close" -> {
                                send(Frame.Text(true, """{"type":"exit","code":0}""".toByteArray()))
                                close(CloseReason(CloseReason.Codes.NORMAL, "exited"))
                                break
                            }
                            "boom" -> {
                                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "boom"))
                                break
                            }
                        }
                    }
                } finally {
                    disconnected.complete(Unit)
                }
            }
        }
    }

    val port: Int

    init {
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors() }.first().port
    }

    private fun outputJson(data: String): ByteArray = buildJsonObject {
        put("type", "output")
        put("data", data)
    }.toString().toByteArray()

    override fun close() {
        server.stop(0, 0)
    }
}

/** Returns a port on which nothing listens (the fake agent is started and stopped). */
fun closedPort(): Int {
    val agent = FakeAgent()
    val port = agent.port
    agent.close()
    return port
}

fun parseJson(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

fun authFrame(token: String, cols: Int? = null, rows: Int? = null): String = buildJsonObject {
    put("type", "auth")
    put("token", token)
    if (cols != null) put("cols", cols)
    if (rows != null) put("rows", rows)
}.toString()

fun inputFrame(data: String): String = buildJsonObject {
    put("type", "input")
    put("data", data)
}.toString()
