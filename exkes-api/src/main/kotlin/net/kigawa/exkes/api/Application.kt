package net.kigawa.exkes.api

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import net.kigawa.exkes.api.routes.configureExecutionRoutes
import net.kigawa.exkes.api.routes.configureHealthRoutes
import net.kigawa.exkes.api.routes.configureRuntimeRoutes
import net.kigawa.exkes.api.routes.configureWorkspaceRoutes
import net.kigawa.exkes.api.routes.installErrorPages
import net.kigawa.exkes.api.runtime.RuntimeService
import net.kigawa.exkes.api.workspace.WorkspaceService
import net.kigawa.exkes.common.config.ExkesConfig
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
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
    val runtimeService = RuntimeService(
        runtimeRepository = runtimeRepository,
        executionRepository = executionRepository,
        templateRepository = templateRepository,
        workspaceRepository = workspaceRepository,
    )

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    // Common error format for every route (plan chapter 6.2).
    installErrorPages()

    routing {
        configureHealthRoutes()
        configureWorkspaceRoutes(workspaceService)
        configureRuntimeRoutes(runtimeService)
        configureExecutionRoutes(runtimeService)
    }

    monitor.subscribe(ApplicationStopping) {
        closeDataSource(dataSource)
    }

    logger.info("exkes-api started (desired-state only; reconciliation runs in exkes-controller)")
}
