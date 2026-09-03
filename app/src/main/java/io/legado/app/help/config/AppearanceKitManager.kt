package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.zip.ZipFile

/**
 * 上游外观套件包（appearance_kit.json + 组件 zip）的识别与导入。
 *
 * 套件可携带界面主题、顶栏、导航栏、封面集合四类组件；导入即应用：主题按
 * switchNightMode=false 应用并触发界面重建，导航栏/顶栏按各自日夜激活，封面
 * 集合按组件自带的日夜标记选中。上游顶栏 View（MainTopBarView）依赖上游主页
 * 布局结构，本项目经 TopBarConfig 配置层与现有标签条/搜索控件消费其样式。
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
        val topBarCount: Int,
        val coverCollectionCount: Int
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
            var topBarCount = 0
            var coverCollectionCount = 0
            // 主题应用会触发界面重建，放在最后：其余组件配置先写入，重建后一次生效。
            var themeEntry: ThemePackageManager.Entry? = null
            var navigationApplied = false
            manifest.components.forEach { component ->
                val componentFile = resolveComponentFile(packageRoot, unzipDir, component.path)
                    ?: return@forEach
                when (component.type) {
                    TYPE_NAVIGATION_BAR -> {
                        val entry = NavigationBarIconConfig.importZip(componentFile)
                        NavigationBarIconConfig.select(entry)
                        navigationApplied = true
                        navigationBarCount += 1
                    }

                    TYPE_TOP_BAR -> {
                        val entry = TopBarConfig.importZip(componentFile)
                        TopBarConfig.apply(entry)
                        topBarCount += 1
                    }

                    TYPE_COVER_COLLECTION -> {
                        val collection = CoverCollectionManager.importZip(
                            componentFile,
                            component.isNight,
                            overwrite = true
                        )
                        CoverCollectionManager.setSelected(collection.isNight, collection.id)
                        CoverCollectionManager.setMode(collection.isNight, collection.mode)
                        coverCollectionCount += 1
                    }

                    TYPE_THEME -> {
                        themeEntry = ThemePackageManager.importZipForKit(componentFile)
                        themeCount += 1
                    }
                }
            }
            if (navigationApplied) {
                NavigationBarIconConfig.applyCurrentBottomConfig(AppConfig.isNightTheme)
            }
            themeEntry?.let { ThemePackageManager.apply(appCtx, it, switchNightMode = false) }
            if (themeCount == 0 && navigationBarCount == 0 && topBarCount == 0 && coverCollectionCount == 0) {
                throw IllegalArgumentException(appCtx.getString(R.string.appearance_kit_no_importable))
            }
            ImportSummary(
                kitName = manifest.name.ifBlank { appCtx.getString(R.string.appearance_kit_default_name) },
                themeCount = themeCount,
                navigationBarCount = navigationBarCount,
                topBarCount = topBarCount,
                coverCollectionCount = coverCollectionCount
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
