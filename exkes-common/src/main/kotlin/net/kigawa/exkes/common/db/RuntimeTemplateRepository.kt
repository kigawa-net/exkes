package net.kigawa.exkes.common.db

import java.time.Instant
import kotlinx.serialization.json.Json
import net.kigawa.exkes.common.domain.RuntimeTemplate
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * CRUD for runtime_templates.
 *
 * Templates are managed by the controller (ConfigMap or DB), not by the API.
 * This repository is used by the controller to look up templates by ID.
 */
class RuntimeTemplateRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(
        id: String,
        name: String,
        description: String?,
        image: String,
        defaultResourcesJson: String?,
        defaultEnvJson: String?,
        defaultTtlSeconds: Long?,
        labelsJson: String?,
    ): RuntimeTemplate = transaction {
        val now = nowUtc()
        RuntimeTemplatesTable.insert {
            it[RuntimeTemplatesTable.id] = id
            it[RuntimeTemplatesTable.name] = name
            it[RuntimeTemplatesTable.description] = description
            it[RuntimeTemplatesTable.image] = image
            it[RuntimeTemplatesTable.defaultResourcesJson] = defaultResourcesJson
            it[RuntimeTemplatesTable.defaultEnvJson] = defaultEnvJson
            it[RuntimeTemplatesTable.defaultTtlSeconds] = defaultTtlSeconds
            it[RuntimeTemplatesTable.labelsJson] = labelsJson
            it[RuntimeTemplatesTable.createdAt] = now
            it[RuntimeTemplatesTable.updatedAt] = now
        }
        findByIdOrThrow(id)
    }

    fun findById(id: String): RuntimeTemplate? = transaction {
        RuntimeTemplatesTable.selectAll().where { RuntimeTemplatesTable.id eq id }
            .singleOrNull()
            ?.toTemplate()
    }

    fun list(limit: Int, offset: Int): List<RuntimeTemplate> = transaction {
        RuntimeTemplatesTable.selectAll()
            .orderBy(RuntimeTemplatesTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toTemplate() }
    }

    fun update(
        id: String,
        name: String?,
        description: String?,
        image: String?,
        defaultResourcesJson: String?,
        defaultEnvJson: String?,
        defaultTtlSeconds: Long?,
        labelsJson: String?,
    ): RuntimeTemplate? = transaction {
        val now = nowUtc()
        val updated = RuntimeTemplatesTable.update({ RuntimeTemplatesTable.id eq id }) { stmt ->
            name?.let { stmt[RuntimeTemplatesTable.name] = it }
            description?.let { stmt[RuntimeTemplatesTable.description] = it }
            image?.let { stmt[RuntimeTemplatesTable.image] = it }
            defaultResourcesJson?.let { stmt[RuntimeTemplatesTable.defaultResourcesJson] = it }
            defaultEnvJson?.let { stmt[RuntimeTemplatesTable.defaultEnvJson] = it }
            defaultTtlSeconds?.let { stmt[RuntimeTemplatesTable.defaultTtlSeconds] = it }
            labelsJson?.let { stmt[RuntimeTemplatesTable.labelsJson] = it }
            stmt[RuntimeTemplatesTable.updatedAt] = now
        }
        if (updated > 0) findById(id) else null
    }

    private fun findByIdOrThrow(id: String): RuntimeTemplate =
        findById(id) ?: throw IllegalStateException("runtime template vanished after insert: $id")

    private fun org.jetbrains.exposed.sql.ResultRow.toTemplate(): RuntimeTemplate = RuntimeTemplate(
        id = this[RuntimeTemplatesTable.id],
        name = this[RuntimeTemplatesTable.name],
        description = this[RuntimeTemplatesTable.description],
        image = this[RuntimeTemplatesTable.image],
        defaultResourcesJson = this[RuntimeTemplatesTable.defaultResourcesJson],
        defaultEnvJson = this[RuntimeTemplatesTable.defaultEnvJson],
        defaultTtlSeconds = this[RuntimeTemplatesTable.defaultTtlSeconds],
        labelsJson = this[RuntimeTemplatesTable.labelsJson],
        createdAt = this[RuntimeTemplatesTable.createdAt],
        updatedAt = this[RuntimeTemplatesTable.updatedAt],
    )

    companion object {
        fun nowUtc(): Instant = Instant.now()
    }
}