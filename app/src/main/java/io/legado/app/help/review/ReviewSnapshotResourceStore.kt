package io.legado.app.help.review

import android.util.AtomicFile
import com.google.gson.stream.JsonReader
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Per-book resource database for review snapshots.
 *
 * The JSON file is an index only. Image bytes stay in content-addressed files so a
 * single metadata read never creates another giant Base64/String copy on the Java heap.
 * Snapshot HTML refers to a stable [RESOURCE_SCHEME] key; several source URLs can point
 * at the same content-addressed resource file.
 */
data class ReviewSnapshotResourceDatabase(
    val version: Int = 1,
    val resources: List<ReviewSnapshotResourceEntry> = emptyList(),
)

data class ReviewSnapshotResourceEntry(
    val url: String = "",
    val key: String = "",
    val mimeType: String = "",
    val byteCount: Long = 0L,
)

data class ReviewSnapshotResourceHandle(
    val mimeType: String,
    val inputStream: InputStream,
)

/** Result of a garbage-collection pass over one book's review resource library. */
data class ReviewResourceGcResult(
    val aborted: Boolean = false,
    val scannedSnapshots: Int = 0,
    val scannedBlobs: Int = 0,
    val referencedKeys: Int = 0,
    val removedBlobs: Int = 0,
    val removedEntries: Int = 0,
    val removedBytes: Long = 0L,
)

object ReviewSnapshotResourceStore {

    const val DATABASE_FILE_NAME = "resources.json"
    const val RESOURCE_SCHEME = "review-resource"

    private const val BLOB_PREFIX = "rr_"
    private const val BLOB_SUFFIX = ".bin"
    private const val COPY_BUFFER_BYTES = 32 * 1024
    private val keyPattern = Regex("[0-9a-f]{64}")
    private val referencePattern = Regex("$RESOURCE_SCHEME://([0-9a-f]{64})")

    /**
     * Heavy capture is currently globally serialized, but import/export and WebView
     * interception are not. Keep index replacement and blob publication coherent.
     */
    private val lock = Any()

    fun referenceFor(key: String): String {
        require(keyPattern.matches(key)) { "非法评论资源键: $key" }
        return "$RESOURCE_SCHEME://$key"
    }

    fun keyFromReference(url: String): String? {
        val prefix = "$RESOURCE_SCHEME://"
        val key = url.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.substringBefore('/')
            ?: return null
        return key.takeIf(keyPattern::matches)
    }

    /**
     * Creates the empty index before a new review capture starts. An older review
     * payload without this index is an unsupported format, not an empty library.
     */
    fun prepareForCapture(book: Book) = synchronized(lock) {
        val dir = ReviewSnapshotStore.reviewsDir(book)
        check(dir.exists() || dir.mkdirs()) {
            "无法创建评论资源目录: ${dir.absolutePath}"
        }
        val database = databaseFile(dir)
        if (database.isFile) {
            readDatabase(dir)
            return@synchronized
        }
        check(!ReviewSnapshotStore.hasPersistedReviewData(book)) {
            "不支持没有 $DATABASE_FILE_NAME 的旧评论缓存: ${dir.absolutePath}"
        }
        writeDatabase(dir, ReviewSnapshotResourceDatabase())
    }

    /** Returns the validated index for an existing current-format review cache. */
    fun requireDatabase(book: Book): ReviewSnapshotResourceDatabase = synchronized(lock) {
        val dir = ReviewSnapshotStore.reviewsDir(book)
        val database = databaseFile(dir)
        check(database.isFile) {
            "评论缓存缺少 $DATABASE_FILE_NAME，旧的非资源库格式不受支持: ${dir.absolutePath}"
        }
        return@synchronized readDatabase(dir)
    }

    /** Returns every valid URL entry. Broken indexes are exposed instead of ignored. */
    fun entries(book: Book): Map<String, ReviewSnapshotResourceEntry> = synchronized(lock) {
        val dir = ReviewSnapshotStore.reviewsDir(book)
        val database = requireDatabase(book)
        database.resources.associateBy { entry ->
            validateEntry(dir, entry)
            entry.url
        }.also { entries ->
            check(entries.size == database.resources.size) {
                "评论资源数据库包含重复 URL: ${databaseFile(dir).absolutePath}"
            }
        }
    }

    /**
     * Publishes [source] under its SHA-256 key and updates the URL index atomically.
     * The caller retains ownership of [source].
     */
    fun put(
        book: Book,
        url: String,
        mimeType: String,
        source: File,
    ): ReviewSnapshotResourceEntry = synchronized(lock) {
        require(url.isNotBlank()) { "评论资源 URL 为空" }
        require(mimeType.isNotBlank()) { "评论资源 MIME 为空: $url" }
        check(source.isFile) { "评论资源暂存文件不存在: ${source.absolutePath}" }
        prepareForCapture(book)
        val dir = ReviewSnapshotStore.reviewsDir(book)

        val key = sha256(source)
        val target = blobFile(dir, key)
        if (target.exists()) {
            check(target.isFile && target.length() == source.length()) {
                "评论资源键冲突或文件损坏: ${target.absolutePath}"
            }
        } else {
            copyAtomically(source, target)
        }

        val entry = ReviewSnapshotResourceEntry(
            url = url,
            key = key,
            mimeType = mimeType,
            byteCount = source.length(),
        )
        val old = requireDatabase(book)
        val updated = old.resources
            .filterNot { it.url == url }
            .plus(entry)
        writeDatabase(dir, ReviewSnapshotResourceDatabase(resources = updated))
        entry
    }

    /** Opens a resource referenced by review-resource://<sha256>. */
    fun open(book: Book, key: String): ReviewSnapshotResourceHandle? = synchronized(lock) {
        if (!keyPattern.matches(key)) return null
        val dir = ReviewSnapshotStore.reviewsDir(book)
        val database = requireDatabase(book)
        val file = blobFile(dir, key)
        if (!file.isFile) return null
        val entry = database.resources.firstOrNull { it.key == key }
        if (entry != null) {
            validateEntry(dir, entry)
        }
        ReviewSnapshotResourceHandle(entry?.mimeType ?: guessMime(file), FileInputStream(file))
    }

    fun copyAllTo(book: Book, targetDir: File) = synchronized(lock) {
        if (!ReviewSnapshotStore.hasPersistedReviewData(book)) return@synchronized
        val sourceDir = ReviewSnapshotStore.reviewsDir(book)
        val database = databaseFile(sourceDir)
        val index = requireDatabase(book)
        index.resources.forEach { validateEntry(sourceDir, it) }
        copyFile(database, File(targetDir, DATABASE_FILE_NAME))
        resourceFiles(sourceDir).forEach { source ->
            copyFile(source, File(targetDir, source.name))
        }
    }

    /** Restores the index and every referenced blob from a TXT-ZIP extraction. */
    fun importFrom(book: Book, extractedFiles: List<File>) = synchronized(lock) {
        val snapshotFiles = extractedFiles.filter(ReviewSnapshotStore::isSnapshotFile)
        val statusFiles = extractedFiles.filter(ReviewSnapshotStore::isChapterStatusFile)
        val containsReviewData = snapshotFiles.isNotEmpty() || statusFiles.isNotEmpty()
        if (!containsReviewData) return@synchronized
        check(snapshotFiles.isEmpty() || statusFiles.isNotEmpty()) {
            "导入评论缓存缺少章节状态文件，旧格式不受支持"
        }
        val indexFile = extractedFiles.firstOrNull { it.name == DATABASE_FILE_NAME }
            ?: error("导入评论缓存缺少 $DATABASE_FILE_NAME，旧的非资源库格式不受支持")
        val imported = readDatabaseFile(indexFile)
        val sourceByName = extractedFiles.associateBy { it.name }
        val targetDir = ReviewSnapshotStore.reviewsDir(book)
        check(targetDir.exists() || targetDir.mkdirs()) {
            "无法创建导入评论资源目录: ${targetDir.absolutePath}"
        }
        imported.resources.forEach { entry ->
            validateEntry(indexFile.parentFile ?: targetDir, entry, requireBlob = false)
            val source = sourceByName[blobName(entry.key)]
                ?: error("导入评论资源缺失: ${blobName(entry.key)}")
            check(source.isFile && source.length() == entry.byteCount) {
                "导入评论资源长度异常: ${source.absolutePath}"
            }
            val target = blobFile(targetDir, entry.key)
            if (target.exists()) {
                check(target.isFile && target.length() == entry.byteCount) {
                    "导入评论资源与现有文件冲突: ${target.absolutePath}"
                }
            } else {
                copyAtomically(source, target)
            }
        }
        extractedFiles.filter(::isResourceBlob).forEach { source ->
            val target = File(targetDir, source.name)
            if (target.exists()) {
                check(target.isFile && target.length() == source.length()) {
                    "导入评论资源与现有文件冲突: ${target.absolutePath}"
                }
            } else {
                copyAtomically(source, target)
            }
        }
        val existing = readDatabase(targetDir)
        val importedUrls = imported.resources.mapTo(hashSetOf()) { it.url }
        writeDatabase(
            targetDir,
            ReviewSnapshotResourceDatabase(
                resources = existing.resources.filterNot { it.url in importedUrls } + imported.resources
            )
        )
    }

    /**
     * 回收本轮全书 REVIEW 缓存结束后没有任何快照引用的孤儿资源。
     *
     * 语义：
     * - 存活标准 = 至少一个快照 HTML 的 review-resource:// 引用；
     * - 索引中无引用的条目（含同 key 多个来源 URL）、以及磁盘上无引用的
     *   rr_<key>.bin 一并删除；
     * - 先重写索引、后删 blob：中途失败只会留下“无索引的孤儿 blob”，
     *   下次 GC 自愈，绝不会出现“索引在而文件缺失”的损坏态。
     *
     * 性能：引用扫描在锁外进行（只读快照，REVIEW 结束后无并发写入）；
     * 持锁窗口只有“读索引 + 重写索引 + 删 blob”，不影响其他书的 put/open。
     * 必须在确认本书没有正在运行的 REVIEW task 之后由调用方触发
     * （例如 CacheCoordinator 的终态钩子）。
     *
     * 安全：[canProceed] 在持锁删除前再次求值。若扫描期间又有新的 REVIEW
     * task 入队并开始 put 资源（扫描结果因此过期），返回 false 则本次 GC
     * 中止、不删任何文件；稍后该新任务的终态会再次触发 GC。
     */
    fun gc(book: Book, canProceed: () -> Boolean = { true }): ReviewResourceGcResult {
        val dir = ReviewSnapshotStore.reviewsDir(book)
        if (!ReviewSnapshotStore.hasPersistedReviewData(book)) {
            return ReviewResourceGcResult()
        }
        val referenced = collectReferencedKeys(dir)
        return synchronized(lock) {
            val database = requireDatabase(book)
            if (database.resources.isEmpty() && resourceFiles(dir).isEmpty()) {
                return@synchronized ReviewResourceGcResult()
            }
            if (!canProceed()) {
                return@synchronized ReviewResourceGcResult(aborted = true)
            }
            // 存活标准统一为“至少一个快照引用”，与索引是否存在无关：
            // 索引条目只服务于复用与 MIME 推断，快照引用才是真实使用权。
            val removedEntries = database.resources
                .filter { it.key !in referenced }
            val removedKeys = removedEntries.mapTo(hashSetOf()) { it.key }
            val retained = database.resources.filterNot { it.key in removedKeys }
            val blobFiles = resourceFiles(dir)
            // 先重写索引：孤儿条目消失后，blob 删除失败只会留下“无索引的孤儿 blob”，
            // 下次 GC 自愈，绝不会出现“索引在而文件缺失”的损坏态。
            if (removedEntries.isNotEmpty()) {
                writeDatabase(dir, ReviewSnapshotResourceDatabase(resources = retained))
            }
            var removedBlobs = 0
            var removedBytes = 0L
            blobFiles.forEach { file ->
                val key = blobKeyOf(file) ?: return@forEach
                if (key !in referenced) {
                    removedBytes += file.length()
                    if (file.delete()) removedBlobs++
                }
            }
            ReviewResourceGcResult(
                scannedSnapshots = dir.listFiles()?.count(ReviewSnapshotStore::isSnapshotFile) ?: 0,
                scannedBlobs = blobFiles.size,
                referencedKeys = referenced.size,
                removedBlobs = removedBlobs,
                removedEntries = removedEntries.size,
                removedBytes = removedBytes,
            )
        }
    }

    private fun blobKeyOf(file: File): String? {
        if (!isResourceBlob(file)) return null
        return file.name.removePrefix(BLOB_PREFIX).removeSuffix(BLOB_SUFFIX)
    }

    private fun collectReferencedKeys(dir: File): Set<String> {
        val referenced = hashSetOf<String>()
        dir.listFiles()
            ?.filter(ReviewSnapshotStore::isSnapshotFile)
            .orEmpty()
            .forEach { file ->
                referenced.addAll(referencedKeysIn(file))
            }
        return referenced
    }

    private fun referencedKeysIn(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val keys = hashSetOf<String>()
                JsonReader(reader).use { json ->
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "html") {
                            referencePattern.findAll(json.nextString()).forEach { match ->
                                keys.add(match.groupValues[1])
                            }
                        } else {
                            json.skipValue()
                        }
                    }
                    json.endObject()
                }
                keys
            }
        }.getOrElse { emptySet() }
    }

    private fun readDatabase(dir: File): ReviewSnapshotResourceDatabase {
        val file = databaseFile(dir)
        if (!file.exists()) return ReviewSnapshotResourceDatabase()
        check(file.isFile) { "评论资源数据库不是文件: ${file.absolutePath}" }
        return file.bufferedReader(Charsets.UTF_8).use { reader ->
            GSON.fromJson(reader, ReviewSnapshotResourceDatabase::class.java)
                ?: error("评论资源数据库为空: ${file.absolutePath}")
        }.also(::validateDatabase)
    }

    private fun readDatabaseFile(file: File): ReviewSnapshotResourceDatabase {
        check(file.isFile) { "评论资源数据库不存在: ${file.absolutePath}" }
        return file.bufferedReader(Charsets.UTF_8).use { reader ->
            GSON.fromJson(reader, ReviewSnapshotResourceDatabase::class.java)
                ?: error("评论资源数据库为空: ${file.absolutePath}")
        }.also(::validateDatabase)
    }

    private fun validateDatabase(database: ReviewSnapshotResourceDatabase) {
        check(database.version == 1) { "不支持的评论资源数据库版本: ${database.version}" }
        val urls = hashSetOf<String>()
        database.resources.forEach { entry ->
            require(entry.url.isNotBlank()) { "评论资源数据库存在空 URL" }
            require(urls.add(entry.url)) { "评论资源数据库存在重复 URL: ${entry.url}" }
            require(keyPattern.matches(entry.key)) { "评论资源数据库存在非法资源键: ${entry.key}" }
            require(entry.mimeType.isNotBlank()) { "评论资源数据库存在空 MIME: ${entry.url}" }
            require(entry.byteCount >= 0L) { "评论资源数据库存在非法长度: ${entry.url}" }
        }
    }

    private fun validateEntry(dir: File, entry: ReviewSnapshotResourceEntry, requireBlob: Boolean = true) {
        validateDatabase(ReviewSnapshotResourceDatabase(resources = listOf(entry)))
        if (requireBlob) {
            val file = blobFile(dir, entry.key)
            check(file.isFile && file.length() == entry.byteCount) {
                "评论资源文件缺失或长度异常: ${file.absolutePath}"
            }
        }
    }

    private fun writeDatabase(dir: File, database: ReviewSnapshotResourceDatabase) {
        validateDatabase(database)
        val atomicFile = AtomicFile(databaseFile(dir))
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            GSON.toJson(database, writer)
            writer.flush()
            atomicFile.finishWrite(stream)
            output = null
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun copyAtomically(source: File, target: File) {
        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            source.inputStream().buffered().use { input ->
                input.copyTo(stream, COPY_BUFFER_BYTES)
            }
            atomicFile.finishWrite(stream)
            output = null
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun copyFile(source: File, target: File) {
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun databaseFile(dir: File): File = File(dir, DATABASE_FILE_NAME)

    private fun blobFile(dir: File, key: String): File = File(dir, blobName(key))

    private fun resourceFiles(dir: File): List<File> = dir.listFiles()
        ?.filter(::isResourceBlob)
        .orEmpty()

    private fun isResourceBlob(file: File): Boolean {
        val name = file.name
        return file.isFile && name.startsWith(BLOB_PREFIX) && name.endsWith(BLOB_SUFFIX) &&
            keyPattern.matches(name.removePrefix(BLOB_PREFIX).removeSuffix(BLOB_SUFFIX))
    }

    private fun guessMime(file: File): String {
        val header = ByteArray(512)
        val size = file.inputStream().use { input -> input.read(header) }
        return when {
            size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            size >= 8 && header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ) -> "image/png"
            size >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") ->
                "image/gif"
            size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> "image/jpeg"
            size > 0 && String(header, 0, size, Charsets.UTF_8).contains("<svg", true) ->
                "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun blobName(key: String): String {
        require(keyPattern.matches(key)) { "非法评论资源键: $key" }
        return "$BLOB_PREFIX$key$BLOB_SUFFIX"
    }
}
