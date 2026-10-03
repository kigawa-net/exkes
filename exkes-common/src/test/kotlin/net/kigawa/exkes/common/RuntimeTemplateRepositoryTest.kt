package net.kigawa.exkes.common

import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.RuntimeTemplate

class RuntimeTemplateRepositoryTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: RuntimeTemplateRepository

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_template_repo_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = RuntimeTemplateRepository()
    }

    private fun createTemplate(id: String = "runtime-${System.nanoTime()}"): RuntimeTemplate =
        repository.create(
            id = id,
            name = "Test Template",
            description = "A test template",
            image = "ghcr.io/kigawa/runtime-agent:latest",
            defaultResourcesJson = """{"cpu":"500m","memory":"512Mi"}""",
            defaultEnvJson = """{"ENV_VAR":"value"}""",
            defaultTtlSeconds = 3600L,
            labelsJson = """{"app":"exkes"}""",
        )

    @Test
    fun `create and find template`() {
        val template = createTemplate("runtime-test-create")

        assertEquals("runtime-test-create", template.id)
        assertEquals("Test Template", template.name)
        assertEquals("ghcr.io/kigawa/runtime-agent:latest", template.image)
        assertEquals(3600L, template.defaultTtlSeconds)

        val reloaded = repository.findById(template.id)
        assertNotNull(reloaded)
        assertEquals(template.id, reloaded.id)
    }

    @Test
    fun `list returns templates ordered by creation time descending`() {
        val first = createTemplate("runtime-first")
        val second = createTemplate("runtime-second")

        val list = repository.list(limit = 100, offset = 0)

        assertEquals(listOf(second.id, first.id), list.map { it.id })
    }

    @Test
    fun `update modifies template fields`() {
        val template = createTemplate("runtime-update")

        val updated = repository.update(
            id = template.id,
            name = "Updated Name",
            description = "Updated description",
            image = "ghcr.io/kigawa/runtime-agent:v2",
            defaultResourcesJson = """{"cpu":"1000m","memory":"1Gi"}""",
            defaultEnvJson = """{"NEW_VAR":"new"}""",
            defaultTtlSeconds = 7200L,
            labelsJson = """{"updated":"true"}""",
        )

        assertNotNull(updated)
        assertEquals("Updated Name", updated!!.name)
        assertEquals("ghcr.io/kigawa/runtime-agent:v2", updated.image)
        assertEquals(7200L, updated.defaultTtlSeconds)

        val reloaded = repository.findById(template.id)
        assertEquals("Updated Name", reloaded!!.name)
    }

    @Test
    fun `update returns null for non-existent id`() {
        val updated = repository.update(
            id = "non-existent",
            name = "X",
            description = null,
            image = null,
            defaultResourcesJson = null,
            defaultEnvJson = null,
            defaultTtlSeconds = null,
            labelsJson = null,
        )
        assertNull(updated)
    }
}