package net.kigawa.exkes.common.db

import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Result of an idempotent create: [replayed] is true when the value came from an earlier request. */
data class IdempotentResult<T>(val value: T, val replayed: Boolean)

/** The same Idempotency-Key was already used with a different request body. */
class IdempotencyKeyMismatchException :
    RuntimeException("Idempotency-Key was already used with a different request")

/**
 * Binds an `Idempotency-Key` to the resource created by the first request.
 *
 * [execute] runs the check, the create and the key insert in one transaction, so a
 * key is never recorded without its resource (or the other way round). Two
 * concurrent requests with the same key race on the primary key of
 * `idempotency_keys`; the loser rolls back and is retried once, where it finds the
 * winner's row and replays it. A retry is also used when the race is on a unique
 * resource name, because the loser may have hit the name constraint first.
 */
class IdempotencyRepository {

    fun <T : Any> execute(
        scope: String,
        key: String,
        requestDigest: String,
        load: (resourceId: String) -> T?,
        create: () -> T,
        idOf: (T) -> String,
    ): IdempotentResult<T> {
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return transaction {
                    val existing = IdempotencyKeysTable.selectAll().where {
                        (IdempotencyKeysTable.scope eq scope) and (IdempotencyKeysTable.idempotencyKey eq key)
                    }.singleOrNull()

                    if (existing != null) {
                        if (existing[IdempotencyKeysTable.requestDigest] != requestDigest) {
                            throw IdempotencyKeyMismatchException()
                        }
                        val resourceId = existing[IdempotencyKeysTable.resourceId]
                        val value = load(resourceId)
                            ?: throw IllegalStateException("idempotent resource vanished: $resourceId")
                        return@transaction IdempotentResult(value, replayed = true)
                    }

                    val created = create()
                    IdempotencyKeysTable.insert {
                        it[IdempotencyKeysTable.scope] = scope
                        it[IdempotencyKeysTable.idempotencyKey] = key
                        it[IdempotencyKeysTable.requestDigest] = requestDigest
                        it[IdempotencyKeysTable.resourceId] = idOf(created)
                        it[IdempotencyKeysTable.createdAt] = ExecutionRepository.nowUtc()
                    }
                    IdempotentResult(created, replayed = false)
                }
            } catch (e: ExposedSQLException) {
                if (attempt == MAX_ATTEMPTS) throw e
            } catch (e: DuplicateWorkspaceNameException) {
                if (attempt == MAX_ATTEMPTS) throw e
            }
        }
        error("unreachable")
    }

    companion object {
        private const val MAX_ATTEMPTS = 2

        /** SHA-256 (hex) of the compact JSON form of a canonical request description. */
        fun digestOf(canonical: JsonElement): String =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
