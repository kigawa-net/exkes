package net.kigawa.exkes.common

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.TerminalSessionRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.TerminalSessionStatus
import net.kigawa.exkes.common.domain.TerminalSessionToken

/**
 * TerminalSessionRepository: hashed-only storage, single-use consumption, expiry.
 * H2 (MODE=MySQL) with the Flyway-created schema.
 */
class TerminalSessionRepositoryTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: TerminalSessionRepository
    private lateinit var runtimeId: String

    private val issuedAt: Instant = Instant.parse("2026-10-26T00:00:00Z")
    private val expiresAt: Instant = issuedAt.plusSeconds(300)

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_terminal_repo_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = TerminalSessionRepository()

        RuntimeTemplateRepository().create(
            id = "terminal-template",
            name = "Terminal Template",
            description = null,
            image = "example/image:latest",
            defaultResourcesJson = null,
            defaultEnvJson = null,
            defaultTtlSeconds = null,
            labelsJson = null,
        )
        val workspace = WorkspaceRepository().create(
            name = "terminal-ws-${System.nanoTime()}",
            storageClass = "rook-cephfs",
            storageSizeBytes = 1024L * 1024 * 1024,
            labels = emptyMap(),
        )
        runtimeId = RuntimeRepository().create(
            workspaceId = workspace.id,
            templateId = "terminal-template",
            resourcesJson = null,
            envJson = null,
            ttlSeconds = null,
            labelsJson = null,
        ).id
    }

    private fun issue(token: String = TerminalSessionToken.generate()): Pair<String, net.kigawa.exkes.common.domain.TerminalSession> {
        val session = repository.create(
            runtimeId = runtimeId,
            tokenHash = TerminalSessionToken.hash(token),
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )
        return token to session
    }

    @Test
    fun `create records an ISSUED session and never stores the plaintext token`() {
        val (token, session) = issue()

        assertEquals(TerminalSessionStatus.ISSUED, session.status)
        assertEquals(runtimeId, session.runtimeId)
        assertEquals(expiresAt, session.expiresAt)
        assertNull(session.consumedAt)

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM terminal_sessions").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(TerminalSessionToken.hash(token), rs.getString("token_hash"))
                    assertEquals(64, rs.getString("token_hash").length)
                    // No column in the row may contain the plaintext.
                    for (column in 1..rs.metaData.columnCount) {
                        val value = rs.getString(column)
                        assertTrue(value == null || !value.contains(token), "column ${rs.metaData.getColumnName(column)} leaks the token")
                    }
                    assertTrue(!rs.next(), "exactly one row expected")
                }
            }
        }
    }

    @Test
    fun `consume returns the session as CONSUMED`() {
        val (token, session) = issue()

        val consumed = repository.consume(token, issuedAt.plusSeconds(10))

        assertNotNull(consumed)
        assertEquals(session.id, consumed.id)
        assertEquals(TerminalSessionStatus.CONSUMED, consumed.status)
        assertEquals(issuedAt.plusSeconds(10), consumed.consumedAt)
    }

    @Test
    fun `a token can be consumed only once`() {
        val (token, _) = issue()

        assertNotNull(repository.consume(token, issuedAt.plusSeconds(1)))
        assertNull(repository.consume(token, issuedAt.plusSeconds(2)), "second consume must be rejected")
    }

    @Test
    fun `an unknown token is rejected`() {
        issue()

        assertNull(repository.consume(TerminalSessionToken.generate(), issuedAt.plusSeconds(1)))
    }

    @Test
    fun `a token one second before expiry can still be consumed`() {
        val (token, _) = issue()

        assertNotNull(repository.consume(token, expiresAt.minusSeconds(1)))
    }

    @Test
    fun `an expired token cannot be consumed and its session is marked EXPIRED`() {
        val (token, session) = issue()

        // expires_at is exclusive: at exactly expiresAt the token is already expired.
        assertNull(repository.consume(token, expiresAt))
        assertEquals(TerminalSessionStatus.EXPIRED, repository.findById(session.id)?.status)
        assertNull(repository.consume(token, issuedAt.plusSeconds(1)), "expired stays unusable even with an earlier clock")
    }

    @Test
    fun `an expired token is rejected long after expiry`() {
        val (token, _) = issue()

        assertNull(repository.consume(token, expiresAt.plusSeconds(3600)))
    }

    @Test
    fun `concurrent consumers of one token produce exactly one winner`() {
        val (token, _) = issue()
        val parties = 8
        val pool = Executors.newFixedThreadPool(parties)
        try {
            val start = CountDownLatch(1)
            val results = (1..parties).map {
                pool.submit<net.kigawa.exkes.common.domain.TerminalSession?> {
                    start.await()
                    repository.consume(token, issuedAt.plusSeconds(1))
                }
            }
            start.countDown()
            val winners = results.mapNotNull { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, winners.size, "exactly one consumer must win")
        } finally {
            pool.shutdownNow()
        }
    }
}
