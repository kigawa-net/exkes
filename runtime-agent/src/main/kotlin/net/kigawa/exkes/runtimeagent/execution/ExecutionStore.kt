package net.kigawa.exkes.runtimeagent.execution

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Persists execution metadata (not logs) so a restarted agent can tell what was in flight. */
interface ExecutionStore {
    fun save(snapshot: ExecutionSnapshot)
    fun loadAll(): List<ExecutionSnapshot>
}

class InMemoryExecutionStore : ExecutionStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ExecutionSnapshot>()
    override fun save(snapshot: ExecutionSnapshot) { map[snapshot.id] = snapshot }
    override fun loadAll(): List<ExecutionSnapshot> = map.values.toList()
}

/** One `<id>.json` per execution, written atomically (tmp + rename). */
class FileExecutionStore(private val dir: Path) : ExecutionStore {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        Files.createDirectories(dir)
    }

    override fun save(snapshot: ExecutionSnapshot) {
        val target = dir.resolve("${snapshot.id}.json")
        val tmp = dir.resolve("${snapshot.id}.json.tmp")
        Files.writeString(tmp, json.encodeToString(ExecutionSnapshot.serializer(), snapshot))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun loadAll(): List<ExecutionSnapshot> =
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }.toList()
        }.mapNotNull {
            runCatching { json.decodeFromString(ExecutionSnapshot.serializer(), Files.readString(it)) }.getOrNull()
        }
}
