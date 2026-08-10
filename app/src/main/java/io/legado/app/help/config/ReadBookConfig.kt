package io.legado.app.help.config

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.help.DefaultData
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getMeanColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.hexString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.resizeAndRecycle
import splitties.init.appCtx
import java.io.File
import androidx.core.graphics.drawable.toDrawable

/**
 * 阅读界面配置
 */
@Suppress("ConstPropertyName")
@Keep
object ReadBookConfig {
    const val configFileName = "readConfig.json"
    const val shareConfigFileName = "shareReadConfig.json"
    private const val activeReadConfigFileName = "activeReadConfig.json"
    private const val activeComicConfigFileName = "activeComicConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)
    val shareConfigFilePath = FileUtils.getPath(appCtx.filesDir, shareConfigFileName)
    private val activeReadConfigFilePath = FileUtils.getPath(appCtx.filesDir, activeReadConfigFileName)
    private val activeComicConfigFilePath = FileUtils.getPath(appCtx.filesDir, activeComicConfigFileName)
    val configList: ArrayList<Config> = arrayListOf()
    lateinit var shareConfig: Config
    private var activeConfig: Config? = null
        set(value) {
            if (value?.sanitize() == true) {
                needSaveSanitizedConfig = true
            }
            field = value
        }
    private var needSaveConfigList = false
    private var needSaveSanitizedConfig = false
    var durConfig
        get() = activeConfig ?: (loadActiveConfig(styleSelect) ?: getConfig(styleSelect).copy()).also {
            activeConfig = it
        }
        set(value) {
            activeConfig = value
            if (shareLayout) {
                shareConfig = value
            }
        }

    var isComic: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resetActiveConfig()
            } else {
                field = value
            }
        }
    var bg: Drawable? = null
    var bgMeanColor: Int = 0
    val textColor: Int get() = durConfig.curTextColor()
    val textAccentColor: Int get() = durConfig.curTextAccentColor()
    var isNineBgImg = false

    init {
        initConfigs()
        initShareConfig()
    }

    @Synchronized
    fun getConfig(index: Int): Config {
        val normalizedIndex = normalizeStyleIndex(index)
        return if (isBuiltInStyleIndex(normalizedIndex)) {
            DefaultData.readConfigs[normalizedIndex]
        } else {
            configList[customIndex(normalizedIndex)]
        }
    }

    val builtInStyleCount: Int
        get() = DefaultData.readConfigs.size

    val customStyleStartIndex: Int
        get() = builtInStyleCount

    val styleCount: Int
        get() = builtInStyleCount + configList.size

    fun customIndex(globalIndex: Int): Int {
        return globalIndex - customStyleStartIndex
    }

    fun customGlobalIndex(customIndex: Int): Int {
        return customStyleStartIndex + customIndex
    }

    fun isBuiltInStyleIndex(index: Int): Boolean {
        return index in 0 until builtInStyleCount
    }

    fun isCustomStyleIndex(index: Int): Boolean {
        return customIndex(index) in configList.indices
    }

    fun isValidStyleIndex(index: Int): Boolean {
        return isBuiltInStyleIndex(index) || isCustomStyleIndex(index)
    }

    fun normalizeStyleIndex(index: Int): Int {
        return when {
            isValidStyleIndex(index) -> index
            builtInStyleCount > 0 -> 0
            configList.isNotEmpty() -> customGlobalIndex(0)
            else -> 0
        }
    }

    fun allStyleConfigs(): List<IndexedValue<Config>> {
        val styles = arrayListOf<IndexedValue<Config>>()
        DefaultData.readConfigs.forEachIndexed { index, config ->
            styles.add(IndexedValue(index, config))
        }
        configList.forEachIndexed { index, config ->
            styles.add(IndexedValue(customGlobalIndex(index), config))
        }
        return styles
    }

    fun initConfigs() {
        val configFile = File(configFilePath)
        var configs: List<Config>? = null
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                configs = GSON.fromJsonArray<Config>(json).getOrThrow()
            } catch (e: Exception) {
                AppLog.put("读取排版配置文件出错", e)
            }
        }
        val normalizedConfigs = normalizeCustomConfigs(configs)
        configList.clear()
        configList.addAll(normalizedConfigs)
        activeConfig = loadActiveConfig(styleSelect)
    }

    fun initShareConfig() {
        val configFile = File(shareConfigFilePath)
        var c: Config? = null
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                c = GSON.fromJsonObject<Config>(json).getOrThrow()
            } catch (e: Exception) {
                e.printOnDebug()
            }
        }
        shareConfig = c ?: getConfig(5).copy()
        if (needSaveConfigList || needSaveSanitizedConfig) {
            needSaveConfigList = false
            needSaveSanitizedConfig = false
            save()
        }
    }

    fun repairConfigIfNeeded(): Boolean {
        var changed = false
        val normalizedReadStyle = normalizeStyleIndex(readStyleSelect)
        if (readStyleSelect != normalizedReadStyle) {
            readStyleSelect = normalizedReadStyle
            changed = true
        }
        val normalizedComicStyle = normalizeStyleIndex(comicStyleSelect)
        if (comicStyleSelect != normalizedComicStyle) {
            comicStyleSelect = normalizedComicStyle
            changed = true
        }
        if (changed) {
            save()
        }
        return changed
    }

    private fun normalizeCustomConfigs(configs: List<Config>?): List<Config> {
        if (configs == null) {
            return emptyList()
        }
        val defaultConfigs = DefaultData.readConfigs
        if (!isOldFormatConfigList(configs, defaultConfigs)) {
            return configs.map { it.copy() }
        }
        val normalized = arrayListOf<Config>()
        val names = defaultConfigs.map { it.name }.toHashSet()

        if (configs.size > defaultConfigs.size) {
            configs.drop(defaultConfigs.size).forEach { customConfig ->
                val config = customConfig.copy()
                config.name = uniqueCustomStyleName(config.name.ifBlank { "自定义" }, names)
                normalized.add(config)
                names.add(config.name)
            }
        }

        defaultConfigs.forEachIndexed { index, defaultConfig ->
            val savedConfig = configs.getOrNull(index) ?: return@forEachIndexed
            if (GSON.toJson(savedConfig) != GSON.toJson(defaultConfig)) {
                val customConfig = savedConfig.copy()
                customConfig.name = uniqueCustomStyleName(
                    customConfig.name.ifBlank { defaultConfig.name.ifBlank { "自定义" } },
                    names
                )
                normalized.add(customConfig)
                names.add(customConfig.name)
            }
        }

        needSaveConfigList = true
        return normalized
    }

    private fun isOldFormatConfigList(configs: List<Config>, defaultConfigs: List<Config>): Boolean {
        if (configs.size < defaultConfigs.size) {
            return false
        }
        return defaultConfigs.indices.any { index ->
            val savedConfig = configs.getOrNull(index) ?: return@any false
            savedConfig.name == defaultConfigs[index].name ||
                GSON.toJson(savedConfig.copy(name = defaultConfigs[index].name)) ==
                GSON.toJson(defaultConfigs[index])
        }
    }

    private fun uniqueCustomStyleName(baseName: String, names: Set<String>): String {
        if (!names.contains(baseName)) {
            return baseName
        }
        var index = 1
        var name = "$baseName($index)"
        while (names.contains(name)) {
            index++
            name = "$baseName($index)"
        }
        return name
    }

    fun upBg(width: Int, height: Int) {
        val drawable = durConfig.curBgDrawable(width, height)
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            bgMeanColor = drawable.bitmap.getMeanColor()
        } else if (drawable is ColorDrawable) {
            bgMeanColor = drawable.color
        }
        val tmp = bg
        bg = drawable
        if (tmp is BitmapDrawable) { //太快执行，可能还正在被使用，延时防崩溃
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                tmp.bitmap?.recycle()
            }
        }
    }

    fun save() {
        val activeConfigFilePath = if (isComic) activeComicConfigFilePath else activeReadConfigFilePath
        val activeConfigState = ActiveConfigState(styleSelect, durConfig.copy())
        Coroutine.async {
            synchronized(this) {
                GSON.toJson(configList).let {
                    FileUtils.delete(configFilePath)
                    FileUtils.createFileIfNotExist(configFilePath).writeText(it)
                }
                GSON.toJson(shareConfig).let {
                    FileUtils.delete(shareConfigFilePath)
                    FileUtils.createFileIfNotExist(shareConfigFilePath).writeText(it)
                }
                GSON.toJson(activeConfigState).let {
                    FileUtils.delete(activeConfigFilePath)
                    FileUtils.createFileIfNotExist(activeConfigFilePath).writeText(it)
                }
            }
        }
    }

    fun getAllPicBgStr(): ArrayList<String> {
        val list = arrayListOf<String>()
        configList.forEach {
            if (it.bgType == 2) {
                list.add(it.bgStr)
            }
            if (it.bgTypeNight == 2) {
                list.add(it.bgStrNight)
            }
            if (it.bgTypeEInk == 2) {
                list.add(it.bgStrEInk)
            }
        }
        return list
    }

    fun deleteDur(): Boolean {
        return deleteAt(styleSelect)
    }

    fun deleteAt(index: Int): Boolean {
        if (!isCustomStyleIndex(index)) {
            return false
        }
        val customIndex = customIndex(index)
        val deleteCurrent = index == styleSelect
        configList.removeAt(customIndex)
        if (index < readStyleSelect) {
            readStyleSelect -= 1
        } else if (index == readStyleSelect) {
            readStyleSelect = normalizeStyleIndex(0)
        }
        if (index < comicStyleSelect) {
            comicStyleSelect -= 1
        } else if (index == comicStyleSelect) {
            comicStyleSelect = normalizeStyleIndex(0)
        }
        readStyleSelect = normalizeStyleIndex(readStyleSelect)
        comicStyleSelect = normalizeStyleIndex(comicStyleSelect)
        if (deleteCurrent) {
            resetActiveConfig()
        }
        return true
    }

    fun resetActiveConfig() {
        activeConfig = loadActiveConfig(styleSelect) ?: getConfig(styleSelect).copy()
        if (shareLayout) {
            shareConfig = activeConfig ?: shareConfig
        }
    }

    fun setActiveConfig(config: Config, selectedIndex: Int = styleSelect) {
        styleSelect = selectedIndex
        activeConfig = config
        if (shareLayout) {
            shareConfig = config
        }
    }

    fun clearBgAndCache() {
        val bgs = hashSetOf<String>()
        configList.forEach { config ->
            repeat(3) {
                config.getBgPath(it)?.let { path ->
                    bgs.add(path)
                }
            }
        }
        activeConfig?.let { config ->
            repeat(3) {
                config.getBgPath(it)?.let { path ->
                    bgs.add(path)
                }
            }
        }
        appCtx.externalFiles.getFile("bg").listFiles()?.forEach {
            if (!bgs.contains(it.absolutePath)) {
                it.delete()
            }
        }
        FileUtils.delete(appCtx.externalCache.getFile("readConfig"))
        val configZipPath = FileUtils.getPath(appCtx.externalCache, "readConfig.zip")
        FileUtils.delete(configZipPath)
    }

    private fun resetAll() {
        configList.clear()
        activeConfig = null
        readStyleSelect = normalizeStyleIndex(readStyleSelect)
        comicStyleSelect = normalizeStyleIndex(comicStyleSelect)
        FileUtils.delete(activeReadConfigFilePath)
        FileUtils.delete(activeComicConfigFilePath)
        save()
    }

    private fun loadActiveConfig(selectedIndex: Int): Config? {
        val activeConfigFilePath = if (isComic) activeComicConfigFilePath else activeReadConfigFilePath
        val configFile = File(activeConfigFilePath)
        if (!configFile.exists()) {
            return null
        }
        return try {
            val state = GSON.fromJsonObject<ActiveConfigState>(configFile.readText()).getOrThrow()
            if (state.styleIndex != normalizeStyleIndex(selectedIndex)) {
                return null
            }
            state.config
        } catch (e: Exception) {
            AppLog.put("读取当前排版配置出错", e)
            null
        }
    }

    //配置写入读取
    var readBodyToLh = appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)
    var autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 10)
        set(value) {
            field = value
            appCtx.putPrefInt(PreferKey.autoReadSpeed, value)
        }
    var autoReadMode = appCtx.getPrefInt(PreferKey.autoReadMode, AUTO_READ_MODE_SCROLL)
        set(value) {
            field = value
            appCtx.putPrefInt(PreferKey.autoReadMode, value)
        }
    var styleSelect: Int
        get() = normalizeStyleIndex(if (isComic) comicStyleSelect else readStyleSelect)
        set(value) {
            val normalizedValue = normalizeStyleIndex(value)
            if (isComic) {
                comicStyleSelect = normalizedValue
            } else {
                readStyleSelect = normalizedValue
            }
            resetActiveConfig()
        }
    var readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
        set(value) {
            field = value
            if (appCtx.getPrefInt(PreferKey.readStyleSelect) != value) {
                appCtx.putPrefInt(PreferKey.readStyleSelect, value)
            }
        }
    var comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect, readStyleSelect)
        set(value) {
            field = value
            if (appCtx.getPrefInt(PreferKey.comicStyleSelect) != value) {
                appCtx.putPrefInt(PreferKey.comicStyleSelect, value)
            }
        }
    // var shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
    //     set(value) {
    //         field = value
    //         if (appCtx.getPrefBoolean(PreferKey.shareLayout) != value) {
    //             appCtx.putPrefBoolean(PreferKey.shareLayout, value)
    //         }
    //     }
    var shareLayout = false
        set(value) {
            field = false
        }

    /**
     * 两端对齐
     */
    val textFullJustify get() = appCtx.getPrefBoolean(PreferKey.textFullJustify, true)

    /**
     * 底部对齐
     */
    val textBottomJustify get() = appCtx.getPrefBoolean(PreferKey.textBottomJustify, true)

    var hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
    var hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
    var useZhLayout = appCtx.getPrefBoolean(PreferKey.useZhLayout)

    val config get() = if (shareLayout) shareConfig else durConfig

    var bgAlpha: Int
        get() = config.bgAlpha
        set(value) {
            config.bgAlpha = value
        }

    var pageAnim: Int
        get() = config.curPageAnim()
        set(@PageAnim.Anim value) {
            config.setCurPageAnim(value)
        }

    const val AUTO_READ_MODE_SCROLL = 0
    const val AUTO_READ_MODE_TIMED = 1

    var textFont: String
        get() = config.textFont
        set(value) {
            config.textFont = value
        }

    var textBold: Int
        get() = config.textBold
        set(value) {
            config.textBold = value
        }

    var textSize: Int
        get() = config.textSize
        set(value) {
            config.textSize = value
        }

    var letterSpacing: Float
        get() = config.letterSpacing
        set(value) {
            config.letterSpacing = value
        }

    var lineSpacingExtra: Int
        get() = config.lineSpacingExtra
        set(value) {
            config.lineSpacingExtra = value
        }

    var paragraphSpacing: Int
        get() = config.paragraphSpacing
        set(value) {
            config.paragraphSpacing = value
        }

    var paperInkStrength: Int
        get() = config.curPaperInkStrength()
        set(value) {
            config.paperInkStrength = value.coerceIn(0, 100)
            config.paperEffect = false
        }

    /**
     * 标题位置 0:居左 1:居中 2:隐藏
     */
    var titleMode: Int
        get() = config.titleMode
        set(value) {
            config.titleMode = value
        }
    var titleSize: Int
        get() = config.titleSize
        set(value) {
            config.titleSize = value
        }

    /**
     * 是否标题居中
     */
    val isMiddleTitle get() = titleMode == 1

    var titleTopSpacing: Int
        get() = config.titleTopSpacing
        set(value) {
            config.titleTopSpacing = value
        }

    var titleBottomSpacing: Int
        get() = config.titleBottomSpacing
        set(value) {
            config.titleBottomSpacing = value
        }

    var paragraphIndent: String
        get() = config.paragraphIndent
        set(value) {
            config.paragraphIndent = value
        }

    var underlineMode: Int
        get() = config.underlineMode
        set(value) {
            config.underlineMode = value
        }

    var paddingBottom: Int
        get() = config.paddingBottom
        set(value) {
            config.paddingBottom = value
        }

    var paddingLeft: Int
        get() = config.paddingLeft
        set(value) {
            config.paddingLeft = value
        }

    var paddingRight: Int
        get() = config.paddingRight
        set(value) {
            config.paddingRight = value
        }

    var paddingTop: Int
        get() = config.paddingTop
        set(value) {
            config.paddingTop = value
        }

    var headerPaddingBottom: Int
        get() = config.headerPaddingBottom
        set(value) {
            config.headerPaddingBottom = value
        }

    var headerPaddingLeft: Int
        get() = config.headerPaddingLeft
        set(value) {
            config.headerPaddingLeft = value
        }

    var headerPaddingRight: Int
        get() = config.headerPaddingRight
        set(value) {
            config.headerPaddingRight = value
        }

    var headerPaddingTop: Int
        get() = config.headerPaddingTop
        set(value) {
            config.headerPaddingTop = value
        }

    var footerPaddingBottom: Int
        get() = config.footerPaddingBottom
        set(value) {
            config.footerPaddingBottom = value
        }

    var footerPaddingLeft: Int
        get() = config.footerPaddingLeft
        set(value) {
            config.footerPaddingLeft = value
        }

    var footerPaddingRight: Int
        get() = config.footerPaddingRight
        set(value) {
            config.footerPaddingRight = value
        }

    var footerPaddingTop: Int
        get() = config.footerPaddingTop
        set(value) {
            config.footerPaddingTop = value
        }

    var showHeaderLine: Boolean
        get() = config.showHeaderLine
        set(value) {
            config.showHeaderLine = value
        }

    var showFooterLine: Boolean
        get() = config.showFooterLine
        set(value) {
            config.showFooterLine = value
        }

    fun getExportConfig(): Config {
        val exportConfig = durConfig.copy()
        if (shareLayout) {
            exportConfig.textFont = shareConfig.textFont
            exportConfig.textBold = shareConfig.textBold
            exportConfig.textSize = shareConfig.textSize
            exportConfig.letterSpacing = shareConfig.letterSpacing
            exportConfig.lineSpacingExtra = shareConfig.lineSpacingExtra
            exportConfig.paragraphSpacing = shareConfig.paragraphSpacing
            exportConfig.paperEffect = shareConfig.paperEffect
            exportConfig.paperInkStrength = shareConfig.paperInkStrength
            exportConfig.titleMode = shareConfig.titleMode
            exportConfig.titleSize = shareConfig.titleSize
            exportConfig.titleTopSpacing = shareConfig.titleTopSpacing
            exportConfig.titleBottomSpacing = shareConfig.titleBottomSpacing
            exportConfig.paddingBottom = shareConfig.paddingBottom
            exportConfig.paddingLeft = shareConfig.paddingLeft
            exportConfig.paddingRight = shareConfig.paddingRight
            exportConfig.paddingTop = shareConfig.paddingTop
            exportConfig.headerPaddingBottom = shareConfig.headerPaddingBottom
            exportConfig.headerPaddingLeft = shareConfig.headerPaddingLeft
            exportConfig.headerPaddingRight = shareConfig.headerPaddingRight
            exportConfig.headerPaddingTop = shareConfig.headerPaddingTop
            exportConfig.footerPaddingBottom = shareConfig.footerPaddingBottom
            exportConfig.footerPaddingLeft = shareConfig.footerPaddingLeft
            exportConfig.footerPaddingRight = shareConfig.footerPaddingRight
            exportConfig.footerPaddingTop = shareConfig.footerPaddingTop
            exportConfig.showHeaderLine = shareConfig.showHeaderLine
            exportConfig.showFooterLine = shareConfig.showFooterLine
            exportConfig.tipHeaderLeft = shareConfig.tipHeaderLeft
            exportConfig.tipHeaderMiddle = shareConfig.tipHeaderMiddle
            exportConfig.tipHeaderRight = shareConfig.tipHeaderRight
            exportConfig.tipFooterLeft = shareConfig.tipFooterLeft
            exportConfig.tipFooterMiddle = shareConfig.tipFooterMiddle
            exportConfig.tipFooterRight = shareConfig.tipFooterRight
            exportConfig.tipColor = shareConfig.tipColor
            exportConfig.headerMode = shareConfig.headerMode
            exportConfig.footerMode = shareConfig.footerMode
        }
        return exportConfig
    }

    fun import(byteArray: ByteArray): Config {
        val configZipPath = FileUtils.getPath(appCtx.externalCache, "readConfig.zip")
        FileUtils.delete(configZipPath)
        val zipFile = FileUtils.createFileIfNotExist(configZipPath)
        zipFile.writeBytes(byteArray)
        val configDir = appCtx.externalCache.getFile("readConfig")
        configDir.createFolderReplace()
        ZipUtils.unZipToPath(zipFile, configDir)
        val configFile = configDir.getFile(configFileName)
        val config: Config = GSON.fromJsonObject<Config>(configFile.readText()).getOrThrow()
        if (config.textFont.isNotEmpty()) {
            val fontName = config.textFont
            val fontPath =
                FileUtils.getPath(appCtx.externalFiles, "font", fontName)
            val fontFile = configDir.getFile(fontName)
            if (fontFile.exists()) {
                if (!FileUtils.exist(fontPath)) {
                    fontFile.copyTo(File(fontPath))
                }
                config.textFont = fontPath
            } else {
                config.textFont = ""
            }
        }
        if (config.bgType == 2) {
            val bgName = FileUtils.getName(config.bgStr)
            config.bgStr = bgName
            val bgPath = FileUtils.getPath(appCtx.externalFiles, "bg", bgName)
            if (!FileUtils.exist(bgPath)) {
                val bgFile = configDir.getFile(bgName)
                if (bgFile.exists()) {
                    bgFile.copyTo(File(bgPath))
                }
            }
            config.bgStr = bgPath
        }
        if (config.bgTypeNight == 2) {
            val bgName = FileUtils.getName(config.bgStrNight)
            config.bgStrNight = bgName
            val bgPath = FileUtils.getPath(appCtx.externalFiles, "bg", bgName)
            if (!FileUtils.exist(bgPath)) {
                val bgFile = configDir.getFile(bgName)
                if (bgFile.exists()) {
                    bgFile.copyTo(File(bgPath))
                }
            }
            config.bgStrNight = bgPath
        }
        if (config.bgTypeEInk == 2) {
            val bgName = FileUtils.getName(config.bgStrEInk)
            config.bgStrEInk = bgName
            val bgPath = FileUtils.getPath(appCtx.externalFiles, "bg", bgName)
            if (!FileUtils.exist(bgPath)) {
                val bgFile = configDir.getFile(bgName)
                if (bgFile.exists()) {
                    bgFile.copyTo(File(bgPath))
                }
            }
            config.bgStrEInk = bgPath
        }
        config.curTextColor()
        config.curTextAccentColor()
        return config
    }

    @Keep
    data class ActiveConfigState(
        val styleIndex: Int = 0,
        val config: Config = Config()
    )

    @Keep
    data class Config(
        var name: String = "",
        var bgStr: String = "#EEEEEE",//白天背景
        var bgStrNight: String = "#000000",//夜间背景
        var bgStrEInk: String = "#FFFFFF",//EInk背景
        var bgAlpha: Int = 100,//背景透明度
        var bgType: Int = 0,//白天背景类型 0:颜色, 1:assets图片, 2其它图片
        var bgTypeNight: Int = 0,//夜间背景类型
        var bgTypeEInk: Int = 0,//EInk背景类型
        private var darkStatusIcon: Boolean = true,//白天是否暗色状态栏
        private var darkStatusIconNight: Boolean = false,//晚上是否暗色状态栏
        private var darkStatusIconEInk: Boolean = true,
        private var textColor: String = "#3E3D3B",//白天文字颜色
        private var textColorNight: String = "#ADADAD",//夜间文字颜色
        private var textColorEInk: String = "#000000",
        private var textAccentColor: String = "#E53935",//白天强调文字颜色
        private var textAccentColorNight: String = "#FE4D55",//夜间强调文字颜色
        private var textAccentColorEInk: String = "#000000",
        private var readMenuBgColor: String? = "",
        private var readMenuBgColorNight: String? = "",
        private var readMenuBgColorEInk: String? = "",
        var readMenuAlpha: Int = 100,
        private var readScrollFollowBackground: Boolean = false,
        private var readScrollFollowBackgroundNight: Boolean = false,
        private var readScrollFollowBackgroundEInk: Boolean = false,
        private var pageAnim: Int = 0,//翻页动画
        private var pageAnimEInk: Int = 4,
        var textFont: String = "",//字体
        var textBold: Int = 0,//是否粗体字 0:正常, 1:粗体, 2:细体
        var textSize: Int = 20,//文字大小
        var letterSpacing: Float = 0.1f,//字间距
        var lineSpacingExtra: Int = 12,//行间距
        var paragraphSpacing: Int = 2,//段距
        var paperEffect: Boolean = false,//纸质化
        var paperInkStrength: Int = 0,//纸墨融合强度
        var titleMode: Int = 0,//标题位置 0:居左 1:居中 2:隐藏
        var titleSize: Int = 0,
        var titleTopSpacing: Int = 0,
        var titleBottomSpacing: Int = 0,
        var paragraphIndent: String = "　　",//段落缩进
        var underlineMode: Int = 0, //下划线
        var paddingBottom: Int = 6,
        var paddingLeft: Int = 16,
        var paddingRight: Int = 16,
        var paddingTop: Int = 6,
        var headerPaddingBottom: Int = 0,
        var headerPaddingLeft: Int = 16,
        var headerPaddingRight: Int = 16,
        var headerPaddingTop: Int = 0,
        var footerPaddingBottom: Int = 6,
        var footerPaddingLeft: Int = 16,
        var footerPaddingRight: Int = 16,
        var footerPaddingTop: Int = 6,
        var showHeaderLine: Boolean = false,
        var showFooterLine: Boolean = true,
        var tipHeaderLeft: Int = ReadTipConfig.time,
        var tipHeaderMiddle: Int = ReadTipConfig.none,
        var tipHeaderRight: Int = ReadTipConfig.battery,
        var tipFooterLeft: Int = ReadTipConfig.chapterTitle,
        var tipFooterMiddle: Int = ReadTipConfig.none,
        var tipFooterRight: Int = ReadTipConfig.pageAndTotal,
        var tipColor: Int = 0,
        var tipDividerColor: Int = -1,
        var headerMode: Int = 0,
        var footerMode: Int = 0
    ) {

        fun sanitize(): Boolean {
            var changed = false

            fun updateInt(current: Int, value: Int, setValue: (Int) -> Unit) {
                if (current != value) {
                    setValue(value)
                    changed = true
                }
            }

            fun updateFloat(current: Float, value: Float, setValue: (Float) -> Unit) {
                if (current != value) {
                    setValue(value)
                    changed = true
                }
            }

            fun normalizeBgPath(
                bgType: Int,
                bgStr: String,
                defaultBg: String,
                setType: (Int) -> Unit,
                setValue: (String) -> Unit
            ) {
                if (bgType != 2) {
                    return
                }
                val localPath = FileUtils.getPath(appCtx.externalFiles, "bg", FileUtils.getName(bgStr))
                val normalizedPath = when {
                    FileUtils.exist(localPath) -> localPath
                    !bgStr.contains(File.separator) && FileUtils.exist(bgStr) -> bgStr
                    else -> null
                }
                if (normalizedPath == null) {
                    setType(0)
                    setValue(defaultBg)
                    changed = true
                    return
                }
                if (bgStr != normalizedPath) {
                    setValue(normalizedPath)
                    changed = true
                }
            }

            fun normalizeFontPath() {
                if (textFont.isBlank()) {
                    return
                }
                if (textFont.isContentScheme()) {
                    val canRead = runCatching {
                        appCtx.contentResolver.openFileDescriptor(textFont.toUri(), "r")?.use { true } == true
                    }.getOrDefault(false)
                    if (!canRead) {
                        textFont = ""
                        changed = true
                    }
                    return
                }

                val normalizedPath = if (textFont.contains(File.separator)) {
                    textFont
                } else {
                    FileUtils.getPath(appCtx.externalFiles, "font", FileUtils.getName(textFont))
                }
                if (FileUtils.exist(normalizedPath)) {
                    if (textFont != normalizedPath) {
                        textFont = normalizedPath
                        changed = true
                    }
                } else {
                    textFont = ""
                    changed = true
                }
            }

            updateInt(bgAlpha, bgAlpha.coerceIn(0, 100)) { bgAlpha = it }
            updateInt(bgType, bgType.coerceIn(0, 2)) { bgType = it }
            updateInt(bgTypeNight, bgTypeNight.coerceIn(0, 2)) { bgTypeNight = it }
            updateInt(bgTypeEInk, bgTypeEInk.coerceIn(0, 2)) { bgTypeEInk = it }
            normalizeBgPath(bgType, bgStr, "#EEEEEE", { bgType = it }) { bgStr = it }
            normalizeBgPath(bgTypeNight, bgStrNight, "#000000", { bgTypeNight = it }) { bgStrNight = it }
            normalizeBgPath(bgTypeEInk, bgStrEInk, "#FFFFFF", { bgTypeEInk = it }) { bgStrEInk = it }
            normalizeFontPath()
            updateInt(readMenuAlpha, readMenuAlpha.coerceIn(35, 100)) { readMenuAlpha = it }
            updateInt(pageAnim, pageAnim.coerceIn(PageAnim.coverPageAnim, PageAnim.linkedCoverPageAnim)) {
                pageAnim = it
            }
            updateInt(pageAnimEInk, pageAnimEInk.coerceIn(PageAnim.coverPageAnim, PageAnim.linkedCoverPageAnim)) {
                pageAnimEInk = it
            }
            updateInt(textBold, textBold.coerceIn(0, 2)) { textBold = it }
            updateInt(textSize, textSize.coerceIn(5, 50)) { textSize = it }
            updateFloat(letterSpacing, letterSpacing.coerceIn(-0.5f, 0.5f)) { letterSpacing = it }
            updateInt(lineSpacingExtra, lineSpacingExtra.coerceIn(0, 20)) { lineSpacingExtra = it }
            updateInt(paragraphSpacing, paragraphSpacing.coerceIn(0, 20)) { paragraphSpacing = it }
            updateInt(paperInkStrength, paperInkStrength.coerceIn(0, 100)) { paperInkStrength = it }
            updateInt(titleMode, titleMode.coerceIn(0, AdvancedTitleConfig.TITLE_MODE_ADVANCED)) {
                titleMode = it
            }
            updateInt(titleSize, titleSize.coerceIn(0, 20)) { titleSize = it }
            updateInt(titleTopSpacing, titleTopSpacing.coerceIn(0, 100)) { titleTopSpacing = it }
            updateInt(titleBottomSpacing, titleBottomSpacing.coerceIn(0, 100)) { titleBottomSpacing = it }
            updateInt(underlineMode, underlineMode.coerceAtLeast(0)) { underlineMode = it }
            updateInt(paddingTop, paddingTop.coerceIn(0, 200)) { paddingTop = it }
            updateInt(paddingBottom, paddingBottom.coerceIn(0, 100)) { paddingBottom = it }
            updateInt(paddingLeft, paddingLeft.coerceIn(0, 100)) { paddingLeft = it }
            updateInt(paddingRight, paddingRight.coerceIn(0, 100)) { paddingRight = it }
            updateInt(headerPaddingTop, headerPaddingTop.coerceIn(0, 100)) { headerPaddingTop = it }
            updateInt(headerPaddingBottom, headerPaddingBottom.coerceIn(0, 100)) { headerPaddingBottom = it }
            updateInt(headerPaddingLeft, headerPaddingLeft.coerceIn(0, 100)) { headerPaddingLeft = it }
            updateInt(headerPaddingRight, headerPaddingRight.coerceIn(0, 100)) { headerPaddingRight = it }
            updateInt(footerPaddingTop, footerPaddingTop.coerceIn(0, 100)) { footerPaddingTop = it }
            updateInt(footerPaddingBottom, footerPaddingBottom.coerceIn(0, 100)) { footerPaddingBottom = it }
            updateInt(footerPaddingLeft, footerPaddingLeft.coerceIn(0, 100)) { footerPaddingLeft = it }
            updateInt(footerPaddingRight, footerPaddingRight.coerceIn(0, 100)) { footerPaddingRight = it }
            updateInt(headerMode, headerMode.coerceIn(0, 2)) { headerMode = it }
            updateInt(footerMode, footerMode.coerceIn(0, 1)) { footerMode = it }

            return changed
        }

        @Transient
        private var textColorIntEInk = -1

        @Transient
        private var textColorIntNight = -1

        @Transient
        private var textColorInt = -1

        @Transient
        private var initColorInt = false

        private fun initColorInt() {
            textColorIntEInk = colorOrDefault(textColorEInk, "#000000")
            textColorIntNight = colorOrDefault(textColorNight, "#ADADAD")
            textColorInt = colorOrDefault(textColor, "#3E3D3B")
            initColorInt = true
        }

        @Transient
        private var textAccentColorIntEInk = -1

        @Transient
        private var textAccentColorIntNight = -1

        @Transient
        private var textAccentColorInt = -1

        @Transient
        private var initAccentColorInt = false

        private fun initAccentColorInt() {
            textAccentColorIntEInk = colorOrDefault(textAccentColorEInk, "#000000")
            textAccentColorIntNight = colorOrDefault(textAccentColorNight, "#FE4D55")
            textAccentColorInt = colorOrDefault(textAccentColor, "#E53935")
            initAccentColorInt = true
        }

        fun setCurReadMenuBgColor(color: Int) {
            when {
                AppConfig.isEInkMode -> readMenuBgColorEInk = "#${color.hexString}"
                AppConfig.isNightTheme -> readMenuBgColorNight = "#${color.hexString}"
                else -> readMenuBgColor = "#${color.hexString}"
            }
        }

        fun clearCurReadMenuBgColor() {
            when {
                AppConfig.isEInkMode -> readMenuBgColorEInk = ""
                AppConfig.isNightTheme -> readMenuBgColorNight = ""
                else -> readMenuBgColor = ""
            }
        }

        fun curReadMenuBgColor(): Int? {
            val color = when {
                AppConfig.isEInkMode -> readMenuBgColorEInk
                AppConfig.isNightTheme -> readMenuBgColorNight
                else -> readMenuBgColor
            }
            return colorOrNull(color)
        }

        fun setCurTextColor(color: Int) {
            when {
                AppConfig.isEInkMode -> {
                    textColorEInk = "#${color.hexString}"
                    textColorIntEInk = color
                }

                AppConfig.isNightTheme -> {
                    textColorNight = "#${color.hexString}"
                    textColorIntNight = color
                }

                else -> {
                    textColor = "#${color.hexString}"
                    textColorInt = color
                }
            }
        }

        fun curTextColor(): Int {
            if (!initColorInt) {
                initColorInt()
            }
            return when {
                AppConfig.isEInkMode -> textColorIntEInk
                AppConfig.isNightTheme -> textColorIntNight
                else -> textColorInt
            }
        }

        fun setCurTextAccentColor(color: Int) {
            when {
                AppConfig.isEInkMode -> {
                    textAccentColorEInk = "#${color.hexString}"
                    textAccentColorIntEInk = color
                }

                AppConfig.isNightTheme -> {
                    textAccentColorNight = "#${color.hexString}"
                    textAccentColorIntNight = color
                }

                else -> {
                    textAccentColor = "#${color.hexString}"
                    textAccentColorInt = color
                }
            }
        }

        fun curTextAccentColor(): Int {
            if (!initAccentColorInt) {
                initAccentColorInt()
            }
            return when {
                AppConfig.isEInkMode -> textAccentColorIntEInk
                AppConfig.isNightTheme -> textAccentColorIntNight
                else -> textAccentColorInt
            }
        }

        fun setCurStatusIconDark(isDark: Boolean) {
            when {
                AppConfig.isEInkMode -> darkStatusIconEInk = isDark
                AppConfig.isNightTheme -> darkStatusIconNight = isDark
                else -> darkStatusIcon = isDark
            }
        }

        fun curStatusIconDark(): Boolean {
            return when {
                AppConfig.isEInkMode -> darkStatusIconEInk
                AppConfig.isNightTheme -> darkStatusIconNight
                else -> darkStatusIcon
            }
        }

        fun setCurPageAnim(@PageAnim.Anim anim: Int) {
            when {
                AppConfig.isEInkMode -> pageAnimEInk = anim
                else -> pageAnim = anim
            }
        }

        fun curPageAnim(): Int {
            return when {
                AppConfig.isEInkMode -> pageAnimEInk
                else -> pageAnim
            }
        }

        fun setCurBg(bgType: Int, bg: String) {
            when {
                AppConfig.isEInkMode -> {
                    bgTypeEInk = bgType
                    bgStrEInk = bg
                }

                AppConfig.isNightTheme -> {
                    bgTypeNight = bgType
                    bgStrNight = bg
                }

                else -> {
                    this.bgType = bgType
                    bgStr = bg
                }
            }
        }

        fun curBgStr(): String {
            return when {
                AppConfig.isEInkMode -> bgStrEInk
                AppConfig.isNightTheme -> bgStrNight
                else -> bgStr
            }
        }

        fun curBgColor(): Int {
            return when {
                AppConfig.isEInkMode -> colorOrDefault(bgStrEInk, "#FFFFFF")
                AppConfig.isNightTheme -> colorOrDefault(bgStrNight, "#000000")
                else -> colorOrDefault(bgStr, "#EEEEEE")
            }
        }

        fun curBgType(): Int {
            return when {
                AppConfig.isEInkMode -> bgTypeEInk
                AppConfig.isNightTheme -> bgTypeNight
                else -> bgType
            }
        }

        fun curReadScrollFollowBackground(): Boolean {
            return when {
                AppConfig.isEInkMode -> readScrollFollowBackgroundEInk
                AppConfig.isNightTheme -> readScrollFollowBackgroundNight
                else -> readScrollFollowBackground
            }
        }

        fun setCurReadScrollFollowBackground(value: Boolean) {
            when {
                AppConfig.isEInkMode -> readScrollFollowBackgroundEInk = value
                AppConfig.isNightTheme -> readScrollFollowBackgroundNight = value
                else -> readScrollFollowBackground = value
            }
        }

        fun curPaperInkStrength(): Int {
            return paperInkStrength.takeIf { it > 0 } ?: if (paperEffect) 60 else 0
        }

        fun curBgDrawable(width: Int, height: Int): Drawable {
            val curBgStr = curBgStr()
            isNineBgImg = curBgStr.endsWith(".9.png")
            if (width == 0 || height == 0) {
                return appCtx.getCompatColor(R.color.background).toDrawable()
            }
            var bgDrawable: Drawable? = null
            val resources = appCtx.resources
            try {
                bgDrawable = when (curBgType()) {
                    0 -> curBgColor().toDrawable()
                    1 -> {
                        val path = "bg" + File.separator + curBgStr
                        val bitmap = BitmapUtils.decodeAssetsBitmap(appCtx, path, width, height)
                        bitmap?.resizeAndRecycle(width, height)?.toDrawable(resources)
                    }

                    else -> {
                        val path = curBgStr.let {
                            if (it.contains(File.separator)) it
                            else FileUtils.getPath(appCtx.externalFiles, "bg", it)
                        }
                        if (isNineBgImg) {
                            BitmapUtils.decodeNinePatchDrawable(path)
                        } else {
                            val bitmap = BitmapUtils.decodeBitmap(path, width, height)
                            bitmap?.resizeAndRecycle(width, height)?.toDrawable(resources)
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                e.printOnDebug()
            } catch (e: Exception) {
                e.printOnDebug()
            }
            return bgDrawable ?: appCtx.getCompatColor(R.color.background).toDrawable()
        }

        private fun colorOrNull(color: String?): Int? {
            return color
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { it.toColorInt() }.getOrNull() }
        }

        private fun colorOrDefault(color: String?, default: String): Int {
            return colorOrNull(color) ?: default.toColorInt()
        }

        fun getBgPath(bgIndex: Int): String? {
            val bgType = when (bgIndex) {
                0 -> bgType
                1 -> bgTypeNight
                2 -> bgTypeEInk
                else -> error("unknown bgIndex: $bgIndex")
            }
            if (bgType != 2) {
                return null
            }
            val bgStr = when (bgIndex) {
                0 -> bgStr
                1 -> bgStrNight
                2 -> bgStrEInk
                else -> error("unknown bgIndex: $bgIndex")
            }
            val path = if (bgStr.contains(File.separator)) {
                bgStr
            } else {
                FileUtils.getPath(appCtx.externalFiles, "bg", bgStr)
            }
            return path
        }

        fun toMap() = mapOf(
            "name" to name,
            "bgStr" to bgStr,
            "bgStrNight" to bgStrNight,
            "bgStrEInk" to bgStrEInk,
            "bgAlpha" to bgAlpha,
            "bgType" to bgType,
            "bgTypeNight" to bgTypeNight,
            "bgTypeEInk" to bgTypeEInk,
            "darkStatusIcon" to darkStatusIcon,
            "darkStatusIconNight" to darkStatusIconNight,
            "darkStatusIconEInk" to darkStatusIconEInk,
            "textColor" to textColor,
            "textColorNight" to textColorNight,
            "textColorEInk" to textColorEInk,
            "textColorInt" to textColorInt,
            "textColorIntNight" to textColorIntNight,
            "textColorIntEInk" to textColorIntEInk,
            "textAccentColor" to textAccentColor,
            "textAccentColorNight" to textAccentColorNight,
            "textAccentColorEInk" to textAccentColorEInk,
            "readMenuBgColor" to readMenuBgColor.orEmpty(),
            "readMenuBgColorNight" to readMenuBgColorNight.orEmpty(),
            "readMenuBgColorEInk" to readMenuBgColorEInk.orEmpty(),
            "readMenuAlpha" to readMenuAlpha,
            "readScrollFollowBackground" to readScrollFollowBackground,
            "readScrollFollowBackgroundNight" to readScrollFollowBackgroundNight,
            "readScrollFollowBackgroundEInk" to readScrollFollowBackgroundEInk,
            "textAccentColorInt" to textAccentColorInt,
            "textAccentColorIntNight" to textAccentColorIntNight,
            "textAccentColorIntEInk" to textAccentColorIntEInk,
            "pageAnim" to pageAnim,
            "pageAnimEInk" to pageAnimEInk,
            "textFont" to textFont,
            "textBold" to textBold,
            "textSize" to textSize,
            "letterSpacing" to letterSpacing,
            "lineSpacingExtra" to lineSpacingExtra,
            "paragraphSpacing" to paragraphSpacing,
            "paperEffect" to paperEffect,
            "paperInkStrength" to paperInkStrength,
            "titleMode" to titleMode,
            "titleSize" to titleSize,
            "titleTopSpacing" to titleTopSpacing,
            "titleBottomSpacing" to titleBottomSpacing,
            "paragraphIndent" to paragraphIndent,
            "underlineMode" to underlineMode,
            "paddingBottom" to paddingBottom,
            "paddingLeft" to paddingLeft,
            "paddingRight" to paddingRight,
            "paddingTop" to paddingTop,
            "headerPaddingBottom" to headerPaddingBottom,
            "headerPaddingLeft" to headerPaddingLeft,
            "headerPaddingRight" to headerPaddingRight,
            "headerPaddingTop" to headerPaddingTop,
            "footerPaddingBottom" to footerPaddingBottom,
            "footerPaddingLeft" to footerPaddingLeft,
            "footerPaddingRight" to footerPaddingRight,
            "footerPaddingTop" to footerPaddingTop,
            "showHeaderLine" to showHeaderLine,
            "showFooterLine" to showFooterLine,
            "tipHeaderLeft" to tipHeaderLeft,
            "tipHeaderMiddle" to tipHeaderMiddle,
            "tipHeaderRight" to tipHeaderRight,
            "tipFooterLeft" to tipFooterLeft,
            "tipFooterMiddle" to tipFooterMiddle,
            "tipFooterRight" to tipFooterRight,
            "tipColor" to tipColor,
            "tipDividerColor" to tipDividerColor,
            "headerMode" to headerMode,
            "footerMode" to footerMode
        )

    }

}
