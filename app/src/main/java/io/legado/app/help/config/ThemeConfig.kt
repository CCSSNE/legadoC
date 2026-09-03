package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.DefaultData
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.BookCover
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.stackBlur
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.CenterCropBitmapDrawable
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import java.io.FileOutputStream
import java.util.Locale

@Keep
object ThemeConfig {

    /**
     * The persisted application theme mode. Every user-facing selector must
     * switch this state through [switchThemeMode] so the UI and reader cannot
     * update different theme sources.
     */
    enum class ThemeMode(val preferenceValue: String) {
        AUTO("0"),
        LIGHT("1"),
        DARK("2"),
        EINK("3");

        companion object {
            fun fromPreference(value: Any?): ThemeMode? {
                return entries.firstOrNull { it.preferenceValue == value }
            }
        }
    }

    private const val DEFAULT_DAY_BACKGROUND_ASSET = "defaultData/pre_default_background_day.jpg"
    private const val DEFAULT_DAY_BACKGROUND_FILE = "pre_default_background_day.jpg"
    private const val DEFAULT_NIGHT_BACKGROUND_ASSET = "defaultData/pre_default_background_night.png"
    private const val DEFAULT_NIGHT_BACKGROUND_FILE = "pre_default_background_night.png"
    private const val LEGACY_DEFAULT_BACKGROUND_FILE = "pre_default_background.png"
    private const val MISAPPLIED_READER_DAY_BACKGROUND_FILE = "护眼漫绿.jpg"
    private const val MISAPPLIED_READER_NIGHT_BACKGROUND_FILE = "宁静夜色.jpg"
    private const val DEFAULT_DAY_PRIMARY = 0xFFF1F2F6.toInt()
    private const val DEFAULT_NIGHT_PRIMARY = 0xFF252528.toInt()
    private const val DEFAULT_DAY_PRIMARY_HEX = "#F1F2F6"
    private const val LEGACY_DEFAULT_DAY_PRIMARY = 0xFF795548.toInt()
    const val DEFAULT_BOOK_INFO_BACKGROUND_BLUR = 12
    const val PANEL_BG_CROP = "crop"
    const val PANEL_BG_FIT = "fit"
    private var usableBgImageCacheKey: String? = null
    private var usableBgImageCacheValue: Boolean = false
    const val configFileName = "themeConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)

    val configList: ArrayList<Config> by lazy {
        val cList = getConfigs() ?: DefaultData.themeConfigs
        ArrayList(cList.map { migrateLegacyDefaultDayPrimary(it) })
    }

    private var needClearImg = true

    /**
     * Installs the packaged default wallpapers only for theme slots that have
     * never been configured or still carry a legacy default. An explicit empty
     * value means the user removed it and must not be overwritten.
     */
    fun installDefaultBackgrounds(context: Context) {
        val preferences = context.defaultSharedPreferences
        val defaultDir = File(context.filesDir, "defaultData")
        val legacyPath = File(defaultDir, LEGACY_DEFAULT_BACKGROUND_FILE).absolutePath
        val misappliedDayPath = File(
            defaultDir,
            MISAPPLIED_READER_DAY_BACKGROUND_FILE
        ).absolutePath
        val misappliedNightPath = File(
            defaultDir,
            MISAPPLIED_READER_NIGHT_BACKGROUND_FILE
        ).absolutePath
        val needsDayBackground = !preferences.contains(PreferKey.bgImage) ||
            preferences.getString(PreferKey.bgImage, null) == legacyPath ||
            preferences.getString(PreferKey.bgImage, null) == misappliedDayPath
        val needsNightBackground = !preferences.contains(PreferKey.bgImageN) ||
            preferences.getString(PreferKey.bgImageN, null) == legacyPath ||
            preferences.getString(PreferKey.bgImageN, null) == misappliedNightPath
        if (!needsDayBackground && !needsNightBackground) return

        val dayBackgroundFile = File(defaultDir, DEFAULT_DAY_BACKGROUND_FILE)
        val nightBackgroundFile = File(defaultDir, DEFAULT_NIGHT_BACKGROUND_FILE)
        if (needsDayBackground) {
            installBackgroundAsset(context, DEFAULT_DAY_BACKGROUND_ASSET, dayBackgroundFile)
        }
        if (needsNightBackground) {
            installBackgroundAsset(context, DEFAULT_NIGHT_BACKGROUND_ASSET, nightBackgroundFile)
        }
        if (needsDayBackground && !dayBackgroundFile.isFile) return
        if (needsNightBackground && !nightBackgroundFile.isFile) return

        preferences.edit {
            if (needsDayBackground && dayBackgroundFile.isFile) {
                putString(PreferKey.bgImage, dayBackgroundFile.absolutePath)
            }
            if (needsNightBackground && nightBackgroundFile.isFile) {
                putString(PreferKey.bgImageN, nightBackgroundFile.absolutePath)
            }
        }
        if (dayBackgroundFile.isFile || nightBackgroundFile.isFile) {
            FileUtils.delete(legacyPath)
        }
    }

    private fun installBackgroundAsset(context: Context, asset: String, target: File) {
        if (target.isFile && target.length() > 0L) return
        runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input ->
                target.outputStream().use(input::copyTo)
            }
        }.onFailure {
            AppLog.put("Install default theme background failed", it, true)
        }
    }

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    fun currentThemeMode(): ThemeMode {
        return ThemeMode.fromPreference(AppConfig.themeMode) ?: ThemeMode.AUTO
    }

    fun currentVisualThemeMode(): ThemeMode {
        return when (val mode = currentThemeMode()) {
            ThemeMode.AUTO -> if (AppConfig.isNightTheme) ThemeMode.DARK else ThemeMode.LIGHT
            else -> mode
        }
    }

    /**
     * The only state transition for application theme selection. Callers may
     * choose whether their host needs recreation, but they never write the
     * preference or the AppConfig cache themselves.
     */
    fun switchThemeMode(
        context: Context,
        mode: ThemeMode,
        recreate: Boolean = true,
        forceApply: Boolean = false,
    ): Boolean {
        val changed = setThemeModeState(context, mode)
        if (changed || forceApply) {
            if (recreate) {
                applyDayNight(context)
            } else {
                applyDayNightNoRecreate(context)
            }
        }
        return changed
    }

    fun toggleLightDarkTheme(context: Context, recreate: Boolean = true): Boolean {
        val target = if (AppConfig.isNightTheme) ThemeMode.LIGHT else ThemeMode.DARK
        return switchThemeMode(context, target, recreate)
    }

    private fun setThemeModeState(context: Context, mode: ThemeMode): Boolean {
        val changed = AppConfig.themeMode != mode.preferenceValue ||
            AppConfig.isEInkMode != (mode == ThemeMode.EINK)
        if (!changed) return false
        context.putPrefString(PreferKey.themeMode, mode.preferenceValue)
        AppConfig.updateThemeModeCache(mode.preferenceValue)
        return true
    }

    fun isDarkTheme(): Boolean {
        return getTheme() == Theme.Dark
    }

    fun applyDayNight(context: Context) {
        applyTheme(context)
        initNightMode()
        BookCover.upDefaultCover()
        postEvent(EventBus.RECREATE, "")
    }

    fun applyDayNightNoRecreate(context: Context) {
        applyTheme(context)
        initNightMode()
        BookCover.upDefaultCover()
    }

    fun applyDayNightInit(context: Context) {
        migrateLegacyDefaultDayPrimary(context)
        applyTheme(context)
        initNightMode()
    }

    private fun migrateLegacyDefaultDayPrimary(context: Context) {
        if (context.getPrefInt(PreferKey.cPrimary, DEFAULT_DAY_PRIMARY) == LEGACY_DEFAULT_DAY_PRIMARY) {
            context.putPrefInt(PreferKey.cPrimary, DEFAULT_DAY_PRIMARY)
        }
    }

    private fun migrateLegacyDefaultDayPrimary(config: Config): Config {
        if (config.isNightTheme) return config
        val isLegacyDefault = runCatching {
            config.primaryColor.toColorInt() == LEGACY_DEFAULT_DAY_PRIMARY
        }.getOrDefault(false)
        if (!isLegacyDefault) return config
        return config.copy(primaryColor = DEFAULT_DAY_PRIMARY_HEX)
    }

    private fun initNightMode() {
        val targetMode =
            if (AppConfig.isNightTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    /**
     * 获取链接获取图片文件名
     */
    private fun getUrlToFile(url: String): String {
        val suffix = when {
            url.contains(".9.png", ignoreCase = true) -> ".9.png"
            url.contains(".png", ignoreCase = true) -> ".png"
            url.contains(".gif", ignoreCase = true) -> ".gif"
            url.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
        return MD5Utils.md5Encode16(url) + suffix
    }

    /**
     * 背景图路径统一解析入口：偏好里可能存 URL、绝对路径或备份/主题配置残留的裸缓存文件名，
     * 统一解析为真实存在的文件路径；文件不存在时视为无背景返回 null，不允许把失效路径带进解码。
     */
    private fun resolveBgImagePath(context: Context, preferenceKey: String): String? {
        val path = context.getPrefString(preferenceKey)?.takeIf { it.isNotBlank() } ?: return null
        if (path.startsWith("http", ignoreCase = true)) {
            val filePath = FileUtils.getPath(context.externalFiles, preferenceKey, getUrlToFile(path))
            return filePath.takeIf { FileUtils.exist(it) }
        }
        if (!File(path).isAbsolute) {
            val filePath = FileUtils.getPath(context.externalFiles, preferenceKey, path)
            return filePath.takeIf { FileUtils.exist(it) }
        }
        return path.takeIf { isReadableThemeFile(it) }
    }

    fun getBgImage(context: Context, metrics: DisplayMetrics): Drawable? {
        val themeMode = getTheme()
        val preferenceKey = when (themeMode) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return null
        }
        val path = resolveBgImagePath(context, preferenceKey) ?: return null
        if (path.endsWith(".9.png")) {
            val bgDrawable = BitmapUtils.decodeNinePatchDrawable(path)
            return bgDrawable
        }
        val bgImgBlu = when (themeMode) {
            Theme.Light -> context.getPrefInt(PreferKey.bgImageBlurring, 0)
            Theme.Dark -> context.getPrefInt(PreferKey.bgImageNBlurring, 0)
        }
        val bgImage = BitmapUtils
            .decodeBitmap(path, metrics.widthPixels, metrics.heightPixels)
        if (bgImgBlu == 0) {
            return bgImage?.let { CenterCropBitmapDrawable(context.resources, it) }
        }
        return bgImage?.stackBlur(bgImgBlu)?.let { CenterCropBitmapDrawable(context.resources, it) }
    }

    fun getBookInfoBgImage(context: Context, metrics: DisplayMetrics): Drawable? {
        val themeMode = getTheme()
        val preferenceKey = when (themeMode) {
            Theme.Light -> PreferKey.bookInfoBgImage
            Theme.Dark -> PreferKey.bookInfoBgImageN
            else -> return null
        }
        val path = resolveBgImagePath(context, preferenceKey) ?: return null
        val bgImgBlur = when (themeMode) {
            Theme.Light -> context.getPrefInt(
                PreferKey.bookInfoBgImageBlurring,
                DEFAULT_BOOK_INFO_BACKGROUND_BLUR
            )
            Theme.Dark -> context.getPrefInt(
                PreferKey.bookInfoBgImageNBlurring,
                DEFAULT_BOOK_INFO_BACKGROUND_BLUR
            )
        }.coerceIn(0, 25)
        val bgImage = BitmapUtils.decodeBitmap(path, metrics.widthPixels, metrics.heightPixels)
        if (bgImgBlur == 0) {
            return bgImage?.let { CenterCropBitmapDrawable(context.resources, it) }
        }
        return bgImage?.stackBlur(bgImgBlur)?.let { CenterCropBitmapDrawable(context.resources, it) }
    }

    fun hasUsableBgImage(context: Context): Boolean {
        val preferenceKey = when (getTheme()) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return false
        }
        val path = context.getPrefString(preferenceKey)?.takeIf { it.isNotBlank() } ?: return false
        val cacheKey = "$preferenceKey|$path"
        if (usableBgImageCacheKey == cacheKey) {
            return usableBgImageCacheValue
        }
        return (resolveBgImagePath(context, preferenceKey) != null).also {
            usableBgImageCacheKey = cacheKey
            usableBgImageCacheValue = it
        }
    }

    fun getFallbackBackgroundColor(context: Context): Int {
        return when {
            AppConfig.isEInkMode -> Color.WHITE
            AppConfig.isNightTheme -> context.getPrefInt(
                PreferKey.cNBackground,
                context.getCompatColor(R.color.md_grey_900)
            )
            else -> context.getPrefInt(
                PreferKey.cBackground,
                context.getCompatColor(R.color.md_grey_100)
            )
        }
    }

    fun upConfig() {
        addConfigs(getConfigs())
    }

    fun save() {
        val json = GSON.toJson(configList)
        FileUtils.delete(configFilePath)
        FileUtils.createFileIfNotExist(configFilePath).writeText(json)
    }

    fun delConfig(index: Int) {
        configList.removeAt(index)
        save()
    }

    fun addConfig(json: String): Boolean {
        GSON.fromJsonObject<Config>(json.trim { it < ' ' }).getOrNull()
            ?.let {
                if (validateConfig(it)) {
                    addConfig(it)
                    return true
                }
            }
        return false
    }

    fun addConfig(newConfig: Config) {
        val newConfig = migrateLegacyDefaultDayPrimary(newConfig)
        if (!validateConfig(newConfig)) {
            return
        }
        var hasTheme = false
        configList.forEachIndexed { index, config ->
            if (newConfig.themeName == config.themeName) {
                configList[index] = newConfig
                hasTheme = true
                return@forEachIndexed
            }
        }
        if (!hasTheme) {
            configList.add(newConfig)
        }
        save()
    }

    fun addConfigs(newConfigs: List<Config>?) {
        val newConfigs = newConfigs
            ?.map { migrateLegacyDefaultDayPrimary(it) }
            ?.filter { validateConfig(it) }
        if (newConfigs.isNullOrEmpty()) {
            return
        }
        newConfigs.forEach { newConfig ->
            val existingIndex = configList.indexOfFirst { it.themeName == newConfig.themeName }
            if (existingIndex != -1) {
                configList[existingIndex] = newConfig
            } else {
                configList.add(newConfig)
            }
        }
        save()
    }

    fun normalizeBackgroundCrop(value: String?): String? {
        val parts = value
            ?.split(',', '|', ';')
            ?.mapNotNull { it.trim().toFloatOrNull()?.coerceIn(0f, 1f) }
            ?: return null
        if (parts.size != 4) return null
        val (left, top, right, bottom) = parts
        if (right <= left || bottom <= top) return null
        return parts.joinToString(",") { crop ->
            String.format(Locale.US, "%.6f", crop).trimEnd('0').trimEnd('.')
        }
    }

    private fun applyExtendedInterfaceColors(context: Context, config: Config) {
        val isNightTheme = config.isNightTheme
        context.putOrClearThemeColor(
            if (isNightTheme) PreferKey.themeCardColorN else PreferKey.themeCardColor,
            config.cardColor
        )
        context.putOrClearThemeColor(
            if (isNightTheme) PreferKey.themeMutedColorN else PreferKey.themeMutedColor,
            config.mutedColor
        )
        context.putOrClearThemeColor(
            if (isNightTheme) {
                PreferKey.themeSearchFieldBackgroundColorN
            } else {
                PreferKey.themeSearchFieldBackgroundColor
            },
            config.searchFieldBackgroundColor
        )
        context.putOrClearThemeColor(
            if (isNightTheme) {
                PreferKey.themeTabBackgroundColorN
            } else {
                PreferKey.themeTabBackgroundColor
            },
            config.tabBackgroundColor
        )
        context.putOrClearThemeColor(
            if (isNightTheme) PreferKey.themeShelfColorN else PreferKey.themeShelfColor,
            config.shelfColor
        )
        val shadowKey = if (isNightTheme) PreferKey.themeCardShadowN else PreferKey.themeCardShadow
        config.cardShadow?.let {
            context.putPrefInt(shadowKey, it.coerceIn(0, 24))
        } ?: context.removePref(shadowKey)
        val blurKey = if (isNightTheme) {
            PreferKey.themeCardBackgroundBlurN
        } else {
            PreferKey.themeCardBackgroundBlur
        }
        config.cardBackgroundBlur?.let {
            context.putPrefInt(blurKey, (it * 10f).toInt().coerceIn(0, 250))
        } ?: context.removePref(blurKey)
        context.putOrClearThemeColor(
            if (isNightTheme) PreferKey.uiFontColorN else PreferKey.uiFontColor,
            config.uiFontColor
        )
        context.putOrClearThemeColor(
            if (isNightTheme) PreferKey.titleFontColorN else PreferKey.titleFontColor,
            config.titleFontColor
        )
    }

    private fun Context.putOrClearThemeColor(key: String, value: String?) {
        val normalized = value?.takeIf { it.isNotBlank() }
        if (normalized == null) {
            removePref(key)
        } else {
            putPrefString(key, normalized)
        }
    }

    private fun validateConfig(config: Config): Boolean {
        try {
            config.primaryColor.toColorInt()
            config.accentColor.toColorInt()
            config.backgroundColor.toColorInt()
            config.bottomBackground.toColorInt()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun getConfigs(): List<Config>? {
        val configFile = File(configFilePath)
        if (configFile.exists()) {
            kotlin.runCatching {
                val json = configFile.readText()
                return GSON.fromJsonArray<Config>(json).getOrThrow()
            }
        }
        return null
    }

    fun applyConfig(
        context: Context,
        config: Config,
        switchNightMode: Boolean = true,
        notify: Boolean = true
    ) {
        try {
            if (needClearImg) {
                needClearImg = false
                clearBg(context)
            }
            val primary = config.primaryColor.toColorInt()
            val accent = config.accentColor.toColorInt()
            val background = config.backgroundColor.toColorInt()
            val bBackground = config.bottomBackground.toColorInt()
            val isNightTheme = config.isNightTheme
            val backgroundPath = config.backgroundImgPath
            val backgroundCrop = normalizeBackgroundCrop(config.backgroundImgCrop)
            val bookInfoBackgroundPath = config.bookInfoBackgroundImgPath
            val bookInfoBackgroundBlur = config.bookInfoBackgroundBlur().coerceIn(0, 25)
            val panelBackgroundPath = config.panelBackgroundImgPath
            val panelBackgroundScaleType = config.panelBackgroundScaleType?.takeIf {
                it == PANEL_BG_CROP || it == PANEL_BG_FIT
            } ?: PANEL_BG_CROP
            val panelBorderColor = config.panelBorderColor?.takeIf { it.isNotBlank() }
            val panelBorderAlpha = config.panelBorderAlpha?.coerceIn(0, 100) ?: 100
            config.uiCornerScale?.let {
                context.putPrefString(PreferKey.uiCornerScale, it.coerceIn(0f, 3f).toPlainScale())
            }
            config.uiLayoutAlpha?.let {
                context.putPrefInt(PreferKey.uiLayoutAlpha, it.coerceIn(0, 100))
            }
            config.dialogAlpha?.let {
                context.putPrefInt(PreferKey.dialogAlpha, it.coerceIn(0, 100))
            }
            applyExtendedInterfaceColors(context, config)
            config.uiCornerSearchFollow?.let {
                context.putPrefBoolean(PreferKey.uiCornerSearchFollow, it)
            }
            config.uiCornerReplyFollow?.let {
                context.putPrefBoolean(PreferKey.uiCornerReplyFollow, it)
            }
            config.fontScale?.let {
                context.putPrefInt(PreferKey.fontScale, it.coerceIn(0, 16))
            }
            context.putPrefString(PreferKey.uiFontPath, config.uiFontPath.orEmpty())
            context.putPrefString(PreferKey.titleFontPath, config.titleFontPath.orEmpty())
            if (backgroundPath != null && backgroundPath.startsWith("http")) {
                val fileRoot = context.externalFiles
                val preferenceKey = if (isNightTheme) {
                    PreferKey.bgImageN
                } else {
                    PreferKey.bgImage
                }
                val name = getUrlToFile(backgroundPath)
                val fileFold = File(fileRoot, preferenceKey)
                if (!fileFold.exists()) {
                    fileFold.mkdirs()
                }
                val fileImg = File(fileFold, name)
                if (!fileImg.exists()) {
                    appCtx.toastOnUi(R.string.theme_background_downloading)
                    Coroutine.async {
                        kotlin.runCatching {
                            val res = okHttpClient.newCallResponse(0) {
                                url(backgroundPath)
                            }
                            res.body.byteStream().use { inputStream ->
                                FileOutputStream(fileImg).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }.onSuccess {
                            appCtx.toastOnUi(R.string.theme_background_downloaded)
                            if (notify) {
                                postEvent(EventBus.RECREATE, "")
                            }
                        }.onFailure {
                            appCtx.toastOnUi(it.localizedMessage)
                        }
                    }
                }
            }
            val backgroundBlur = config.backgroundImgBlur
            if (isNightTheme) {
                context.putPrefString(PreferKey.dNThemeName, config.themeName)
                context.putPrefInt(PreferKey.cNPrimary, primary)
                context.putPrefInt(PreferKey.cNAccent, accent)
                context.putPrefInt(PreferKey.cNBackground, background)
                context.putPrefInt(PreferKey.cNBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBarN, true)
                context.putPrefString(PreferKey.bgImageN, backgroundPath.orEmpty())
                context.putPrefInt(PreferKey.bgImageNBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bgImageCropN, backgroundCrop.orEmpty())
                context.putPrefString(PreferKey.bookInfoBgImageN, bookInfoBackgroundPath)
                context.putPrefInt(PreferKey.bookInfoBgImageNBlurring, bookInfoBackgroundBlur)
                context.putPrefString(PreferKey.panelBgImageN, panelBackgroundPath.orEmpty())
                context.putPrefString(PreferKey.panelBgScaleTypeN, panelBackgroundScaleType)
                context.putPrefString(PreferKey.panelBorderColorN, panelBorderColor.orEmpty())
                context.putPrefInt(PreferKey.panelBorderAlphaN, panelBorderAlpha)
            } else {
                context.putPrefString(PreferKey.dThemeName, config.themeName)
                context.putPrefInt(PreferKey.cPrimary, primary)
                context.putPrefInt(PreferKey.cAccent, accent)
                context.putPrefInt(PreferKey.cBackground, background)
                context.putPrefInt(PreferKey.cBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBar, true)
                context.putPrefString(PreferKey.bgImage, backgroundPath.orEmpty())
                context.putPrefInt(PreferKey.bgImageBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bgImageCrop, backgroundCrop.orEmpty())
                context.putPrefString(PreferKey.bookInfoBgImage, bookInfoBackgroundPath)
                context.putPrefInt(PreferKey.bookInfoBgImageBlurring, bookInfoBackgroundBlur)
                context.putPrefString(PreferKey.panelBgImage, panelBackgroundPath.orEmpty())
                context.putPrefString(PreferKey.panelBgScaleType, panelBackgroundScaleType)
                context.putPrefString(PreferKey.panelBorderColor, panelBorderColor.orEmpty())
                context.putPrefInt(PreferKey.panelBorderAlpha, panelBorderAlpha)
            }
            if (switchNightMode) {
                switchThemeMode(
                    context = context,
                    mode = if (isNightTheme) ThemeMode.DARK else ThemeMode.LIGHT,
                    recreate = notify,
                    forceApply = notify,
                )
                return
            }
            if (!notify) {
                return
            }
            applyTheme(context)
            BookCover.upDefaultCover()
            postEvent(EventBus.RECREATE, "")
        } catch (e: Exception) {
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    fun getDurConfig(context: Context): Config {
        val isNight = AppConfig.isNightTheme
        val name = if (isNight) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNight) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    fun getThemeConfig(context: Context, isNightTheme: Boolean): Config {
        val name = if (isNightTheme) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNightTheme) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    private fun getDayTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(PreferKey.cPrimary, DEFAULT_DAY_PRIMARY)
        val accent =
            context.getPrefInt(PreferKey.cAccent, context.getCompatColor(R.color.md_red_600))
        val background =
            context.getPrefInt(PreferKey.cBackground, context.getCompatColor(R.color.md_grey_100))
        val bBackground =
            context.getPrefInt(PreferKey.cBBackground, context.getCompatColor(R.color.md_grey_200))
        val bgImgPath =
            context.getPrefString(PreferKey.bgImage)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageBlurring, 0)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImage)
        val bookInfoBgImgBlur =
            context.getPrefInt(PreferKey.bookInfoBgImageBlurring, DEFAULT_BOOK_INFO_BACKGROUND_BLUR)
        val stored = configList.firstOrNull {
            it.themeName == name && !it.isNightTheme
        }

        return mergeStoredThemeAssets(
            Config(
                themeName = name,
                isNightTheme = false,
                primaryColor = "#${primary.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${background.hexString}",
                bottomBackground = "#${bBackground.hexString}",
                transparentNavBar = true,
                backgroundImgPath = bgImgPath,
                backgroundImgBlur = bgImgBlur,
                backgroundImgCrop = context.getPrefString(PreferKey.bgImageCrop)
                    ?.takeIf { it.isNotBlank() } ?: stored?.backgroundImgCrop,
                bookInfoBackgroundImgPath = bookInfoBgImgPath,
                bookInfoBackgroundImgBlur = bookInfoBgImgBlur,
                panelBackgroundImgPath = context.getPrefString(PreferKey.panelBgImage)
                    ?.takeIf { it.isNotBlank() } ?: stored?.panelBackgroundImgPath,
                panelBackgroundScaleType = context.getPrefString(PreferKey.panelBgScaleType)
                    ?.takeIf { it.isNotBlank() } ?: stored?.panelBackgroundScaleType,
                panelBorderColor = context.getPrefString(PreferKey.panelBorderColor)
                    ?.takeIf { it.isNotBlank() } ?: stored?.panelBorderColor,
                panelBorderAlpha = context.getPrefInt(PreferKey.panelBorderAlpha, 100),
                uiCornerScale = stored?.uiCornerScale ?: AppConfig.uiCornerScale,
                uiLayoutAlpha = stored?.uiLayoutAlpha ?: AppConfig.uiLayoutAlpha,
                dialogAlpha = appCtx.getPrefInt(PreferKey.dialogAlpha, 50).coerceIn(0, 100),
                cardColor = context.getPrefString(PreferKey.themeCardColor)
                    ?.takeIf { it.isNotBlank() } ?: stored?.cardColor,
                mutedColor = context.getPrefString(PreferKey.themeMutedColor)
                    ?.takeIf { it.isNotBlank() } ?: stored?.mutedColor,
                searchFieldBackgroundColor = context.getPrefString(
                    PreferKey.themeSearchFieldBackgroundColor
                )?.takeIf { it.isNotBlank() } ?: stored?.searchFieldBackgroundColor,
                tabBackgroundColor = context.getPrefString(PreferKey.themeTabBackgroundColor)
                    ?.takeIf { it.isNotBlank() } ?: stored?.tabBackgroundColor,
                shelfColor = context.getPrefString(PreferKey.themeShelfColor)
                    ?.takeIf { it.isNotBlank() } ?: stored?.shelfColor,
                cardShadow = context.getPrefInt(PreferKey.themeCardShadow, -1)
                    .takeIf { it >= 0 } ?: stored?.cardShadow,
                cardBackgroundBlur = context.getPrefInt(PreferKey.themeCardBackgroundBlur, -1)
                    .takeIf { it >= 0 }?.let { it / 10f } ?: stored?.cardBackgroundBlur,
                uiCornerSearchFollow = stored?.uiCornerSearchFollow ?: AppConfig.uiCornerSearchFollow,
                uiCornerReplyFollow = stored?.uiCornerReplyFollow ?: AppConfig.uiCornerReplyFollow,
                fontScale = stored?.fontScale ?: appCtx.getPrefInt(PreferKey.fontScale, 0),
                uiFontPath = stored?.uiFontPath ?: AppConfig.uiFontPath,
                titleFontPath = stored?.titleFontPath ?: AppConfig.titleFontPath,
                uiFontColor = context.getPrefString(PreferKey.uiFontColor)
                    ?.takeIf { it.isNotBlank() } ?: stored?.uiFontColor,
                titleFontColor = context.getPrefString(PreferKey.titleFontColor)
                    ?.takeIf { it.isNotBlank() } ?: stored?.titleFontColor
            )
        )
    }

    fun saveDayTheme(context: Context, name: String) {
        val config = getDayTheme(context, name)
        addConfig(config)
    }

    private fun getNightTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(
                PreferKey.cNPrimary,
                DEFAULT_NIGHT_PRIMARY
            )
        val accent =
            context.getPrefInt(
                PreferKey.cNAccent,
                context.getCompatColor(R.color.md_deep_orange_800)
            )
        val background =
            context.getPrefInt(PreferKey.cNBackground, context.getCompatColor(R.color.md_grey_900))
        val bBackground =
            context.getPrefInt(PreferKey.cNBBackground, context.getCompatColor(R.color.md_grey_850))
        val bgImgPath =
            context.getPrefString(PreferKey.bgImageN)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageNBlurring, 0)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImageN)
        val bookInfoBgImgBlur =
            context.getPrefInt(PreferKey.bookInfoBgImageNBlurring, DEFAULT_BOOK_INFO_BACKGROUND_BLUR)
        val stored = configList.firstOrNull {
            it.themeName == name && it.isNightTheme
        }
        return mergeStoredThemeAssets(
            Config(
                themeName = name,
                isNightTheme = true,
                primaryColor = "#${primary.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${background.hexString}",
                bottomBackground = "#${bBackground.hexString}",
                transparentNavBar = true,
                backgroundImgPath = bgImgPath,
                backgroundImgBlur = bgImgBlur,
                backgroundImgCrop = context.getPrefString(PreferKey.bgImageCropN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.backgroundImgCrop,
                bookInfoBackgroundImgPath = bookInfoBgImgPath,
                bookInfoBackgroundImgBlur = bookInfoBgImgBlur,
                panelBackgroundImgPath = context.getPrefString(PreferKey.panelBgImageN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.panelBackgroundImgPath,
                panelBackgroundScaleType = context.getPrefString(PreferKey.panelBgScaleTypeN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.panelBackgroundScaleType,
                panelBorderColor = context.getPrefString(PreferKey.panelBorderColorN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.panelBorderColor,
                panelBorderAlpha = context.getPrefInt(PreferKey.panelBorderAlphaN, 100),
                uiCornerScale = stored?.uiCornerScale ?: AppConfig.uiCornerScale,
                uiLayoutAlpha = stored?.uiLayoutAlpha ?: AppConfig.uiLayoutAlpha,
                dialogAlpha = appCtx.getPrefInt(PreferKey.dialogAlpha, 50).coerceIn(0, 100),
                cardColor = context.getPrefString(PreferKey.themeCardColorN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.cardColor,
                mutedColor = context.getPrefString(PreferKey.themeMutedColorN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.mutedColor,
                searchFieldBackgroundColor = context.getPrefString(
                    PreferKey.themeSearchFieldBackgroundColorN
                )?.takeIf { it.isNotBlank() } ?: stored?.searchFieldBackgroundColor,
                tabBackgroundColor = context.getPrefString(PreferKey.themeTabBackgroundColorN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.tabBackgroundColor,
                shelfColor = context.getPrefString(PreferKey.themeShelfColorN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.shelfColor,
                cardShadow = context.getPrefInt(PreferKey.themeCardShadowN, -1)
                    .takeIf { it >= 0 } ?: stored?.cardShadow,
                cardBackgroundBlur = context.getPrefInt(PreferKey.themeCardBackgroundBlurN, -1)
                    .takeIf { it >= 0 }?.let { it / 10f } ?: stored?.cardBackgroundBlur,
                uiCornerSearchFollow = stored?.uiCornerSearchFollow ?: AppConfig.uiCornerSearchFollow,
                uiCornerReplyFollow = stored?.uiCornerReplyFollow ?: AppConfig.uiCornerReplyFollow,
                fontScale = stored?.fontScale ?: appCtx.getPrefInt(PreferKey.fontScale, 0),
                uiFontPath = stored?.uiFontPath ?: AppConfig.uiFontPath,
                titleFontPath = stored?.titleFontPath ?: AppConfig.titleFontPath,
                uiFontColor = context.getPrefString(PreferKey.uiFontColorN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.uiFontColor,
                titleFontColor = context.getPrefString(PreferKey.titleFontColorN)
                    ?.takeIf { it.isNotBlank() } ?: stored?.titleFontColor
            )
        )
    }

    private fun mergeStoredThemeAssets(config: Config): Config {
        if (config.themeName.isBlank()) return config
        val stored = configList.firstOrNull {
            it.themeName == config.themeName && it.isNightTheme == config.isNightTheme
        } ?: return config
        return config.copy(
            backgroundImgPath = preferThemeAsset(config.backgroundImgPath, stored.backgroundImgPath),
            backgroundImgCrop = config.backgroundImgCrop ?: stored.backgroundImgCrop,
            bookInfoBackgroundImgPath = config.bookInfoBackgroundImgPath,
            bookInfoBackgroundImgBlur = config.bookInfoBackgroundImgBlur,
            backgroundImgBlur = if (config.backgroundImgPath.isNullOrBlank() && !stored.backgroundImgPath.isNullOrBlank()) {
                stored.backgroundImgBlur
            } else {
                config.backgroundImgBlur
            },
            panelBackgroundImgPath = preferThemeAsset(
                config.panelBackgroundImgPath,
                stored.panelBackgroundImgPath
            ),
            panelBackgroundScaleType = config.panelBackgroundScaleType
                ?: stored.panelBackgroundScaleType,
            panelBorderColor = config.panelBorderColor ?: stored.panelBorderColor,
            panelBorderAlpha = config.panelBorderAlpha ?: stored.panelBorderAlpha,
            uiCornerScale = config.uiCornerScale ?: stored.uiCornerScale,
            uiLayoutAlpha = config.uiLayoutAlpha ?: stored.uiLayoutAlpha,
            dialogAlpha = config.dialogAlpha ?: stored.dialogAlpha,
            cardColor = config.cardColor ?: stored.cardColor,
            mutedColor = config.mutedColor ?: stored.mutedColor,
            searchFieldBackgroundColor = config.searchFieldBackgroundColor
                ?: stored.searchFieldBackgroundColor,
            tabBackgroundColor = config.tabBackgroundColor ?: stored.tabBackgroundColor,
            shelfColor = config.shelfColor ?: stored.shelfColor,
            cardShadow = config.cardShadow ?: stored.cardShadow,
            cardBackgroundBlur = config.cardBackgroundBlur ?: stored.cardBackgroundBlur,
            uiCornerSearchFollow = config.uiCornerSearchFollow ?: stored.uiCornerSearchFollow,
            uiCornerReplyFollow = config.uiCornerReplyFollow ?: stored.uiCornerReplyFollow,
            fontScale = config.fontScale ?: stored.fontScale,
            uiFontPath = config.uiFontPath ?: stored.uiFontPath,
            titleFontPath = config.titleFontPath ?: stored.titleFontPath,
            uiFontColor = config.uiFontColor ?: stored.uiFontColor,
            titleFontColor = config.titleFontColor ?: stored.titleFontColor
        )
    }

    private fun preferThemeAsset(current: String?, fallback: String?): String? {
        if (!current.isNullOrBlank()) {
            if (current.startsWith("http", ignoreCase = true)) return current
            if (File(current).exists()) return current
            if (isReadableThemeFile(current)) return current
        }
        return fallback?.takeIf {
            it.startsWith("http", ignoreCase = true) || isReadableThemeFile(it)
        }
    }

    private fun isReadableThemeFile(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        if (isOtherAppExternalDataPath(path)) return false
        return runCatching {
            FileInputStream(file).use { true }
        }.getOrDefault(false)
    }

    private fun isOtherAppExternalDataPath(path: String): Boolean {
        val marker = "/Android/data/"
        val normalized = path.replace('\\', '/')
        val start = normalized.indexOf(marker, ignoreCase = true)
        if (start < 0) return false
        val packageStart = start + marker.length
        val packageEnd = normalized.indexOf('/', packageStart).takeIf { it >= 0 } ?: normalized.length
        val ownerPackage = normalized.substring(packageStart, packageEnd)
        return ownerPackage.isNotBlank() && ownerPackage != appCtx.packageName
    }

    private fun Float.toPlainScale(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    fun saveNightTheme(context: Context, name: String) {
        val config = getNightTheme(context, name)
        addConfig(config)
    }

    /**
     * 更新主题
     */
    fun applyTheme(context: Context) = with(context) {
        when {
            AppConfig.isEInkMode -> {
                ThemeStore.editTheme(this)
                    .primaryColor(Color.WHITE)
                    .accentColor(Color.BLACK)
                    .backgroundColor(Color.WHITE)
                    .bottomBackground(Color.WHITE)
                    .transparentNavBar(true)
                    .apply()
            }

            AppConfig.isNightTheme -> {
                val primary =
                    getPrefInt(PreferKey.cNPrimary, DEFAULT_NIGHT_PRIMARY)
                val accent =
                    getPrefInt(PreferKey.cNAccent, getCompatColor(R.color.md_deep_orange_800))
                val background =
                    getPrefInt(PreferKey.cNBackground, getCompatColor(R.color.md_grey_900))
                val bBackground =
                    getPrefInt(PreferKey.cNBBackground, getCompatColor(R.color.md_grey_850))
                val appBackground =
                    if (hasUsableBgImage(this)) Color.TRANSPARENT else ColorUtils.withAlpha(background, 1f)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(appBackground)
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(true)
                    .apply()
            }

            else -> {
                val primary =
                    getPrefInt(PreferKey.cPrimary, DEFAULT_DAY_PRIMARY)
                val accent =
                    getPrefInt(PreferKey.cAccent, getCompatColor(R.color.md_red_600))
                val background =
                    getPrefInt(PreferKey.cBackground, getCompatColor(R.color.md_grey_100))
                val bBackground =
                    getPrefInt(PreferKey.cBBackground, getCompatColor(R.color.md_grey_200))
                val appBackground =
                    if (hasUsableBgImage(this)) Color.TRANSPARENT else ColorUtils.withAlpha(background, 1f)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(appBackground)
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(true)
                    .apply()
            }
        }
    }

    fun clearBg(context: Context) {
        val (nightConfigs, dayConfigs) = configList.partition { it.isNightTheme }
        val fileRoot = context.externalFiles
        val nightBackgroundImgPaths = nightConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImageN, name)
            } else {
                path
            }
        }
        val dayBackgroundImgPaths = dayConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImage, name)
            } else {
                path
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImage).listFiles()?.forEach {
            if (!dayBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImageN).listFiles()?.forEach {
            if (!nightBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String,
        var transparentNavBar: Boolean,
        var backgroundImgPath: String?,
        var backgroundImgBlur: Int,
        var backgroundImgCrop: String? = null,
        var bookInfoBackgroundImgPath: String? = null,
        var bookInfoBackgroundImgBlur: Int? = null,
        var panelBackgroundImgPath: String? = null,
        var panelBackgroundScaleType: String? = PANEL_BG_CROP,
        var panelBorderColor: String? = null,
        var panelBorderAlpha: Int? = null,
        var uiCornerScale: Float? = null,
        var uiLayoutAlpha: Int? = null,
        var dialogAlpha: Int? = null,
        var cardColor: String? = null,
        var mutedColor: String? = null,
        var searchFieldBackgroundColor: String? = null,
        var tabBackgroundColor: String? = null,
        var shelfColor: String? = null,
        var cardShadow: Int? = null,
        var cardBackgroundBlur: Float? = null,
        var uiCornerSearchFollow: Boolean? = null,
        var uiCornerReplyFollow: Boolean? = null,
        var fontScale: Int? = null,
        var uiFontPath: String? = null,
        var titleFontPath: String? = null,
        var uiFontColor: String? = null,
        var titleFontColor: String? = null
    ) {

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
                        && other.transparentNavBar == transparentNavBar
                        && other.backgroundImgPath == backgroundImgPath
                        && other.backgroundImgBlur == backgroundImgBlur
                        && other.backgroundImgCrop == backgroundImgCrop
                        && other.bookInfoBackgroundImgPath == bookInfoBackgroundImgPath
                        && other.bookInfoBackgroundImgBlur == bookInfoBackgroundImgBlur
                        && other.panelBackgroundImgPath == panelBackgroundImgPath
                        && other.panelBackgroundScaleType == panelBackgroundScaleType
                        && other.panelBorderColor == panelBorderColor
                        && other.panelBorderAlpha == panelBorderAlpha
                        && other.uiCornerScale == uiCornerScale
                        && other.uiLayoutAlpha == uiLayoutAlpha
                        && other.dialogAlpha == dialogAlpha
                        && other.cardColor == cardColor
                        && other.mutedColor == mutedColor
                        && other.searchFieldBackgroundColor == searchFieldBackgroundColor
                        && other.tabBackgroundColor == tabBackgroundColor
                        && other.shelfColor == shelfColor
                        && other.cardShadow == cardShadow
                        && other.cardBackgroundBlur == cardBackgroundBlur
                        && other.uiCornerSearchFollow == uiCornerSearchFollow
                        && other.uiCornerReplyFollow == uiCornerReplyFollow
                        && other.fontScale == fontScale
                        && other.uiFontPath == uiFontPath
                        && other.titleFontPath == titleFontPath
                        && other.uiFontColor == uiFontColor
                        && other.titleFontColor == titleFontColor
            }
            return false
        }

        fun toMap() = mapOf(
            "themeName" to themeName,
            "isNightTheme" to isNightTheme,
            "primaryColor" to primaryColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "transparentNavBar" to transparentNavBar,
            "backgroundImgPath" to backgroundImgPath,
            "backgroundImgBlur" to backgroundImgBlur,
            "backgroundImgCrop" to backgroundImgCrop,
            "bookInfoBackgroundImgPath" to bookInfoBackgroundImgPath,
            "bookInfoBackgroundImgBlur" to bookInfoBackgroundImgBlur,
            "panelBackgroundImgPath" to panelBackgroundImgPath,
            "panelBackgroundScaleType" to panelBackgroundScaleType,
            "panelBorderColor" to panelBorderColor,
            "panelBorderAlpha" to panelBorderAlpha,
            "uiCornerScale" to uiCornerScale,
            "uiLayoutAlpha" to uiLayoutAlpha,
            "dialogAlpha" to dialogAlpha,
            "cardColor" to cardColor,
            "mutedColor" to mutedColor,
            "searchFieldBackgroundColor" to searchFieldBackgroundColor,
            "tabBackgroundColor" to tabBackgroundColor,
            "shelfColor" to shelfColor,
            "cardShadow" to cardShadow,
            "cardBackgroundBlur" to cardBackgroundBlur,
            "uiCornerSearchFollow" to uiCornerSearchFollow,
            "uiCornerReplyFollow" to uiCornerReplyFollow,
            "fontScale" to fontScale,
            "uiFontPath" to uiFontPath,
            "titleFontPath" to titleFontPath,
            "uiFontColor" to uiFontColor,
            "titleFontColor" to titleFontColor
        )

        fun bookInfoBackgroundBlur(): Int {
            return bookInfoBackgroundImgBlur ?: DEFAULT_BOOK_INFO_BACKGROUND_BLUR
        }

    }

}
