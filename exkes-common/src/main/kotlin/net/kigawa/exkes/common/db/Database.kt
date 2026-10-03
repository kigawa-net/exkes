package net.kigawa.exkes.common.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import net.kigawa.exkes.common.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Creates a HikariCP pool. The JDBC driver is inferred from the URL so the
 * same code path works for MariaDB (production) and H2 (tests).
 */
fun createDataSource(config: DatabaseConfig): DataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.user
        password = config.password
        driverClassName = driverFor(config.jdbcUrl)
    }
    return HikariDataSource(hikariConfig)
}

private fun driverFor(jdbcUrl: String): String =
    if (jdbcUrl.startsWith("jdbc:h2")) "org.h2.Driver" else "org.mariadb.jdbc.Driver"

/**
 * Applies classpath:db/migration.
 *
 * Both exkes-api and exkes-controller call this at startup: they are separate
 * Deployments, so whichever comes up first has to be able to serve instead of
 * failing on a missing table. Flyway serializes concurrent runs on
 * `flyway_schema_history` (plan 3.2). If that ever becomes a problem the fix is
 * a dedicated migration job, not "remove it from one side".
 */
fun runMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate()
}

fun connectDatabase(dataSource: DataSource): Database = Database.connect(dataSource)

/**
 * Closes the pool created by createDataSource. javax.sql.DataSource is not
 * Closeable, so the cast to HikariDataSource stays inside this module.
 */
fun closeDataSource(dataSource: DataSource) {
    (dataSource as? HikariDataSource)?.close()
}
