package net.kigawa.exkes.common

import javax.sql.DataSource
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.createDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Applies the Flyway migrations to H2 (MODE=MySQL) and verifies that the DDL
 * stays compatible with both MariaDB and H2 (plan chapter 5.2).
 */
class MigrationH2Test {
    private fun withMigratedDatabase(block: (DataSource, MigrateResult) -> Unit) {
        val dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_migration_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
            .migrate()
        block(dataSource, result)
    }

    @Test
    fun `flyway applies v1 workspaces migration on h2`() = withMigratedDatabase { _, result ->
        assertEquals(3, result.migrationsExecuted)
    }

    @Test
    fun `workspaces table has every column defined by the plan`() = withMigratedDatabase { dataSource, _ ->
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT id, name, status, storage_class, storage_size,
                           pvc_name, error_message, labels_json,
                           archived_at, created_at, updated_at
                    FROM workspaces
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next().not(), "fresh workspaces table must be empty")
                }
            }
        }
    }

    @Test
    fun `workspaces rows can be inserted and read back`() = withMigratedDatabase { dataSource, _ ->
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    INSERT INTO workspaces
                        (id, name, status, storage_class, storage_size, created_at, updated_at)
                    VALUES
                        ('3f0f3b1a-8e52-4a6e-9a41-1c1a2b3c4d5e', 'migration-test', 'CREATING',
                         'rook-cephfs', 1073741824, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                )
                val rs = stmt.executeQuery("SELECT status, storage_size FROM workspaces")
                assertTrue(rs.next())
                assertEquals("CREATING", rs.getString("status"))
                assertEquals(1073741824L, rs.getLong("storage_size"))
            }
        }
    }

    @Test
    fun `workspaces name must be unique`() = withMigratedDatabase { dataSource, _ ->
        val insert = """
            INSERT INTO workspaces (id, name, status, created_at, updated_at)
            VALUES (?, 'duplicate-name', 'CREATING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(insert).use { stmt ->
                stmt.setString(1, "00000000-0000-0000-0000-000000000001")
                stmt.executeUpdate()
                stmt.setString(1, "00000000-0000-0000-0000-000000000002")
                val thrown = runCatching { stmt.executeUpdate() }.exceptionOrNull()
                assertTrue(thrown != null, "duplicate name must violate uk_workspaces_name")
            }
        }
    }

    @Test
    fun `status index exists`() = withMigratedDatabase { dataSource, _ ->
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.indexes " +
                        "WHERE table_name = 'WORKSPACES' AND index_name = 'IDX_WORKSPACES_STATUS'",
                )
                assertTrue(rs.next())
                assertTrue(rs.getInt(1) >= 1, "idx_workspaces_status must exist")
            }
        }
    }
}
