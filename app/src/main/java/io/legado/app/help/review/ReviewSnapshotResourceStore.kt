package io.legado.app.help.review

import android.util.AtomicFile
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

object ReviewSnapshotResourceStore {

    const val DATABASE_FILE_NAME = "resources.json"
    const val RESOURCE_SCHEME = "review-resource"

    private const val BLOB_PREFIX = "rr_"
    private const val BLOB_SUFFIX = ".bin"
    private const val COPY_BUFFER_BYTES = 32 * 1024
    private val keyPattern = Regex("[0-9a-f]{64}")

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

    /** Returns every valid URL entry. Broken indexes are exposed instead of ignored. */
    fun entries(book: Book): Map<String, ReviewSnapshotResourceEntry> = synchronized(lock) {
        val dir = ReviewSnapshotStore.reviewsDir(book)
        val database = readDatabase(dir)
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
        val dir = ReviewSnapshotStore.reviewsDir(book)
        check(dir.exists() || dir.mkdirs()) { "无法创建评论资源目录: ${dir.absolutePath}" }

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
        val old = readDatabase(dir)
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
        val database = readDatabase(dir)
        val file = blobFile(dir, key)
        if (!file.isFile) return null
        val entry = database.resources.firstOrNull { it.key == key }
        if (entry != null) {
            validateEntry(dir, entry)
        }
        ReviewSnapshotResourceHandle(entry?.mimeType ?: guessMime(file), FileInputStream(file))
    }

    fun copyAllTo(book: Book, targetDir: File) = synchronized(lock) {
        val sourceDir = ReviewSnapshotStore.reviewsDir(book)
        val database = databaseFile(sourceDir)
        if (!database.isFile) return@synchronized
        val index = readDatabase(sourceDir)
        index.resources.forEach { validateEntry(sourceDir, it) }
        copyFile(database, File(targetDir, DATABASE_FILE_NAME))
        resourceFiles(sourceDir).forEach { source ->
            copyFile(source, File(targetDir, source.name))
        }
    }

    /** Restores the index and every referenced blob from a TXT-ZIP extraction. */
    fun importFrom(book: Book, extractedFiles: List<File>) = synchronized(lock) {
        val indexFile = extractedFiles.firstOrNull { it.name == DATABASE_FILE_NAME } ?: return@synchronized
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
