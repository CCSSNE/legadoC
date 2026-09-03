package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.zip.ZipFile

/**
 * 上游外观套件包（appearance_kit.json + 组件 zip）的识别与导入。
 *
 * 套件可携带界面主题、顶栏、导航栏、封面集合四类组件；本项目当前只实现了
 * 界面主题与导航栏两类消费端，顶栏与封面集合组件在导入时明确计数跳过并
 * 向用户报告，不做静默丢弃，也不伪装成导入成功。
 */
object AppearanceKitManager {

    const val TYPE_THEME = "THEME"
    const val TYPE_TOP_BAR = "TOP_BAR"
    const val TYPE_NAVIGATION_BAR = "NAVIGATION_BAR"
    const val TYPE_COVER_COLLECTION = "COVER_COLLECTION"

    private const val kitManifestName = "appearance_kit.json"

    data class ImportSummary(
        val kitName: String,
        val themeCount: Int,
        val navigationBarCount: Int,
        val unsupportedCount: Int,
        val unsupportedTypes: List<String>
    )

    fun isKitPackage(zipFile: File): Boolean {
        if (!zipFile.isFile) return false
        return runCatching {
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && entry.name.isKitManifestPath()
                }
            }
        }.getOrDefault(false)
    }

    suspend fun importPackage(file: File): ImportSummary = withContext(IO) {
        val unzipDir = tempDir.getFile("kit_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        try {
            ZipUtils.unZipToPath(file, unzipDir)
            val manifestFile = unzipDir.walkTopDown().firstOrNull {
                it.isFile && it.name == kitManifestName
            } ?: throw IllegalArgumentException(appCtx.getString(R.string.appearance_kit_manifest_missing))
            val packageRoot = manifestFile.parentFile ?: unzipDir
            val manifest = GSON.fromJsonObject<KitManifest>(manifestFile.readText()).getOrThrow()
            var themeCount = 0
            var navigationBarCount = 0
            val unsupportedTypes = mutableListOf<String>()
            manifest.components.forEach { component ->
                val componentFile = resolveComponentFile(packageRoot, unzipDir, component.path)
                    ?: return@forEach
                when (component.type) {
                    TYPE_THEME -> {
                        ThemePackageManager.importZipForKit(componentFile)
                        themeCount += 1
                    }

                    TYPE_NAVIGATION_BAR -> {
                        NavigationBarIconConfig.importZip(componentFile)
                        navigationBarCount += 1
                    }

                    TYPE_TOP_BAR -> unsupportedTypes += TYPE_TOP_BAR
                    TYPE_COVER_COLLECTION -> unsupportedTypes += TYPE_COVER_COLLECTION
                }
            }
            if (themeCount == 0 && navigationBarCount == 0) {
                throw IllegalArgumentException(appCtx.getString(R.string.appearance_kit_no_importable))
            }
            ImportSummary(
                kitName = manifest.name.ifBlank { appCtx.getString(R.string.appearance_kit_default_name) },
                themeCount = themeCount,
                navigationBarCount = navigationBarCount,
                unsupportedCount = unsupportedTypes.size,
                unsupportedTypes = unsupportedTypes.distinct()
            )
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun resolveComponentFile(packageRoot: File, unzipRoot: File, path: String): File? {
        val normalized = path.replace('\\', '/').trim('/').takeIf { it.isNotBlank() } ?: return null
        if (File(normalized).isAbsolute) return null
        val rootCanonical = unzipRoot.canonicalFile
        return listOf(File(packageRoot, normalized), File(unzipRoot, normalized))
            .mapNotNull { candidate ->
                val canonical = candidate.canonicalFile
                if (canonical.isFile && canonical.path.startsWith(rootCanonical.path)) {
                    canonical
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("appearanceKitTemp").apply {
            if (!exists()) mkdirs()
        }

    private fun String.isKitManifestPath(): Boolean {
        val normalized = trim('/')
        return normalized == kitManifestName || normalized.endsWith("/$kitManifestName")
    }

    @Keep
    private data class KitManifest(
        val id: String = "",
        val name: String = "",
        val version: Int = 0,
        val components: List<KitComponent> = emptyList()
    )

    @Keep
    private data class KitComponent(
        val type: String = "",
        val isNight: Boolean = false,
        val path: String = ""
    )
}
