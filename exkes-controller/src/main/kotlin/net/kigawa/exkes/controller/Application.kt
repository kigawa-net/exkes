package net.kigawa.exkes.controller

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import net.kigawa.exkes.common.config.ExkesConfig
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.closeDataSource
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.config.WorkspaceConfig
import net.kigawa.exkes.controller.config.ControllerConfig
import net.kigawa.exkes.controller.runtime.KubernetesRuntimeProvider
import net.kigawa.exkes.controller.runtime.RuntimeReconciler
import net.kigawa.exkes.controller.workspace.KubernetesWorkspaceProvisioner
import net.kigawa.exkes.controller.workspace.WorkspaceReconciler
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ControllerApplication")

/**
 * exkes-controller: the only Control Plane process that talks to Kubernetes.
 *
 * Startup order: connect MariaDB -> Flyway migrate -> start the reconcile loops
 * -> serve /health (probes).
 *
 * Manual DI, same convention as exkes-api (plan chapter 3.2): repository ->
 * provisioner -> reconciler.
 *
 * Migrations run here as well as in exkes-api because the two Deployments are
 * started and restarted independently, and whichever comes up first must be
 * able to serve. Flyway serializes concurrent runs on `flyway_schema_history`
 * (it is created under a lock on the first run), and the migration set is a
 * single DDL statement, so the race window is narrow. If that ever becomes a
 * problem the fix is a dedicated migration job, not "remove it from one side".
 */
fun Application.module() {
    val config = ExkesConfig.fromApplication(this)
    val controllerConfig = ControllerConfig.fromApplication(this)
    val kubernetesConfig = controllerConfig.kubernetes

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

    // Manual DI: repository -> provisioner -> reconciler.
    val workspaceRepository = WorkspaceRepository()
    val runtimeRepository = RuntimeRepository()
    val templateRepository = RuntimeTemplateRepository()
    val workspaceConfig = WorkspaceConfig(
        storageClass = config.workspace.storageClass,
        defaultStorageSizeBytes = config.workspace.defaultStorageSizeBytes,
    )

    val workspaceProvisioner = KubernetesWorkspaceProvisioner(kubernetesConfig)
    val workspaceReconciler = WorkspaceReconciler(
        repository = workspaceRepository,
        provisioner = workspaceProvisioner,
        intervalSeconds = controllerConfig.reconciler.intervalSeconds,
        maxAttempts = controllerConfig.reconciler.maxAttempts,
        creatingTimeoutSeconds = controllerConfig.reconciler.creatingTimeoutSeconds,
    )

    val runtimeProvisioner = KubernetesRuntimeProvider(
        kubernetesConfig = kubernetesConfig,
        templateRepository = templateRepository,
        workspaceConfig = workspaceConfig,
    )
    val runtimeReconciler = RuntimeReconciler(
        runtimeRepository = runtimeRepository,
        workspaceRepository = workspaceRepository,
        provisioner = runtimeProvisioner,
        intervalSeconds = controllerConfig.reconciler.intervalSeconds,
        maxAttempts = controllerConfig.reconciler.maxAttempts,
    )

    routing {
        get("/health") {
            call.respondText("ok")
        }
    }

    workspaceReconciler.start()
    runtimeReconciler.start()

    monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        workspaceReconciler.stop()
        runtimeReconciler.stop()
        closeDataSource(dataSource)
    }

    logger.info(
        "exkes-controller started (namespace={}, interval={}s, maxAttempts={}, creatingTimeout={}s)",
        kubernetesConfig.namespace,
        controllerConfig.reconciler.intervalSeconds,
        controllerConfig.reconciler.maxAttempts,
        controllerConfig.reconciler.creatingTimeoutSeconds,
    )
}
