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

    override fun load(): Result<CacheSnapshot?> = runCatching {
        if (!file.isFile) return@runCatching null
        GSON.fromJson(file.readText(Charsets.UTF_8), CacheSnapshot::class.java)
    }

    override fun save(snapshot: CacheSnapshot): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        tempFile.writeText(GSON.toJson(snapshot), Charsets.UTF_8)
        if (!tempFile.renameTo(file)) {
            throw IllegalStateException("cannot atomically replace ${file.absolutePath}")
        }
    }

    override fun recoverLoadFailure(): Result<Unit> = runCatching {
        if (!file.isFile) return@runCatching
        val backup = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
        if (!file.renameTo(backup) && !file.delete()) {
            throw IllegalStateException("cannot quarantine corrupt snapshot ${file.absolutePath}")
        }
    }
}
