package net.kigawa.exkes.common.config

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig

/**
 * HOCON configuration rooted at the `exkes` block of application.conf.
 *
 * Every key can be overridden by an environment variable named
 * `EXKES_<SECTION>_<KEY>` (plan chapter 4), e.g. `EXKES_DATABASE_JDBC_URL`.
 *
 * Only the sections that both Control Plane processes need are read here. The
 * `exkes.kubernetes.*` and `exkes.reconciler.*` sections are read by
 * exkes-controller alone (its own `ControllerConfig`), because exkes-api never
 * talks to the Kubernetes API and runs no reconcile loop (issue #2
 * "Controller RBAC").
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
)

data class WorkspaceConfig(
    val storageClass: String,
    val defaultStorageSizeBytes: Long,
)

data class ExkesConfig(
    val database: DatabaseConfig,
    val workspace: WorkspaceConfig,
) {
    companion object {
        fun fromApplication(application: Application): ExkesConfig = fromConfig(application.environment.config)

        fun fromConfig(config: ApplicationConfig): ExkesConfig = ExkesConfig(
            database = DatabaseConfig(
                jdbcUrl = config.property("exkes.database.jdbcUrl").getString(),
                user = config.property("exkes.database.user").getString(),
                password = config.property("exkes.database.password").getString(),
            ),
            workspace = WorkspaceConfig(
                storageClass = config.property("exkes.workspace.storageClass").getString(),
                defaultStorageSizeBytes = config.property("exkes.workspace.defaultStorageSizeBytes")
                    .getString().toLong(),
            ),
        )
    }
}
