package net.kigawa.exkes.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import net.kigawa.exkes.api.routes.configureExecutionRoutes
import net.kigawa.exkes.api.routes.configureHealthRoutes
import net.kigawa.exkes.api.routes.configureRuntimeRoutes
import net.kigawa.exkes.api.routes.configureTerminalSessionRoutes
import net.kigawa.exkes.api.routes.configureWorkspaceRoutes
import net.kigawa.exkes.api.routes.installErrorPages
import net.kigawa.exkes.api.runtime.RuntimeService
import net.kigawa.exkes.api.workspace.WorkspaceService
import net.kigawa.exkes.common.config.ExkesConfig
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.TerminalSessionRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.closeDataSource
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ApiApplication")

/**
 * exkes-api: the HTTP entry point of the Control Plane.
 *
 * It writes **desired state only** to MariaDB — a CREATING row on create, a
 * DELETING transition on delete — and answers immediately. Converting that row
 * into Kubernetes objects is exkes-controller's job.
 *
 * This process never calls the Kubernetes API: it has no client-java dependency
 * and platform runs it with `automountServiceAccountToken: false` (issue #2
 * "Controller RBAC").
 */
fun Application.module() {
    val config = ExkesConfig.fromApplication(this)

    // exkes-controller runs migrations too: the two Deployments start
    // independently, so whichever comes up first has to be able to serve. Flyway
    // serializes concurrent runs on flyway_schema_history. See the same note in
    // exkes-controller's Application.module().
    val dataSource = createDataSource(config.database)
    try {
        runMigrations(dataSource)
        connectDatabase(dataSource)
    } catch (e: Throwable) {
        // Startup aborts before the ApplicationStopping hook exists, so the pool
        // (and its housekeeping threads) would be leaked by this process.
        closeDataSource(dataSource)
        throw e
    }

    // Manual DI: repository -> service (plan chapter 3.2). No provisioner and no
    // reconciler: they live in exkes-controller.
    val workspaceRepository = WorkspaceRepository()
    val runtimeRepository = RuntimeRepository()
    val templateRepository = RuntimeTemplateRepository()
    val executionRepository = ExecutionRepository()
    val workspaceService = WorkspaceService(workspaceRepository, config.workspace)
    // Admission freshness: how old a Ready observation from exkes-controller may be before
    // the runtime stops admitting Executions. Read here, not in ExkesConfig, so that
    // exkes-controller (which shares ExkesConfig) does not need the key.
    val admissionFreshnessSeconds = environment.config
        .propertyOrNull("exkes.admission.freshnessSeconds")?.getString()?.toLong()
        ?: RuntimeService.DEFAULT_ADMISSION_FRESHNESS_SECONDS
    // Terminal session token lifetime (API-only setting; exkes-controller never reads it).
    val terminalSessionTtlSeconds = environment.config
        .propertyOrNull("exkes.terminal.sessionTtlSeconds")?.getString()?.toLong()
        ?: RuntimeService.DEFAULT_TERMINAL_SESSION_TTL_SECONDS
    val runtimeService = RuntimeService(
        runtimeRepository = runtimeRepository,
        executionRepository = executionRepository,
        templateRepository = templateRepository,
        workspaceRepository = workspaceRepository,
        admissionFreshnessSeconds = admissionFreshnessSeconds,
        terminalSessionRepository = TerminalSessionRepository(),
        terminalSessionTtlSeconds = terminalSessionTtlSeconds,
    )

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    // Allow cross-origin requests from the dashboard domain.
    // The dashboard is now bundled into exkes-api and served from the same origin,
    // but CORS is kept for future flexibility (e.g., separate deployment).
    install(CORS) {
        allowHost("exkes-stg.kigawa.net", schemes = listOf("https"))
        allowHost("exkes.kigawa.net", schemes = listOf("https"))
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
    }

    // Common error format for every route (plan chapter 6.2).
    installErrorPages()

    routing {
        // Serve the dashboard static files from the classpath resources.
        // The dashboard is built as a WASM static site and bundled into the API JAR
        // resources during the CI build (see Dockerfile, bundleDashboard task).
        staticResources("/static", "dashboard")
        // Serve index.html at the root path so that navigating to the domain
        // shows the dashboard instead of a 404.
        staticResources("/", "dashboard", "index.html")

        configureHealthRoutes()
        configureWorkspaceRoutes(workspaceService)
        configureRuntimeRoutes(runtimeService)
        configureExecutionRoutes(runtimeService)
        configureTerminalSessionRoutes(runtimeService)
    }

    monitor.subscribe(ApplicationStopping) {
        closeDataSource(dataSource)
    }

    logger.info("exkes-api started (desired-state only; reconciliation runs in exkes-controller)")
}
