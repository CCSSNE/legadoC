package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.io.File

/**
 * 主页顶栏包配置。上游顶栏 View（MainTopBarView）依赖上游主页布局结构，本项目
 * 主页为自研玻璃架构，因此这里只移植配置与包管理层；样式经 [UiCorner] 与现有
 * 标签条/搜索控件消费，壁纸字段仅保存数据。
 */
object TopBarConfig {

    const val DEFAULT_DIR_NAME = "default"
    const val STYLE_DEFAULT = "default"
    const val STYLE_REGULAR = "regular"
    private const val packageFileName = "top_bar.json"
    private const val activeDayKey = PreferKey.topBarPackageDay
    private const val activeNightKey = PreferKey.topBarPackageNight

    val rootDir: File
        get() = appCtx.externalFiles.getFile("topBarPackages")

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("topBarTemp").apply { mkdirs() }

    @Keep
    data class Config(
        var name: String,
        var isNightMode: Boolean,
        var style: String = STYLE_DEFAULT,
        var tagBarColor: Int? = null,
        var tagBarAlpha: Int = 100,
        var tagSelectedColor: Int? = null,
        var tagSelectedAlpha: Int = 100,
        var wallpaperPath: String? = null,
        var wallpaperCropLeft: Float? = null,
        var wallpaperCropTop: Float? = null,
        var wallpaperCropRight: Float? = null,
        var wallpaperCropBottom: Float? = null,
        var wallpaperAlpha: Int = 100,
        var backgroundColor: Int? = null,
        var cornerScale: Float? = null,
        var expandFiltersByDefault: Boolean = false,
        var hideFilterToggleWhenExpanded: Boolean = false,
        var showSearchInDefaultStyle: Boolean = false,
        var updatedAt: Long = System.currentTimeMillis()
    )

    data class Entry(
        val config: Config,
        val source: Source,
        val dirName: String,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    )

    enum class Source { BUILTIN, LOCAL, REMOTE, BOTH }

    fun defaultConfig(context: Context, isNight: Boolean): Config {
        return Config(
            name = defaultName(isNight),
            isNightMode = isNight,
            style = STYLE_DEFAULT,
            tagBarColor = UiCorner.themeSurfaceTabColor(context),
            tagBarAlpha = 100,
            tagSelectedColor = UiCorner.themeSurfaceCardColor(context),
            tagSelectedAlpha = 100,
            backgroundColor = defaultBackgroundColor(isNight),
            cornerScale = 1f,
            updatedAt = 0L
        )
    }

    fun activeDirName(isNight: Boolean): String {
        return appCtx.getPrefString(if (isNight) activeNightKey else activeDayKey, DEFAULT_DIR_NAME)
            ?.ifBlank { DEFAULT_DIR_NAME }
            ?: DEFAULT_DIR_NAME
    }

    fun currentEntry(context: Context, isNight: Boolean = AppConfig.isNightTheme): Entry {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) return defaultEntry(context, isNight)
        return readEntry(localDir(isNight, dirName)) ?: defaultEntry(context, isNight)
    }

    fun currentConfig(context: Context, isNight: Boolean = AppConfig.isNightTheme): Config {
        return currentEntry(context, isNight).config
    }

    fun apply(entry: Entry) {
        val key = if (entry.config.isNightMode) activeNightKey else activeDayKey
        appCtx.putPrefString(key, entry.dirName)
    }

    suspend fun loadLocalOnlyForKit(isNight: Boolean): List<Entry> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadLocal(isNight)
        }

    fun defaultEntryForKit(context: Context, isNight: Boolean): Entry {
        return defaultEntry(context, isNight)
    }

    fun deleteLocal(entry: Entry) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        FileUtils.delete(
            entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName),
            deleteRootDir = true
        )
        resetActiveIfNeeded(entry)
    }

    suspend fun exportZip(entry: Entry): File {
        val dir = entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName)
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        return zipFile
    }

    fun importZip(zipFile: File): Entry = importZipInternal(zipFile)

    fun withOpacity(color: Int, opacity: Int): Int {
        val alpha = (opacity.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun defaultBackgroundColor(isNight: Boolean): Int {
        return if (isNight) Color.BLACK else Color.WHITE
    }

    fun resolveBackgroundColor(config: Config): Int {
        return config.backgroundColor ?: defaultBackgroundColor(config.isNightMode)
    }

    fun resolveCornerScale(config: Config): Float {
        return config.cornerScale ?: 1f
    }

    fun cornerRadius(context: Context, config: Config): Float {
        return context.resources.getDimension(R.dimen.ui_panel_radius) *
            resolveCornerScale(config).coerceIn(0f, 3f)
    }

    private fun defaultEntry(context: Context, isNight: Boolean): Entry {
        return Entry(defaultConfig(context, isNight), Source.BUILTIN, DEFAULT_DIR_NAME)
    }

    private fun loadLocal(isNight: Boolean): List<Entry> {
        return typeDir(isNight).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readEntry(it) }
            .orEmpty()
    }

    private fun readEntry(dir: File): Entry? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        val config = GSON.fromJsonObject<Config>(file.readText()).getOrNull()?.let(::normalizeConfig)
            ?: return null
        return Entry(config, Source.LOCAL, dir.name, localDir = dir)
    }

    private fun importZipInternal(zipFile: File, remoteUpdatedAt: Long = 0L): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException(appCtx.getString(R.string.top_bar_config_missing))
            val config = normalizeConfig(GSON.fromJsonObject<Config>(packageFile.readText()).getOrThrow())
            if (remoteUpdatedAt == 0L) {
                config.updatedAt = System.currentTimeMillis()
            }
            val dirName = config.name.normalizeFileName().ifBlank { "top_bar_${System.currentTimeMillis()}" }
            val targetDir = localDir(config.isNightMode, dirName)
            if (targetDir.exists()) FileUtils.delete(targetDir, deleteRootDir = true)
            targetDir.mkdirs()
            packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
            val finalConfig = config.copy(
                wallpaperPath = normalizeWallpaperPath(config.wallpaperPath, targetDir)
            )
            File(targetDir, packageFileName).writeText(GSON.toJson(finalConfig))
            Entry(finalConfig, Source.LOCAL, dirName, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun normalizeConfig(config: Config): Config {
        config.style = when (config.style) {
            STYLE_DEFAULT, STYLE_REGULAR -> config.style
            "immersive", "flow" -> STYLE_REGULAR
            else -> STYLE_DEFAULT
        }
        config.tagBarAlpha = config.tagBarAlpha.coerceIn(0, 100)
        config.tagSelectedAlpha = config.tagSelectedAlpha.coerceIn(0, 100)
        config.wallpaperAlpha = config.wallpaperAlpha.coerceIn(0, 100)
        config.wallpaperPath = config.wallpaperPath?.takeIf { it.isNotBlank() }
        normalizeWallpaperCrop(config)
        config.cornerScale = config.cornerScale?.coerceIn(0f, 3f)
        return config
    }

    private fun normalizeWallpaperCrop(config: Config) {
        val left = config.wallpaperCropLeft?.coerceIn(0f, 1f)
        val top = config.wallpaperCropTop?.coerceIn(0f, 1f)
        val right = config.wallpaperCropRight?.coerceIn(0f, 1f)
        val bottom = config.wallpaperCropBottom?.coerceIn(0f, 1f)
        if (
            config.wallpaperPath.isNullOrBlank() ||
            left == null || top == null || right == null || bottom == null ||
            right <= left || bottom <= top
        ) {
            config.wallpaperCropLeft = null
            config.wallpaperCropTop = null
            config.wallpaperCropRight = null
            config.wallpaperCropBottom = null
            return
        }
        config.wallpaperCropLeft = left
        config.wallpaperCropTop = top
        config.wallpaperCropRight = right
        config.wallpaperCropBottom = bottom
    }

    private fun normalizeWallpaperPath(path: String?, dir: File): String? {
        val value = path?.takeIf { it.isNotBlank() } ?: return null
        val source = File(value)
        if (!source.isAbsolute) {
            return value
        }
        if (!source.exists() || !source.isFile) {
            return null
        }
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("top_bar_wallpaper.") }
            ?.forEach { it.delete() }
        val suffix = source.extension.isNotBlank().let { if (it) source.extension else "jpg" }
        val target = File(dir, "top_bar_wallpaper.$suffix")
        if (source.absolutePath != target.absolutePath) {
            source.copyTo(target, overwrite = true)
        }
        return target.name
    }

    private fun resetActiveIfNeeded(entry: Entry) {
        if (activeDirName(entry.config.isNightMode) == entry.dirName) {
            appCtx.putPrefString(
                if (entry.config.isNightMode) activeNightKey else activeDayKey,
                DEFAULT_DIR_NAME
            )
        }
    }

    private fun localDir(isNight: Boolean, dirName: String): File = typeDir(isNight).getFile(dirName)

    private fun typeDir(isNight: Boolean): File {
        return rootDir.getFile(if (isNight) "night" else "day").apply { mkdirs() }
    }

    private fun defaultName(isNight: Boolean): String {
        return appCtx.getString(
            if (isNight) R.string.top_bar_night_default_name else R.string.top_bar_day_default_name
        )
    }
}
