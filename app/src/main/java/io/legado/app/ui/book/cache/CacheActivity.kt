package io.legado.app.ui.book.cache

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityCacheBookBinding
import io.legado.app.databinding.DialogExportBookConfigBinding
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.removeExportFileSuffix
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.checkWrite
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setIconCompat
import io.legado.app.utils.showPopupMenu
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startService
import io.legado.app.utils.verificationField
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import kotlin.math.max

class CacheActivity : VMBaseActivity<ActivityCacheBookBinding, CacheViewModel>(),
    MenuItem.OnMenuItemClickListener,
    CacheAdapter.CallBack,
    FontSelectDialog.CallBack,
    ColorPickerDialogListener {

    override val binding by viewBinding(ActivityCacheBookBinding::inflate)
    override val viewModel by viewModels<CacheViewModel>()

    private val exportBookPathKey = "exportBookPath"
    private val exportTypes = arrayListOf("txt", "epub")
    private val layoutManager by lazy { LinearLayoutManager(this) }
    private val adapter by lazy { CacheAdapter(this, this) }
    private var booksFlowJob: Job? = null
    private var menu: Menu? = null
    private val groupList: ArrayList<BookGroup> = arrayListOf()
    private var groupId: Long = -1
    private var pendingExportConfig: ExportConfig? = null
    private var pendingExportPathBinding: DialogExportBookConfigBinding? = null
    private var pendingEpubBackgroundBinding: DialogExportBookConfigBinding? = null
    private var activeExportStyleBinding: DialogExportBookConfigBinding? = null

    companion object {
        private const val EPUB_TITLE_COLOR = 101
        private const val EPUB_TEXT_COLOR = 102
        private const val EPUB_BACKGROUND_COLOR = 103
    }

    private enum class ExportConfigTab {
        BASE, TEXT, BACKGROUND
    }

    private data class ExportConfig(
        val position: Int,
        val path: String?,
        val type: String,
        val exportCharset: String = AppConfig.exportCharset,
        val exportUseReplace: Boolean = AppConfig.exportUseReplace,
        val exportToWebDav: Boolean = AppConfig.exportToWebDav,
        val exportNoChapterName: Boolean = AppConfig.exportNoChapterName,
        val exportPictureFile: Boolean = AppConfig.exportPictureFile,
        val parallelExportBook: Boolean = AppConfig.parallelExportBook,
        val bookExportFileName: String? = AppConfig.bookExportFileName,
        val enableCustomExport: Boolean = AppConfig.enableCustomExport,
        val episodeExportFileName: String? = AppConfig.episodeExportFileName,
        val epubSize: Int = 1,
        val epubScope: String? = null,
        val epubTitleColor: String = AppConfig.epubExportTitleColor ?: ReadBookConfig.textAccentColor.toCssHex(),
        val epubTextColor: String = AppConfig.epubExportTextColor ?: ReadBookConfig.textColor.toCssHex(),
        val epubFontPath: String? = ReadBookConfig.textFont.takeIf { it.isNotBlank() }
            ?: AppConfig.epubExportFontPath,
        val epubEmbedFont: Boolean = AppConfig.epubExportEmbedFont,
        val epubTextSize: Int = AppConfig.epubExportTextSize,
        val epubLineHeight: Int = AppConfig.epubExportLineHeight,
        val epubParagraphSpacing: Int = AppConfig.epubExportParagraphSpacing,
        val epubParagraphIndent: String = AppConfig.epubExportParagraphIndent,
        val epubBackgroundColor: String = defaultEpubBackgroundColor(),
        val epubBackgroundImagePath: String? = defaultEpubBackgroundImagePath()
            ?: AppConfig.epubExportBackgroundImagePath,
        val epubUseBackgroundImage: Boolean = defaultEpubBackgroundImagePath() != null ||
                AppConfig.epubExportUseBackgroundImage,
        val epubUseExternalTemplate: Boolean = false
    )

    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        var isReadyPath = false
        var dirPath = ""
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                ACache.get().put(exportBookPathKey, uri.toString())
                dirPath = uri.toString()
                isReadyPath = true
            } else {
                uri.path?.let { path ->
                    ACache.get().put(exportBookPathKey, path)
                    dirPath = path
                    isReadyPath = true
                }
            }
        }
        if (!isReadyPath) {
            pendingExportPathBinding = null
            pendingExportConfig = null
            return@registerForActivityResult
        }
        pendingExportPathBinding?.let {
            it.setExportPath(dirPath)
            pendingExportPathBinding = null
            return@registerForActivityResult
        }
        pendingExportConfig?.copy(path = dirPath)?.let {
            pendingExportConfig = null
            lifecycleScope.launch {
                startExportWithTemplateCheck(it)
            }
        }
    }

    private val selectEpubBackgroundImage = registerForActivityResult(HandleFileContract()) { result ->
        val path = result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                uri.toString()
            } else {
                uri.path
            }
        }
        if (!path.isNullOrBlank()) {
            pendingEpubBackgroundBinding?.setEpubBackgroundImage(path)
        }
        pendingEpubBackgroundBinding = null
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            binding.titleBar.subtitle = withContext(IO) {
                appDb.bookGroupDao.getByID(groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
        }
        initRecyclerView()
        initGroupData()
        initBookData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_cache, menu)
        menu.iconItemOnLongClick(R.id.menu_download) {
            it.showPopupMenu(
                R.menu.book_cache_download,
                onClick = ::onMenuItemClick
            )
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    private fun upMenu() {
        menu?.findItem(R.id.menu_book_group)?.subMenu?.let { subMenu ->
            subMenu.removeGroup(R.id.menu_group)
            groupList.forEach { bookGroup ->
                subMenu.add(R.id.menu_group, bookGroup.order, Menu.NONE, bookGroup.groupName)
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_download_after -> {
                if (!CacheBook.isRun) sureCacheBook {
                    adapter.getItems().forEach { book ->
                        CacheBook.start(
                            this@CacheActivity,
                            book,
                            book.durChapterIndex,
                            book.lastChapterIndex
                        )
                    }
                } else {
                    CacheBook.stop(this@CacheActivity)
                }
            }

            R.id.menu_download_all -> {
                if (!CacheBook.isRun) sureCacheBook {
                    adapter.getItems().forEach { book ->
                        CacheBook.start(
                            this@CacheActivity,
                            book,
                            0,
                            book.lastChapterIndex
                        )
                    }
                } else {
                    CacheBook.stop(this@CacheActivity)
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            else -> if (item.groupId == R.id.menu_group) {
                binding.titleBar.subtitle = item.title
                groupId = appDb.bookGroupDao.getByName(item.title.toString())?.groupId ?: 0
                initBookData()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return onCompatOptionsItemSelected(item)
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    private fun initBookData() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { books ->
                val booksDownload = books.filter {
                    !it.isAudio
                }
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> booksDownload.sortedByDescending { it.latestChapterTime }
                    2 -> booksDownload.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> booksDownload.sortedBy { it.order }
                    4 -> booksDownload.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> booksDownload.sortedByDescending { it.durChapterTime }
                }
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle, table = AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("缓存管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { books ->
                adapter.setItems(books)
                viewModel.loadCacheFiles(books)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("缓存管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
                adapter.notifyDataSetChanged()
                upMenu()
            }
        }
    }

    private fun notifyItemChanged(bookUrl: String) {
        kotlin.runCatching {
            adapter.getItems().forEachIndexed { index, book ->
                if (bookUrl == book.bookUrl) {
                    adapter.notifyItemChanged(index, true)
                    return
                }
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.EXPORT_BOOK) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD_STATE) {
            if (!CacheBook.isRun) {
                menu?.findItem(R.id.menu_download)?.let { item ->
                    item.setIconCompat(R.drawable.ic_play_24dp)
                    item.setTitle(R.string.download_start)
                }
                menu?.applyUiMenuStyle(this)
            } else {
                menu?.findItem(R.id.menu_download)?.let { item ->
                    item.setIconCompat(R.drawable.ic_stop_black_24dp)
                    item.setTitle(R.string.stop)
                }
                menu?.applyUiMenuStyle(this)
            }
        }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)
            notifyItemChanged(book.bookUrl)
        }
    }

    override fun export(position: Int) {
        selector(R.string.export_type, exportTypes) { _, index ->
            showExportConfigDialog(position, exportTypes[index])
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showExportConfigDialog(position: Int, exportType: String) {
        val path = ACache.get().getAsString(exportBookPathKey)
        val alertBinding = DialogExportBookConfigBinding.inflate(layoutInflater).apply {
            setExportPath(path)
            adapter.getItem(position)?.let { book ->
                tvBookFilenameValue.text =
                    book.getExportFileName(exportType, null)
                        .removeExportFileSuffix(exportType)
            } ?: run {
                tvBookFilenameValue.text = AppConfig.bookExportFileName
            }
            tvExportCharsetValue.text = AppConfig.exportCharset
            tvUseReplaceValue.setBooleanValue(AppConfig.exportUseReplace)
            tvExportWebDavValue.setBooleanValue(AppConfig.exportToWebDav)
            tvParallelExportValue.setBooleanValue(AppConfig.parallelExportBook)
            tvNoChapterNameValue.setBooleanValue(AppConfig.exportNoChapterName)
            tvExportPicsFileValue.setBooleanValue(AppConfig.exportPictureFile)
            tvCustomExportValue.setBooleanValue(false)
            tvEpubFilenameValue.text = AppConfig.episodeExportFileName
            tvEpubSizeValue.text = "1"
            tvInputScopeValue.text = ""
            setEpubTitleColor(AppConfig.epubExportTitleColor ?: ReadBookConfig.textAccentColor.toCssHex())
            setEpubTextColor(AppConfig.epubExportTextColor ?: ReadBookConfig.textColor.toCssHex())
            setEpubFont(ReadBookConfig.textFont.takeIf { it.isNotBlank() } ?: AppConfig.epubExportFontPath)
            tvEpubEmbedFontValue.setBooleanValue(AppConfig.epubExportEmbedFont)
            tvEpubTextSizeValue.text = AppConfig.epubExportTextSize.toString()
            tvEpubLineHeightValue.text = AppConfig.epubExportLineHeight.toString()
            tvEpubParagraphSpacingValue.text = AppConfig.epubExportParagraphSpacing.toString()
            tvEpubParagraphIndentValue.text = AppConfig.epubExportParagraphIndent
            setEpubBackgroundColor(defaultEpubBackgroundColor())
            setEpubBackgroundImage(defaultEpubBackgroundImagePath() ?: AppConfig.epubExportBackgroundImagePath)
            fun upTypeView() {
                val isEpub = exportType == "epub"
                tabExportBar.visibility = if (isEpub) View.VISIBLE else View.GONE
                rowExportCharset.visibility = if (isEpub) View.GONE else View.VISIBLE
                rowNoChapterName.visibility = if (isEpub) View.GONE else View.VISIBLE
                rowExportPicsFile.visibility = if (isEpub) View.GONE else View.VISIBLE
                rowCustomExport.visibility = View.GONE
                showExportConfigTab(ExportConfigTab.BASE, isEpub)
                llCustomExport.visibility = View.GONE
            }
            fun upEmbedFontView() {
                rowEpubEmbedFont.isEnabled = !epubFontPath().isNullOrBlank()
                tvEpubEmbedFontValue.isEnabled = rowEpubEmbedFont.isEnabled
            }
            rowCustomExport.setOnClickListener { }
            rowEpubTitleColor.setOnClickListener {
                showEpubColorPicker(EPUB_TITLE_COLOR, tvEpubTitleColorValue.text?.toString())
            }
            rowEpubTextColor.setOnClickListener {
                showEpubColorPicker(EPUB_TEXT_COLOR, tvEpubTextColorValue.text?.toString())
            }
            rowEpubBackgroundColor.setOnClickListener {
                showEpubColorPicker(
                    EPUB_BACKGROUND_COLOR,
                    tvEpubBackgroundColorValue.text?.toString()
                )
            }
            rowEpubBackgroundImage.setOnClickListener {
                val currentPath = epubBackgroundImagePath()
                if (currentPath.isNullOrBlank()) {
                    selectEpubBackgroundImage(this)
                } else {
                    selector(
                        R.string.epub_background_image,
                        arrayListOf(getString(R.string.select_image), getString(R.string.clear))
                    ) { _, index ->
                        if (index == 0) {
                            selectEpubBackgroundImage(this)
                        } else {
                            setEpubBackgroundImage(null)
                        }
                    }
                }
            }
            rowEpubFont.setOnClickListener {
                activeExportStyleBinding = this
                showDialogFragment<FontSelectDialog>()
            }
            btnTabExportBase.setOnClickListener {
                showExportConfigTab(ExportConfigTab.BASE, exportType == "epub")
            }
            btnTabExportText.setOnClickListener {
                showExportConfigTab(ExportConfigTab.TEXT, exportType == "epub")
            }
            btnTabExportBackground.setOnClickListener {
                showExportConfigTab(ExportConfigTab.BACKGROUND, exportType == "epub")
            }
            rowEpubEmbedFont.setOnClickListener {
                if (rowEpubEmbedFont.isEnabled) {
                    tvEpubEmbedFontValue.toggleBooleanValue()
                }
            }
            rowEpubTextSize.setOnClickListener {
                showNumberPicker(
                    title = getString(R.string.epub_text_size),
                    value = tvEpubTextSizeValue.text.toString().toIntOrNull()
                        ?: ReadBookConfig.textSize,
                    min = 8,
                    max = 72
                ) {
                    tvEpubTextSizeValue.text = it.toString()
                }
            }
            rowEpubLineHeight.setOnClickListener {
                showNumberPicker(
                    title = getString(R.string.epub_line_height),
                    value = tvEpubLineHeightValue.text.toString().toIntOrNull()
                        ?: ReadBookConfig.lineSpacingExtra,
                    min = 0,
                    max = 120
                ) {
                    tvEpubLineHeightValue.text = it.toString()
                }
            }
            rowEpubParagraphSpacing.setOnClickListener {
                showNumberPicker(
                    title = getString(R.string.epub_paragraph_spacing),
                    value = tvEpubParagraphSpacingValue.text.toString().toIntOrNull()
                        ?: ReadBookConfig.paragraphSpacing,
                    min = 0,
                    max = 120
                ) {
                    tvEpubParagraphSpacingValue.text = it.toString()
                }
            }
            rowEpubParagraphIndent.setOnClickListener {
                showNumberPicker(
                    title = getString(R.string.epub_paragraph_indent),
                    value = tvEpubParagraphIndentValue.text.toString().toIntOrNull()
                        ?: ReadBookConfig.paragraphIndent.length,
                    min = 0,
                    max = 20
                ) {
                    tvEpubParagraphIndentValue.text = it.toString()
                }
            }
            rowExportPath.setOnClickListener {
                pendingExportPathBinding = this
                selectExportFolder()
            }
            rowBookFilename.setOnClickListener {
                showTextInputDialog(
                    title = getString(R.string.export_file_name),
                    value = tvBookFilenameValue.text?.toString().orEmpty()
                ) {
                    tvBookFilenameValue.text = it
                }
            }
            rowExportCharset.setOnClickListener {
                selector(R.string.export_charset, charsets.toList()) { _, index ->
                    tvExportCharsetValue.text = charsets[index]
                }
            }
            rowUseReplace.setOnClickListener { tvUseReplaceValue.toggleBooleanValue() }
            rowExportWebDav.setOnClickListener { tvExportWebDavValue.toggleBooleanValue() }
            rowParallelExport.setOnClickListener { tvParallelExportValue.toggleBooleanValue() }
            rowNoChapterName.setOnClickListener { tvNoChapterNameValue.toggleBooleanValue() }
            rowExportPicsFile.setOnClickListener { tvExportPicsFileValue.toggleBooleanValue() }
            rowEpubFilename.setOnClickListener {
                showTextInputDialog(
                    title = getString(R.string.export_file_name),
                    value = tvEpubFilenameValue.text?.toString().orEmpty()
                ) {
                    tvEpubFilenameValue.text = it
                }
            }
            rowEpubSize.setOnClickListener {
                showNumberPicker(
                    title = getString(R.string.file_contains_number),
                    value = tvEpubSizeValue.text.toString().toIntOrNull() ?: 1,
                    min = 1,
                    max = 999999
                ) {
                    tvEpubSizeValue.text = it.toString()
                }
            }
            rowInputScope.setOnClickListener {
                showTextInputDialog(
                    title = getString(R.string.export_chapter_index),
                    value = tvInputScopeValue.text?.toString().orEmpty(),
                    hint = "1-5,8,10-18"
                ) {
                    tvInputScopeValue.text = it
                }
            }
            upEmbedFontView()
            upTypeView()
        }
        activeExportStyleBinding = alertBinding
        val alertDialog = alert(title = "${getString(R.string.export)} $exportType") {
            customView { alertBinding.root }
            positiveButton(R.string.ok)
            cancelButton()
        }
        alertDialog.setOnDismissListener {
            if (activeExportStyleBinding === alertBinding) {
                activeExportStyleBinding = null
            }
        }
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val exportConfig = alertBinding.readExportConfig(position, exportType, path)
            if (!saveExportConfig(exportConfig, alertBinding)) {
                return@setOnClickListener
            }
            alertDialog.hide()
            lifecycleScope.launch {
                val exportPath = exportConfig.path
                if (exportPath.isNullOrEmpty() ||
                    withContext(IO) { !isWritableExportDir(exportPath) }
                ) {
                    pendingExportConfig = exportConfig
                    selectExportFolder()
                } else {
                    startExportWithTemplateCheck(exportConfig)
                }
            }
        }
    }

    private fun DialogExportBookConfigBinding.readExportConfig(
        position: Int,
        exportType: String,
        fallbackPath: String?
    ): ExportConfig {
        val customExport = false
        return ExportConfig(
            position = position,
            path = exportPath() ?: fallbackPath,
            type = exportType,
            exportCharset = tvExportCharsetValue.text?.toString()?.takeIf { it.isNotBlank() } ?: "UTF-8",
            exportUseReplace = tvUseReplaceValue.booleanValue(),
            exportToWebDav = tvExportWebDavValue.booleanValue(),
            exportNoChapterName = tvNoChapterNameValue.booleanValue(),
            exportPictureFile = tvExportPicsFileValue.booleanValue(),
            parallelExportBook = tvParallelExportValue.booleanValue(),
            bookExportFileName = tvBookFilenameValue.text?.toString(),
            enableCustomExport = customExport,
            episodeExportFileName = tvEpubFilenameValue.text?.toString(),
            epubSize = (tvEpubSizeValue.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1),
            epubScope = if (customExport) tvInputScopeValue.text?.toString() else null,
            epubTitleColor = tvEpubTitleColorValue.text?.toString()?.normalizeCssColor()
                ?: ReadBookConfig.textAccentColor.toCssHex(),
            epubTextColor = tvEpubTextColorValue.text?.toString()?.normalizeCssColor()
                ?: ReadBookConfig.textColor.toCssHex(),
            epubFontPath = epubFontPath(),
            epubEmbedFont = tvEpubEmbedFontValue.booleanValue(),
            epubTextSize = (tvEpubTextSizeValue.text.toString().toIntOrNull() ?: ReadBookConfig.textSize)
                .coerceIn(8, 72),
            epubLineHeight = (tvEpubLineHeightValue.text.toString().toIntOrNull()
                ?: ReadBookConfig.lineSpacingExtra).coerceIn(0, 120),
            epubParagraphSpacing = (tvEpubParagraphSpacingValue.text.toString().toIntOrNull()
                ?: ReadBookConfig.paragraphSpacing).coerceIn(0, 120),
            epubParagraphIndent = tvEpubParagraphIndentValue.text?.toString()?.takeIf { it.isNotBlank() }
                ?: ReadBookConfig.paragraphIndent.length.toString(),
            epubBackgroundColor = tvEpubBackgroundColorValue.text?.toString()?.normalizeCssColor()
                ?: defaultEpubBackgroundColor(),
            epubBackgroundImagePath = epubBackgroundImagePath(),
            epubUseBackgroundImage = !epubBackgroundImagePath().isNullOrBlank()
        )
    }

    private fun saveExportConfig(
        exportConfig: ExportConfig,
        binding: DialogExportBookConfigBinding
    ): Boolean {
        if (exportConfig.enableCustomExport) {
            val epubScope = exportConfig.epubScope.orEmpty()
            if (!verificationField(epubScope)) {
                binding.tvInputScopeValue.error = appCtx.getString(R.string.error_scope_input)
                return false
            }
            binding.tvInputScopeValue.error = null
            if (!tryParesExportFileName(exportConfig.episodeExportFileName.orEmpty())) {
                binding.tvEpubFilenameValue.error = "Error"
                return false
            }
            binding.tvEpubFilenameValue.error = null
        }
        if (exportConfig.type == "epub") {
            if (exportConfig.epubTitleColor.normalizeCssColor() == null) {
                binding.tvEpubTitleColorValue.error = "Error"
                return false
            }
            binding.tvEpubTitleColorValue.error = null
            if (exportConfig.epubTextColor.normalizeCssColor() == null) {
                binding.tvEpubTextColorValue.error = "Error"
                return false
            }
            binding.tvEpubTextColorValue.error = null
            if (exportConfig.epubBackgroundColor.normalizeCssColor() == null) {
                binding.tvEpubBackgroundColorValue.error = "Error"
                return false
            }
            binding.tvEpubBackgroundColorValue.error = null
        }
        AppConfig.exportType = exportTypes.indexOf(exportConfig.type).coerceAtLeast(0)
        AppConfig.exportCharset = exportConfig.exportCharset
        AppConfig.exportUseReplace = exportConfig.exportUseReplace
        AppConfig.exportToWebDav = exportConfig.exportToWebDav
        AppConfig.exportNoChapterName = exportConfig.exportNoChapterName
        AppConfig.exportPictureFile = exportConfig.exportPictureFile
        AppConfig.parallelExportBook = exportConfig.parallelExportBook
        AppConfig.bookExportFileName = exportConfig.bookExportFileName
        AppConfig.enableCustomExport = exportConfig.enableCustomExport
        AppConfig.episodeExportFileName = exportConfig.episodeExportFileName
        if (exportConfig.type == "epub") {
            AppConfig.epubExportTitleColor = exportConfig.epubTitleColor
            AppConfig.epubExportTextColor = exportConfig.epubTextColor
            AppConfig.epubExportFontPath = exportConfig.epubFontPath
            AppConfig.epubExportEmbedFont = exportConfig.epubEmbedFont
            AppConfig.epubExportTextSize = exportConfig.epubTextSize
            AppConfig.epubExportLineHeight = exportConfig.epubLineHeight
            AppConfig.epubExportParagraphSpacing = exportConfig.epubParagraphSpacing
            AppConfig.epubExportParagraphIndent = exportConfig.epubParagraphIndent
            AppConfig.epubExportBackgroundColor = exportConfig.epubBackgroundColor
            AppConfig.epubExportBackgroundImagePath = exportConfig.epubBackgroundImagePath
            AppConfig.epubExportUseBackgroundImage = exportConfig.epubUseBackgroundImage
        }
        return true
    }

    private fun selectEpubBackgroundImage(binding: DialogExportBookConfigBinding) {
        pendingEpubBackgroundBinding = binding
        selectEpubBackgroundImage.launch {
            mode = HandleFileContract.IMAGE
            title = getString(R.string.select_image)
            value = binding.epubBackgroundImagePath()
        }
    }

    private fun selectExportFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(exportBookPathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        exportDir.launch {
            otherActions = default
            requestCode = pendingExportConfig?.position ?: -1
        }
    }

    private suspend fun startExportWithTemplateCheck(exportConfig: ExportConfig) {
        val exportPath = exportConfig.path ?: return
        if (exportConfig.type == "epub" && withContext(IO) { hasExternalEpubTemplate(exportPath) }) {
            showExternalTemplateDialog(exportConfig)
        } else {
            startExport(exportConfig.copy(epubUseExternalTemplate = false))
        }
    }

    private fun showExternalTemplateDialog(exportConfig: ExportConfig) {
        alert(R.string.epub_external_template_detected) {
            setMessage(R.string.epub_external_template_prompt)
            positiveButton(R.string.continue_use_external_template) {
                startExport(exportConfig.copy(epubUseExternalTemplate = true))
            }
            negativeButton(R.string.use_builtin_template) {
                startExport(exportConfig.copy(epubUseExternalTemplate = false))
            }
            neutralButton(android.R.string.cancel)
        }
    }

    private fun hasExternalEpubTemplate(path: String): Boolean {
        return kotlin.runCatching {
            FileDoc.fromDir(path).find("Asset")?.isDir == true
        }.getOrDefault(false)
    }

    private fun startExport(exportConfig: ExportConfig) {
        val path = exportConfig.path ?: return
        adapter.getItem(exportConfig.position)?.let { book ->
            startService<ExportBookService> {
                action = IntentAction.start
                putExtra("bookUrl", book.bookUrl)
                putExtra("exportType", exportConfig.type)
                putExtra("exportPath", path)
                putExtra("exportCharset", if (exportConfig.type == "epub") "UTF-8" else exportConfig.exportCharset)
                putExtra("exportUseReplace", exportConfig.exportUseReplace)
                putExtra("exportToWebDav", exportConfig.exportToWebDav)
                putExtra("exportNoChapterName", exportConfig.exportNoChapterName)
                putExtra("exportPictureFile", exportConfig.exportPictureFile)
                putExtra("parallelExportBook", exportConfig.parallelExportBook)
                putExtra("bookExportFileName", exportConfig.bookExportFileName)
                putExtra("episodeExportFileName", exportConfig.episodeExportFileName)
                putExtra("epubTitleColor", exportConfig.epubTitleColor)
                putExtra("epubTextColor", exportConfig.epubTextColor)
                putExtra("epubFontPath", exportConfig.epubFontPath)
                putExtra("epubEmbedFont", exportConfig.epubEmbedFont)
                putExtra("epubTextSize", exportConfig.epubTextSize)
                putExtra("epubLineHeight", exportConfig.epubLineHeight)
                putExtra("epubParagraphSpacing", exportConfig.epubParagraphSpacing)
                putExtra("epubParagraphIndent", exportConfig.epubParagraphIndent)
                putExtra("epubBackgroundColor", exportConfig.epubBackgroundColor)
                putExtra("epubBackgroundImagePath", exportConfig.epubBackgroundImagePath)
                putExtra("epubUseBackgroundImage", exportConfig.epubUseBackgroundImage)
                putExtra("epubUseExternalTemplate", exportConfig.epubUseExternalTemplate)
                if (exportConfig.enableCustomExport) {
                    putExtra("epubSize", exportConfig.epubSize)
                    putExtra("epubScope", exportConfig.epubScope)
                }
            }
        }
    }

    private fun isWritableExportDir(path: String): Boolean {
        return kotlin.runCatching {
            FileDoc.fromDir(path).checkWrite()
        }.getOrDefault(false)
    }

    override fun sureCacheBook(action: () -> Unit) {
        alert(R.string.draw) {
            setMessage(R.string.sure_cache_book)
            noButton()
            yesButton {
                action.invoke()
            }
        }
    }

    override val cacheChapters: HashMap<String, HashSet<String>>
        get() = viewModel.cacheChapters

    override fun exportProgress(bookUrl: String): Int? {
        return ExportBookService.exportProgress[bookUrl]
    }

    override fun exportMsg(bookUrl: String): String? {
        return ExportBookService.exportMsg[bookUrl]
    }

    override val curFontPath: String
        get() = activeExportStyleBinding?.epubFontPath().orEmpty()

    override val applySystemTypefaceOnDefault: Boolean
        get() = false

    override fun selectFont(path: String) {
        activeExportStyleBinding?.setEpubFont(path)
    }

    private fun showEpubColorPicker(dialogId: Int, colorText: String?) {
        activeExportStyleBinding ?: return
        ColorPickerDialog.newBuilder()
            .setColor(colorText.normalizeCssColor()?.toColorInt() ?: ReadBookConfig.textColor)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(dialogId)
            .show(this)
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        activeExportStyleBinding?.run {
            when (dialogId) {
                EPUB_TITLE_COLOR -> setEpubTitleColor(color.toCssHex())
                EPUB_TEXT_COLOR -> setEpubTextColor(color.toCssHex())
                EPUB_BACKGROUND_COLOR -> setEpubBackgroundColor(color.toCssHex())
            }
        }
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun DialogExportBookConfigBinding.setEpubFont(path: String?) {
        val fontPath = path?.takeIf { it.isNotBlank() }
        tvEpubFontValue.tag = fontPath
        tvEpubFontValue.text =
            fontPath?.let {
                kotlin.runCatching { FileDoc.fromFile(it).name }.getOrNull()
                    ?.takeIf { name -> name.isNotBlank() }
                    ?: File(it).name.takeIf { name -> name.isNotBlank() }
                    ?: it
            } ?: getString(R.string.default_font)
        rowEpubEmbedFont.isEnabled = !fontPath.isNullOrBlank()
        tvEpubEmbedFontValue.isEnabled = rowEpubEmbedFont.isEnabled
        if (fontPath.isNullOrBlank()) {
            tvEpubEmbedFontValue.setBooleanValue(false)
        } else if (!tvEpubEmbedFontValue.booleanValue()) {
            tvEpubEmbedFontValue.setBooleanValue(AppConfig.epubExportEmbedFont)
        }
    }

    private fun DialogExportBookConfigBinding.epubFontPath(): String? {
        return (tvEpubFontValue.tag as? String)?.takeIf { it.isNotBlank() }
    }

    private fun DialogExportBookConfigBinding.setExportPath(path: String?) {
        val exportPath = path?.takeIf { it.isNotBlank() }
        tvExportPathValue.tag = exportPath
        tvExportPathValue.text = exportPath?.let {
            kotlin.runCatching { FileDoc.fromDir(it).name }.getOrNull()
                ?.takeIf { name -> name.isNotBlank() }
                ?: File(it).name.takeIf { name -> name.isNotBlank() }
                ?: it
        }.orEmpty()
    }

    private fun DialogExportBookConfigBinding.exportPath(): String? {
        return (tvExportPathValue.tag as? String)?.takeIf { it.isNotBlank() }
    }

    private fun DialogExportBookConfigBinding.showExportConfigTab(
        tab: ExportConfigTab,
        isEpub: Boolean
    ) {
        val fixedTab = if (isEpub) tab else ExportConfigTab.BASE
        panelExportBase.visibility = if (fixedTab == ExportConfigTab.BASE) View.VISIBLE else View.GONE
        panelExportText.visibility = if (isEpub && fixedTab == ExportConfigTab.TEXT) {
            View.VISIBLE
        } else {
            View.GONE
        }
        panelExportBackground.visibility =
            if (isEpub && fixedTab == ExportConfigTab.BACKGROUND) View.VISIBLE else View.GONE
        btnTabExportBase.setExportTabSelected(fixedTab == ExportConfigTab.BASE)
        btnTabExportText.setExportTabSelected(fixedTab == ExportConfigTab.TEXT)
        btnTabExportBackground.setExportTabSelected(fixedTab == ExportConfigTab.BACKGROUND)
    }

    private fun TextView.setExportTabSelected(selected: Boolean) {
        isSelected = selected
        background = ColorDrawable(if (selected) 0x1A000000 else Color.TRANSPARENT)
    }

    private fun DialogExportBookConfigBinding.setEpubTitleColor(color: String) {
        val cssColor = color.normalizeCssColor() ?: ReadBookConfig.textAccentColor.toCssHex()
        tvEpubTitleColorValue.text = cssColor
        vwEpubTitleColorSwatch.background = ColorDrawable(cssColor.toColorInt())
    }

    private fun DialogExportBookConfigBinding.setEpubTextColor(color: String) {
        val cssColor = color.normalizeCssColor() ?: ReadBookConfig.textColor.toCssHex()
        tvEpubTextColorValue.text = cssColor
        vwEpubTextColorSwatch.background = ColorDrawable(cssColor.toColorInt())
    }

    private fun DialogExportBookConfigBinding.setEpubBackgroundColor(color: String) {
        val cssColor = color.normalizeCssColor() ?: defaultEpubBackgroundColor()
        tvEpubBackgroundColorValue.text = cssColor
        vwEpubBackgroundColorSwatch.background = ColorDrawable(cssColor.toColorInt())
    }

    private fun DialogExportBookConfigBinding.setEpubBackgroundImage(path: String?) {
        val imagePath = path?.takeIf { it.isNotBlank() }
        tvEpubBackgroundImageValue.tag = imagePath
        tvEpubBackgroundImageValue.text = imagePath?.toDisplayFileName().orEmpty()
    }

    private fun DialogExportBookConfigBinding.epubBackgroundImagePath(): String? {
        return (tvEpubBackgroundImageValue.tag as? String)?.takeIf { it.isNotBlank() }
    }

    private fun showTextInputDialog(
        title: String,
        value: String,
        hint: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(value)
            setSelection(text?.length ?: 0)
            this.hint = hint
            this.inputType = inputType
        }
        alert(title) {
            customView { input }
            okButton {
                onConfirm(input.text?.toString().orEmpty())
            }
            cancelButton()
        }
    }

    private fun showNumberPicker(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        onConfirm: (Int) -> Unit
    ) {
        NumberPickerDialog(this)
            .setTitle(title)
            .setMinValue(min)
            .setMaxValue(max)
            .setValue(value.coerceIn(min, max))
            .show {
                onConfirm(it)
            }
    }

}

private fun Int.toCssHex(): String {
    return "#%06X".format(0xFFFFFF and this)
}

private fun String?.normalizeCssColor(): String? {
    val text = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val cssText = if (text.startsWith("#")) text else "#$text"
    return kotlin.runCatching {
        cssText.toColorInt().toCssHex()
    }.getOrNull()
}

private fun TextView.setBooleanValue(value: Boolean) {
    text = context.getString(if (value) R.string.yes else R.string.no)
    tag = value
}

private fun TextView.toggleBooleanValue() {
    setBooleanValue(!booleanValue())
}

private fun TextView.booleanValue(): Boolean {
    return tag as? Boolean ?: false
}

private const val EPUB_ASSET_BACKGROUND_PREFIX = "asset://bg/"

private fun defaultEpubBackgroundColor(): String {
    val config = ReadBookConfig.durConfig
    if (config.curBgType() == 0) {
        return config.curBgStr().normalizeCssColor() ?: "#FFFFFF"
    }
    return AppConfig.epubExportBackgroundColor.normalizeCssColor() ?: "#FFFFFF"
}

private fun defaultEpubBackgroundImagePath(): String? {
    val config = ReadBookConfig.durConfig
    val bgStr = config.curBgStr().takeIf { it.isNotBlank() } ?: return null
    return when (config.curBgType()) {
        1 -> "$EPUB_ASSET_BACKGROUND_PREFIX$bgStr"
        2 -> if (bgStr.contains(File.separator)) {
            bgStr
        } else {
            FileUtils.getPath(appCtx.externalFiles, "bg", bgStr)
        }
        else -> null
    }
}

private fun String.toDisplayFileName(): String {
    if (startsWith(EPUB_ASSET_BACKGROUND_PREFIX)) {
        return removePrefix(EPUB_ASSET_BACKGROUND_PREFIX).substringAfterLast('/')
    }
    return kotlin.runCatching { FileDoc.fromFile(this).name }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: File(this).name.takeIf { it.isNotBlank() }
        ?: this
}
