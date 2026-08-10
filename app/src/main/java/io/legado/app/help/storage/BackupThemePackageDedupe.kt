package io.legado.app.help.storage

import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.io.File
import java.security.MessageDigest

/**
 * Deduplicates packaged theme fonts inside full app backups only.
 *
 * Theme packages themselves stay self-contained on disk and in WebDAV sync.
 */
object BackupThemePackageDedupe {

    const val themePackagesDirName = "themePackages"
    const val manifestFileName = "themePackageFontDedupe.json"

    private val fontExtensions = setOf("ttf", "otf", "ttc")
    private val fontPrefixes = arrayOf("ui_font", "title_font")

    fun prepareBackupThemePackages(sourceRoot: File, backupRoot: File): File? {
        val targetRoot = File(backupRoot, themePackagesDirName)
        val manifestFile = File(backupRoot, manifestFileName)
        FileUtils.delete(targetRoot, deleteRootDir = true)
        manifestFile.delete()
        if (!sourceRoot.exists() || !sourceRoot.isDirectory) {
            return null
        }

        val dedupeEntries = findDuplicateFonts(sourceRoot)
        val duplicatePaths = dedupeEntries.mapTo(hashSetOf()) { it.duplicatePath }
        copyThemePackages(sourceRoot, targetRoot, duplicatePaths)
        if (dedupeEntries.isNotEmpty()) {
            manifestFile.writeText(GSON.toJson(dedupeEntries))
        }
        return targetRoot
    }

    fun restoreThemePackageFonts(backupRoot: File) {
        val manifestFile = File(backupRoot, manifestFileName)
        if (!manifestFile.exists()) return
        val themePackagesRoot = File(backupRoot, themePackagesDirName)
        if (!themePackagesRoot.exists() || !themePackagesRoot.isDirectory) return
        val entries = GSON.fromJsonArray<FontDedupeEntry>(manifestFile.readText()).getOrNull()
            ?: return
        entries.forEach { entry ->
            val source = themePackagesRoot.resolveSafeChild(entry.sourcePath) ?: return@forEach
            val duplicate = themePackagesRoot.resolveSafeChild(entry.duplicatePath) ?: return@forEach
            if (!source.isFile || duplicate.exists()) return@forEach
            runCatching {
                duplicate.parentFile?.mkdirs()
                source.copyTo(duplicate, overwrite = true)
            }
        }
    }

    private fun findDuplicateFonts(sourceRoot: File): List<FontDedupeEntry> {
        val firstPathByHash = linkedMapOf<String, String>()
        val entries = arrayListOf<FontDedupeEntry>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.isThemeFont() }
            .forEach { file ->
                val relativePath = file.relativePathTo(sourceRoot)
                val hash = file.sha256()
                val firstPath = firstPathByHash[hash]
                if (firstPath == null) {
                    firstPathByHash[hash] = relativePath
                } else {
                    entries.add(
                        FontDedupeEntry(
                            duplicatePath = relativePath,
                            sourcePath = firstPath
                        )
                    )
                }
            }
        return entries
    }

    private fun copyThemePackages(
        sourceRoot: File,
        targetRoot: File,
        duplicatePaths: Set<String>
    ) {
        sourceRoot.walkTopDown().forEach { source ->
            val relativePath = source.relativePathTo(sourceRoot)
            val target = File(targetRoot, relativePath)
            when {
                source == sourceRoot -> targetRoot.mkdirs()
                source.isDirectory -> target.mkdirs()
                duplicatePaths.contains(relativePath) -> Unit
                else -> {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
            }
        }
    }

    private fun File.isThemeFont(): Boolean {
        val lowerName = name.lowercase()
        val extension = lowerName.substringAfterLast('.', "")
        return fontPrefixes.any { lowerName.startsWith(it) } && extension in fontExtensions
    }

    private fun File.relativePathTo(root: File): String {
        return root.toPath()
            .relativize(toPath())
            .toString()
            .replace(File.separatorChar, '/')
    }

    private fun File.resolveSafeChild(relativePath: String): File? {
        val root = canonicalFile
        val child = File(this, relativePath).canonicalFile
        return child.takeIf {
            it == root || it.path.startsWith(root.path + File.separator)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    data class FontDedupeEntry(
        val duplicatePath: String,
        val sourcePath: String
    )
}
