package io.legado.app.help.cache

import io.legado.app.utils.GSON
import splitties.init.appCtx
import java.io.File

internal interface CacheTaskPersistence {
    fun load(): Result<CacheSnapshot?>
    fun save(snapshot: CacheSnapshot): Result<Unit>
    fun recoverLoadFailure(): Result<Unit>
}

internal class InMemoryCacheTaskPersistence(
    initial: CacheSnapshot? = null,
) : CacheTaskPersistence {
    var snapshot: CacheSnapshot? = initial
        private set

    override fun load(): Result<CacheSnapshot?> = Result.success(snapshot)

    override fun save(snapshot: CacheSnapshot): Result<Unit> {
        this.snapshot = snapshot
        return Result.success(Unit)
    }

    override fun recoverLoadFailure(): Result<Unit> = Result.success(Unit)
}

/** Small atomic JSON snapshot. It is intentionally replaceable in tests and by a future Room store. */
internal object AppFileCacheTaskPersistence : CacheTaskPersistence {
    private val file by lazy { File(appCtx.filesDir, "cache_tasks.json") }
    private val tempFile by lazy { File(appCtx.filesDir, "cache_tasks.json.tmp") }

    override fun load(): Result<CacheSnapshot?> {
        val trace = CacheOperationDiagnostics.begin(
            CacheOperationDiagnostics.Context(domain = CacheOperationDiagnostics.Domain.STORE),
            "SNAPSHOT_LOAD",
            CacheOperationDiagnostics.Metrics(inputBytes = file.takeIf { it.isFile }?.length()),
        )
        return runCatching {
            if (!file.isFile) return@runCatching null
            GSON.fromJson(file.readText(Charsets.UTF_8), CacheSnapshot::class.java)
        }.onSuccess { snapshot ->
            trace.done(snapshot.metrics(file.length()))
        }.onFailure { error ->
            trace.fail(error)
        }
    }

    override fun save(snapshot: CacheSnapshot): Result<Unit> {
        val trace = CacheOperationDiagnostics.begin(
            CacheOperationDiagnostics.Context(domain = CacheOperationDiagnostics.Domain.STORE),
            "SNAPSHOT_PERSIST",
            snapshot.metrics(),
            startAlways = true,
        )
        return runCatching {
            file.parentFile?.mkdirs()
            tempFile.writeText(GSON.toJson(snapshot), Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                throw IllegalStateException("cannot atomically replace ${file.absolutePath}")
            }
        }.onSuccess {
            trace.done(snapshot.metrics(file.length()))
        }.onFailure { error ->
            trace.fail(error, snapshot.metrics())
        }
    }

    override fun recoverLoadFailure(): Result<Unit> = runCatching {
        if (!file.isFile) return@runCatching
        val backup = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
        if (!file.renameTo(backup) && !file.delete()) {
            throw IllegalStateException("cannot quarantine corrupt snapshot ${file.absolutePath}")
        }
    }

    private fun CacheSnapshot?.metrics(outputBytes: Long? = null): CacheOperationDiagnostics.Metrics {
        val sessions = this?.sessions.orEmpty()
        return CacheOperationDiagnostics.Metrics(
            outputBytes = outputBytes,
            sessionCount = sessions.size,
            taskCount = sessions.sumOf { it.tasks.size },
            unitCount = sessions.sumOf { session -> session.tasks.sumOf { it.units.size } },
            persisted = outputBytes != null,
        )
    }
}
