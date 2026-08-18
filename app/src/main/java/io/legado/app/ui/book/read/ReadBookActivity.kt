package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.size
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.google.android.material.snackbar.Snackbar
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookIllustration
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.ai.AiChapterPurifyException
import io.legado.app.help.ai.AiChapterPurifyConfig
import io.legado.app.help.ai.AiChapterPurifyProgress
import io.legado.app.help.ai.AiChapterPurifyService
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.illustration.IllustrationAnchor
import io.legado.app.help.illustration.AudioBlockPlayer
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.imageSrcsFromJson
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.ColorPreference.ColorPickerDialogCompat
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudUiState
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.receiver.TimeBatteryReceiver
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.changesource.ChangeChapterSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.config.IllustrationEditDialog
import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.READ_MENU_BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_ACCENT_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_COLOR
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_COLOR
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_DIVIDER_COLOR
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.SelectionHandleDrawable
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.searchContent.SearchContentActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.dict.DictDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.Debounce
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.gone
import io.legado.app.utils.hexString
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.invisible
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarGravity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showPopupMenu
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import com.script.rhino.runScriptWithContext
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import java.lang.ref.WeakReference
import io.legado.app.ui.login.SourceLoginJsExtensions
import kotlinx.coroutines.CoroutineStart
import kotlin.math.min

/**
 * 阅读界面
 */
class ReadBookActivity : BaseReadBookActivity(),
    View.OnTouchListener,
    ReadView.CallBack,
    TextActionMenu.CallBack,
    ContentTextView.CallBack,
    MenuItem.OnMenuItemClickListener,
    ReadMenu.CallBack,
    SearchMenu.CallBack,
    ReadAloudDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeChapterSourceDialog.CallBack,
    ReadBook.CallBack,
    AutoReadDialog.CallBack,
    TxtTocRuleDialog.CallBack,
    ColorPickerDialogListener,
    LayoutProgressListener {

    private val tocActivity =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                viewModel.openChapter(it[0] as Int, it[1] as Int)
            }
        }
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    upMenuView()
                }
            }
        }
    private val replaceActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.replaceRuleChanged()
            }
        }
    private val searchContentActivity =
        registerForActivityResult(StartActivityContract(SearchContentActivity::class.java)) {
            val data = it.data ?: return@registerForActivityResult
            val key = data.getLongExtra("key", System.currentTimeMillis())
            val index = data.getIntExtra("index", 0)
            val searchResult = IntentData.get<SearchResult>("searchResult$key")
            val searchResultList = IntentData.get<List<SearchResult>>("searchResultList$key")
            if (searchResult != null && searchResultList != null) {
                viewModel.searchContentQuery = searchResult.query
                binding.searchMenu.upSearchResultList(searchResultList)
                isShowingSearchResult = true
                viewModel.searchResultIndex = index
                binding.searchMenu.updateSearchResultIndex(index)
                binding.searchMenu.selectedSearchResult?.let { currentResult ->
                    ReadBook.saveCurrentBookProgress() //退出全文搜索恢复此时进度
                    skipToSearch(currentResult)
                    showActionMenu()
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadBook.loadOrUpContent()
            }
    }
    private var lastTextMenuAnchor: ReadAiFloatingPanel.Anchor? = null
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private var menu: Menu? = null
    private var backupJob: Job? = null
    private var aiChapterPurifyJob: Job? = null
    private var aiChapterPurifySummarySnackbar: Snackbar? = null
    private var aiChapterPurifyLastStreamSnackbarAt = 0L
    private var aiChapterPurifyPendingChapterIndex: Int? = null
    private var aiChapterPurifyPendingForce = false
    private var aiChapterPurifyPendingSource: String? = null
    private var aiChapterPurifyRefreshChapterIndex: Int? = null
    private var illustrationAnchor: IllustrationAnchor? = null
    val textActionMenu: TextActionMenu by lazy {
        TextActionMenu(this, this)
    }
    private val popupAction: PopupAction by lazy {
        PopupAction(this)
    }
    override val isInitFinish: Boolean get() = viewModel.isInitFinish
    override val isScroll: Boolean get() = binding.readView.isScroll
    private val isAutoPage get() = binding.readView.isAutoPage
    override var isShowingSearchResult = false
    override var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }
    private val timeBatteryReceiver = TimeBatteryReceiver()
    private var screenTimeOut: Long = 0
    private var loadStates: Boolean = false
    override val pageFactory get() = binding.readView.pageFactory
    override val pageDelegate get() = binding.readView.pageDelegate
    override val headerHeight: Int get() = binding.readView.curPage.headerHeight
    override val imgBgPaddingStart: Int get() = binding.readView.curPage.imgBgPaddingStart
    private val nextPageDebounce by lazy { Debounce { keyPage(PageDirection.NEXT) } }
    private val prevPageDebounce by lazy { Debounce { keyPage(PageDirection.PREV) } }
    private var bookChanged = false
    private var pageChanged = false
    private var bookmarkLoadChapterIndex = -1
    private var lastReadAloudChapterPos: Int? = null
    private var lastReadAloudChapterIndex: Int? = null
    private var finishReadAloudBackstage = false
    private val readAloudPanelFadeDuration = 140L
    private enum class ReadAloudPlaybackPresentation {
        HIDDEN,
        PANEL,
        FOOTER,
    }
    private var readAloudPlaybackPresentation = ReadAloudPlaybackPresentation.HIDDEN
    private val handler by lazy { buildMainHandler() }
    private val collapseReadAloudPlaybackPanel = Runnable {
        if (
            readAloudPlaybackPresentation == ReadAloudPlaybackPresentation.PANEL &&
            !AppConfig.readAloudHidePlaybackPanel &&
            ReadAloudUiState.readerPanelMode(
                BaseReadAloudService.isRun,
                ReadBook.readAloudPageDetached,
            ) == ReadAloudUiState.ReaderPanelMode.PLAYBACK
        ) {
            readAloudPlaybackPresentation = ReadAloudPlaybackPresentation.FOOTER
            hideReadAloudPlaybackPanelView()
            showReadAloudPlaybackInFooter()
        }
    }
    private val readAloudAvoidanceGenerations = mutableMapOf<String, Long>()
    private val screenOffRunnable by lazy { Runnable { keepScreenOn(false) } }
    private val executor = ReadBook.executor
    private val upSeekBarThrottle = throttle(200) {
        runOnUiThread {
            upSeekBarProgress()
            binding.readMenu.upSeekBar()
        }
    }

    //恢复跳转前进度对话框的交互结果
    private var confirmRestoreProcess: Boolean? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }
    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.cursorLeft.setColorFilter(accentColor)
        binding.cursorRight.setColorFilter(accentColor)
        binding.cursorLeft.setOnTouchListener(this)
        binding.cursorRight.setOnTouchListener(this)
        binding.selectionMagnifierView.bindSource(binding.readView)
        binding.selectionMagnifierView.bindOverlays(binding.cursorLeft, binding.cursorRight)
        binding.readAiPanel.attach(this)
        binding.btnReadAloudOriginalProgress.setOnClickListener {
            backToReadAloudProgress()
        }
        binding.btnReadAloudFromCurrentPage.setOnClickListener {
            readAloudFromCurrentPage()
        }
        binding.btnReadAloudPlayback.setOnClickListener { toggleReadAloudPlayback() }
        binding.readAloudDialogOutsideTap.setOnClickListener {
            postEvent(EventBus.CLOSE_READ_ALOUD_DIALOG, true)
        }
        window.setBackgroundDrawable(null)
        upScreenTimeOut()
        ReadBook.register(this)
        updateReadAloudPageFloating()
        updateReadAloudPanels()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.readAiPanel.isVisible) {
                binding.readAiPanel.close()
                return@addCallback
            }
            if (isShowingSearchResult) {
                exitSearchMenu()
                restoreLastBookProcess()
                return@addCallback
            }
            //拦截返回供恢复阅读进度
            if (ReadBook.lastBookProgress != null && confirmRestoreProcess != false) {
                restoreLastBookProcess()
                return@addCallback
            }
            if (isAutoPage) {
                autoPageStop()
                return@addCallback
            }
            if (getPrefBoolean("disableReturnKey") && !menuLayoutIsVisible) {
                return@addCallback
            }
            finish()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initReadBookConfig(intent)
        Looper.myQueue().addIdleHandler {
            viewModel.initData(intent)
            false
        }
        justInitData = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.initData(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        upSystemUiVisibility()
        if (hasFocus) {
            binding.readMenu.upBrightnessState()
        } else if (!menuLayoutIsVisible) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        upSystemUiVisibility()
        binding.readView.upStatusBar()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (!isTopResumedActivity) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        activeActivityRef = WeakReference(this)
        postEvent(EventBus.READ_BOOK_ACTIVITY_ACTIVE, true)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        ReadBook.readStartTime = System.currentTimeMillis()
        if (bookChanged) {
            bookChanged = false
            ReadBook.callBack = this
            viewModel.initData(intent)
            justInitData = true
        } else {
            //web端阅读时，app处于阅读界面，本地记录会覆盖web保存的进度，在此处恢复
            ReadBook.webBookProgress?.let {
                ReadBook.setProgress(it)
                ReadBook.webBookProgress = null
            }
        }
        upSystemUiVisibility()
        registerReceiver(timeBatteryReceiver, timeBatteryReceiver.filter)
        binding.readView.upTime()
        updateReadAloudPageFloating()
        if (ReadAloudUiState.consumeAudioPlayerReturn()) {
            handler.post { restoreReadAloudPlayerPosition() }
        }
        updateReadAloudPanels()
        screenOffTimerStart()
        bookmarkLoadChapterIndex = -1
        upChapterBookmarks()
        // 网络监听，当从无网切换到网络环境时同步进度（注意注册的同时就会收到监听，因此界面激活时无需重复执行同步操作）
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadBook.inBookshelf) {
                ReadBook.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoPageStop()
        backupJob?.cancel()
        ReadBook.upReadTime(forceWidgetUpdate = true)
        ReadBook.saveReadNow()
        ReadBook.cancelPreDownloadTask()
        unregisterReceiver(timeBatteryReceiver)
        upSystemUiVisibility()
        if (!BuildConfig.DEBUG && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress()
            } else {
                ReadBook.uploadProgress()
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        justInitData = false
        networkChangedListener.unRegister()
    }

    override fun onStop() {
        super.onStop()
        if (activeActivityRef?.get() === this) {
            activeActivityRef = null
        }
        updateReadAloudMainMenuVisibility(false)
        postEvent(EventBus.READ_BOOK_ACTIVITY_ACTIVE, false)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read_toolbar, menu)
        menu.iconItemOnLongClick(R.id.menu_change_source) {
            it.showPopupMenu(
                R.menu.book_read_change_source,
                onClick = ::onMenuItemClick
            )
        }
        menu.iconItemOnLongClick(R.id.menu_refresh) {
            it.showPopupMenu(
                R.menu.book_read_refresh,
                onClick = ::onMenuItemClick
            )
        }
        menu.findItem(R.id.menu_read_surface_more)?.let { item ->
            item.actionView?.findViewById<ImageButton>(R.id.item)?.apply {
                contentDescription = item.title
                setImageDrawable(item.icon)
                setOnClickListener(::showReadOverflowMenu)
            }
        }
        binding.readMenu.refreshMenuColorFilter()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    private fun showReadOverflowMenu(anchor: View) {
        anchor.showPopupMenu(
            R.menu.book_read,
            prepare = {
                removeGroup(R.id.menu_group_on_line)
                removeGroup(R.id.menu_group_text)
                removeGroup(R.id.menu_group_local)
                findItem(R.id.menu_same_title_removed)?.isChecked =
                    ReadBook.curTextChapter?.sameTitleRemoved == true
                upMenu(this)
            },
            onClick = ::onMenuItemClick
        )
    }

    /**
     * 更新菜单
     */
    private fun upMenu(targetMenu: Menu? = menu) {
        val menu = targetMenu ?: return
        val book = ReadBook.book ?: return
        val onLine = !book.isLocal
        for (i in 0 until menu.size) {
            val item = menu[i]
            when (item.groupId) {
                R.id.menu_group_on_line -> item.isVisible = onLine
                R.id.menu_group_local -> item.isVisible = !onLine
                R.id.menu_group_text -> item.isVisible = book.isLocalTxt
                R.id.menu_group_epub -> item.isVisible = book.isEpub
                else -> when (item.itemId) {
                    R.id.menu_enable_replace -> item.isChecked = book.getUseReplaceRule()
                    R.id.menu_enable_ai_chapter_purify ->
                        item.isChecked = book.getAiChapterPurifyEnabled()
                    R.id.menu_re_segment -> item.isChecked = book.getReSegment()
//                    R.id.menu_enable_review -> {
//                        item.isVisible = BuildConfig.DEBUG
//                        item.isChecked = AppConfig.enableReview
//                    }

                    R.id.menu_reverse_content -> item.isVisible = onLine
                    R.id.menu_del_ruby_tag -> item.isChecked = book.getDelTag(Book.rubyTag)
                    R.id.menu_del_h_tag -> item.isChecked = book.getDelTag(Book.hTag)
                }
            }
        }
        lifecycleScope.launch {
            val show = ReadBook.inBookshelf && withContext(IO) {
                AppWebDav.isOk
            }
            menu.findItem(R.id.menu_get_progress)?.isVisible = show
            menu.findItem(R.id.menu_cover_progress)?.isVisible = show
        }
    }

    /**
     * 菜单
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source,
            R.id.menu_book_change_source -> {
                binding.readMenu.runMenuOut()
                ReadBook.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_chapter_change_source -> lifecycleScope.launch {
                val book = ReadBook.book ?: return@launch
                val chapter =
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                        ?: return@launch
                binding.readMenu.runMenuOut()
                showDialogFragment(
                    ChangeChapterSourceDialog(book.name, book.author, chapter.index, chapter.title)
                )
            }

            R.id.menu_refresh,
            R.id.menu_refresh_dur -> {
                requestAiChapterPurifyAfterRefresh()
                if (ReadBook.bookSource == null) {
                    upContent()
                    scheduleAiChapterPurify(force = true, source = "menu_refresh")
                } else {
                    ReadBook.book?.let {
                        ReadBook.curTextChapter = null
                        binding.readView.upContent()
                        viewModel.refreshContentDur(it)
                    }
                }
            }

            R.id.menu_refresh_after -> {
                requestAiChapterPurifyAfterRefresh()
                if (ReadBook.bookSource == null) {
                    upContent()
                    scheduleAiChapterPurify(force = true, source = "menu_refresh_after")
                } else {
                    ReadBook.book?.let {
                        ReadBook.clearTextChapter()
                        binding.readView.upContent()
                        viewModel.refreshContentAfter(it)
                    }
                }
            }

            R.id.menu_refresh_all -> {
                requestAiChapterPurifyAfterRefresh()
                if (ReadBook.bookSource == null) {
                    upContent()
                    scheduleAiChapterPurify(force = true, source = "menu_refresh_all")
                } else {
                    ReadBook.book?.let {
                        refreshContentAll(it)
                    }
                }
            }

            R.id.menu_download -> showDownloadDialog()
            R.id.menu_add_bookmark -> addBookmark()
            R.id.menu_simulated_reading -> showSimulatedReading()
            R.id.menu_edit_content -> showDialogFragment(ContentEditDialog())
            R.id.menu_update_toc -> ReadBook.book?.let {
                if (it.isEpub) {
                    BookHelp.clearCache(it)
                    EpubFile.clear()
                }
                if (it.isMobi) {
                    MobiFile.clear()
                }
                // 目录更新 = 全书缓存失效：清空该书的净化记录，重新出现的章节按常规判定重跑
                AiChapterPurifyService.dropBookRecords(it)
                loadChapterList(it)
            }

            R.id.menu_enable_replace -> changeReplaceRuleState()
            R.id.menu_enable_ai_chapter_purify -> changeAiChapterPurifyState()
            R.id.menu_re_segment -> ReadBook.book?.let {
                it.setReSegment(!it.getReSegment())
                item.isChecked = it.getReSegment()
                ReadBook.loadContent(false)
            }

//            R.id.menu_enable_review -> {
//                AppConfig.enableReview = !AppConfig.enableReview
//                item.isChecked = AppConfig.enableReview
//                ReadBook.loadContent(false)
//            }

            R.id.menu_del_ruby_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.rubyTag)
                } else {
                    it.removeDelTag(Book.rubyTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_del_h_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.hTag)
                } else {
                    it.removeDelTag(Book.hTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_page_anim -> showPageAnimConfig {
                binding.readView.upPageAnim()
                ReadBook.loadContent(false)
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_toc_regex -> showDialogFragment(
                TxtTocRuleDialog(ReadBook.book?.tocUrl)
            )

            R.id.menu_reverse_content -> ReadBook.book?.let {
                viewModel.reverseContent(it)
            }

            R.id.menu_set_charset -> showCharsetConfig()
            R.id.menu_image_style -> {
                val imgStyles =
                    arrayListOf(
                        Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText,
                        Book.imgStyleSingle
                    )
                selector(
                    R.string.image_style,
                    imgStyles
                ) { _, index ->
                    val imageStyle = imgStyles[index]
                    ReadBook.book?.setImageStyle(imageStyle)
                    if (imageStyle == Book.imgStyleSingle) {
                        ReadBook.book?.setPageAnim(0)  // 切换图片样式single后，自动切换为覆盖
                        binding.readView.upPageAnim()
                    }
                    ReadBook.loadContent(false)
                }
            }

            R.id.menu_get_progress -> ReadBook.book?.let {
                viewModel.syncBookProgress(it) { progress ->
                    sureSyncProgress(progress)
                }
            }

            R.id.menu_cover_progress -> ReadBook.book?.let {
                ReadBook.uploadProgress(true) { toastOnUi(R.string.upload_book_success) }
            }

            R.id.menu_same_title_removed -> {
                ReadBook.book?.let {
                    val contentProcessor = ContentProcessor.get(it)
                    val textChapter = ReadBook.curTextChapter
                    if (textChapter != null
                        && !textChapter.sameTitleRemoved
                        && !BookHelp.getChapterCacheFileNames(it, textChapter.chapter, "nr")
                            .any(contentProcessor.removeSameTitleCache::contains)
                    ) {
                        toastOnUi("未找到可移除的重复标题")
                    }
                }
                viewModel.reverseRemoveSameTitle()
            }

            R.id.menu_effective_replaces -> showDialogFragment<EffectiveReplacesDialog>()

            R.id.menu_help -> showHelp()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun refreshContentAll(book: Book) {
        ReadBook.clearTextChapter()
        binding.readView.upContent()
        viewModel.refreshContentAll(book)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return onCompatOptionsItemSelected(item)
    }

    /**
     * 按键拦截,显示菜单
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 鼠标滚轮事件
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
                // 获得垂直坐标上的滚动方向
                if (axisValue < 0.0f) { // 滚轮向下
                    mouseWheelPage(PageDirection.NEXT, axisValue)
                } else { // 滚轮向上
                    mouseWheelPage(PageDirection.PREV, axisValue)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * 按键事件
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuLayoutIsVisible) {
            return super.onKeyDown(keyCode, event)
        }
        val longPress = event.repeatCount > 0
        when {
            isPrevKey(keyCode) -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            isNextKey(keyCode) -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeyPage(PageDirection.PREV, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeyPage(PageDirection.NEXT, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * 松开按键事件
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDirection.NONE, false)) {
                    return true
                }
            }

        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * view触摸,文字选择
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean = binding.run {
        if (!binding.readView.isTextSelected) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> textActionMenu.dismiss()
            MotionEvent.ACTION_MOVE -> {
                if (getPrefBoolean(PreferKey.selectionMagnifier, true)) {
                    binding.selectionMagnifierView.setFinger(event.rawX, event.rawY)
                }
                val handleX = selectionHandleTouchX(v.id, event.rawX)
                val handleY = event.rawY - selectionHandleDragOffset()
                when (v.id) {
                    R.id.cursor_left -> if (!readView.curPage.getReverseStartCursor()) {
                        readView.curPage.selectStartMove(handleX, handleY)
                    } else {
                        readView.curPage.selectEndMove(handleX, handleY)
                    }

                    R.id.cursor_right -> if (readView.curPage.getReverseEndCursor()) {
                        readView.curPage.selectStartMove(handleX, handleY)
                    } else {
                        readView.curPage.selectEndMove(handleX, handleY)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                readView.curPage.resetReverseCursor()
                dismissSelectionMagnifier()
                showTextActionMenu()
            }
        }
        return true
    }

    /**
     * 更新文字选择开始位置
     */
    override fun upSelectedStart(x: Float, y: Float, top: Float) = binding.run {
        val style = selectionHandleStyle()
        val lineHeight = (y - top).coerceAtLeast(1f)
        selectionHandleLeftRodHeight = lineHeight
        selectionHandleLeftTextBottomY = y
        selectionHandleLeftBottomY = if (style == 0) {
            y + selectionHandleDropletDiameter
        } else {
            y + selectionHandleBallOnlyHeight
        }
        upSelectionHandle()
        cursorLeft.x = if (style == 0) {
            x - cursorLeft.width / 2f - selectionHandleDropletRadius
        } else {
            x - cursorLeft.width / 2f
        }
        cursorLeft.y = if (style == 0) top else y
        textMenuPosition.x = x
        textMenuPosition.y = top
        if (getPrefBoolean(PreferKey.selectionMagnifier, true)) {
            selectionMagnifierView.setHandle(x, y)
        }
    }

    /**
     * 更新文字选择结束位置
     */
    override fun upSelectedEnd(x: Float, y: Float, top: Float) = binding.run {
        val style = selectionHandleStyle()
        val lineHeight = (y - top).coerceAtLeast(1f)
        selectionHandleRightRodHeight = lineHeight
        selectionHandleRightBottomY = if (style == 0) {
            y + selectionHandleDropletDiameter
        } else {
            y + selectionHandleBallOnlyHeight
        }
        upSelectionHandle()
        cursorRight.x = if (style == 0) {
            x - cursorRight.width / 2f + selectionHandleDropletRadius
        } else {
            x - cursorRight.width / 2f
        }
        cursorRight.y = if (style == 0) top else y
        if (getPrefBoolean(PreferKey.selectionMagnifier, true)) {
            selectionMagnifierView.setHandle(x, y)
        }
    }

    /**
     * 球+杆样式的固定球体尺寸；杆高由当前文字行的 top/bottom 动态决定。
     */
    private val selectionHandleDropletDiameter = 16.dpToPx().toFloat()

    /**
     * 仅球样式的固定 View 高度。
     */
    private val selectionHandleBallOnlyHeight = 24.dpToPx().toFloat()

    /**
     * 球+杆样式中，手指按在水滴球心时，命中行底所需减去的距离。
     */
    private val selectionHandleDropletRadius = 8.dpToPx().toFloat()

    /**
     * 仅球样式中，手指按在球心时，命中行底所需减去的距离。
     */
    private val selectionHandleBallOnlyCenter = 12.dpToPx().toFloat()

    private var selectionHandleLeftTextBottomY = 0f
    private var selectionHandleLeftBottomY = 0f
    private var selectionHandleRightBottomY = 0f
    private var selectionHandleLeftRodHeight = 1f
    private var selectionHandleRightRodHeight = 1f

    private fun selectionHandleStyle(): Int {
        return getPrefInt(PreferKey.selectionHandleStyle, 0).coerceIn(0, 2)
    }

    /**
     * 球+杆样式的水滴尖角位于球心的内侧 8dp，拖动时把手指坐标还原为尖角坐标。
     */
    private fun selectionHandleTouchX(viewId: Int, rawX: Float): Float {
        if (selectionHandleStyle() != 0) return rawX
        return if (viewId == R.id.cursor_left) {
            rawX + selectionHandleDropletRadius
        } else {
            rawX - selectionHandleDropletRadius
        }
    }

    /**
     * 拖动时手指（按在球上）到锚点的偏移，使球心跟随手指：
     * 球+杆：杆高=文字行高，水滴尖端=行底，球心=行底+8dp → 偏移 = 8dp
     * 仅球：锚点=行底，球心=锚点+12dp → 偏移 = 12dp
     */
    private fun selectionHandleDragOffset(): Float {
        return if (selectionHandleStyle() == 0) {
            selectionHandleDropletRadius
        } else {
            selectionHandleBallOnlyCenter
        }
    }

    /**
     * 选区手柄：样式（球+杆/仅球/无）、颜色（支持透明度）与可见性统一在这里应用
     */
    private fun upSelectionHandle() {
        val style = selectionHandleStyle()
        if (style == 2) {
            binding.cursorLeft.invisible()
            binding.cursorRight.invisible()
            return
        }
        if (style == 1) {
            binding.cursorLeft.setImageResource(R.drawable.ic_cursor_left_ball)
            binding.cursorRight.setImageResource(R.drawable.ic_cursor_right_ball)
        } else {
            binding.cursorLeft.setImageDrawable(
                SelectionHandleDrawable(
                    SelectionHandleDrawable.Side.LEFT,
                    selectionHandleLeftRodHeight
                )
            )
            binding.cursorRight.setImageDrawable(
                SelectionHandleDrawable(
                    SelectionHandleDrawable.Side.RIGHT,
                    selectionHandleRightRodHeight
                )
            )
        }
        val handleColor = getPrefInt(PreferKey.selectionHandleColor, 0)
        val color = if (handleColor != 0) handleColor else accentColor
        binding.cursorLeft.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        binding.cursorRight.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        binding.cursorLeft.visible(true)
        binding.cursorRight.visible(true)
    }

    /**
     * 关闭选区放大镜
     */
    override fun dismissSelectionMagnifier() {
        binding.selectionMagnifierView.dismiss()
    }

    /**
     * 选区拖动中更新放大镜镜片位置（跟随手指）
     */
    override fun updateSelectionFinger(x: Float, y: Float) {
        if (!getPrefBoolean(PreferKey.selectionMagnifier, true)) return
        binding.selectionMagnifierView.setFinger(x, y)
    }

    /**
     * 取消文字选择
     */
    override fun onCancelSelect() = binding.run {
        cursorLeft.invisible()
        cursorRight.invisible()
        selectionMagnifierView.dismiss()
        textActionMenu.dismiss()
    }

    override fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean {
        return binding.readView.onTouchEvent(event)
    }

    /**
     * 显示文本操作菜单
     */
    override fun showTextActionMenu() {
        illustrationAnchor = computeIllustrationAnchor()
        textActionMenu.illustrationEnabled = illustrationAnchor != null
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        val startX = binding.textMenuPosition.x.toInt()
        val topY = binding.textMenuPosition.y.toInt()
        val endX = binding.cursorRight.x.toInt()
        val startTextBottomY = selectionHandleLeftTextBottomY.toInt()
        val startBottomY = selectionHandleLeftBottomY.toInt()
        val endBottomY = selectionHandleRightBottomY.toInt()
        val centerX = ((startX + endX) / 2f).toInt()
        val bottomY = maxOf(startBottomY, endBottomY)
        lastTextMenuAnchor = ReadAiFloatingPanel.Anchor(
            centerX = centerX,
            topY = topY,
            bottomY = bottomY
        )
        textActionMenu.show(
            binding.root,
            binding.root.height + navigationBarHeight,
            startX,
            topY,
            startTextBottomY,
            startBottomY,
            endX,
            endBottomY
        )
    }

    /**
     * 当前选择的文本
     */
    override val selectedText: String get() = binding.readView.getSelectText()

    /**
     * 计算选区对应的配图插入锚点：
     *
     * 规则：任意文本选区都以第一段为插入边界，插到第一段与下一段之间；
     * 跨多段时（如选中 1/2/3 段）仍只取第一段，插到 1~2 段之间；
     * 第一段是该章最后一段时，插入该章末尾。
     */
    private fun computeIllustrationAnchor(): IllustrationAnchor? {
        val book = ReadBook.book ?: return null
        val chapter = ReadBook.curTextChapter ?: return null
        val pageView = binding.readView.curPage
        val startPos = pageView.selectStartPos
        val endPos = pageView.selectEndPos
        if (!startPos.isSelected() || !endPos.isSelected()) return null
        val startPage = pageView.relativePage(startPos.relativePagePos)
        val endPage = pageView.relativePage(endPos.relativePagePos)
        val startParaNum = startPage.getLine(startPos.lineIndex).paragraphNum
        val endParaNum = endPage.getLine(endPos.lineIndex).paragraphNum
        if (startParaNum <= 0 || endParaNum <= 0) return null
        val chapterParagraphs = chapter.paragraphs
        val lastParaNum = chapterParagraphs.lastOrNull()?.num ?: return null
        val frontNum = min(startParaNum, endParaNum)
        if (frontNum == lastParaNum) {
            // 第一段已是章末：插入该章末尾
            val paragraph = chapterParagraphs.getOrNull(frontNum - 1) ?: return null
            return IllustrationAnchor(
                anchorType = BookIllustration.ANCHOR_CHAPTER_END,
                anchorPos = -1,
                frontParagraph = paragraph.text,
                backParagraph = ""
            )
        }
        val frontParagraph = chapterParagraphs.getOrNull(frontNum - 1) ?: return null
        val backParagraph = chapterParagraphs.getOrNull(frontNum) ?: return null
        val anchorPos = frontParagraph.lastLine.chapterPosition +
            frontParagraph.lastLine.charSize +
            if (frontParagraph.isParagraphEnd) 1 else 0
        return IllustrationAnchor(
            anchorType = BookIllustration.ANCHOR_BETWEEN_PARAGRAPHS,
            anchorPos = anchorPos,
            frontParagraph = frontParagraph.text,
            backParagraph = backParagraph.text
        )
    }

    /**
     * 文本选择菜单操作
     */
    override fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.menu_illustration -> {
                illustrationAnchor?.let { anchor ->
                    val dialog = IllustrationEditDialog(anchor)
                    dialog.setOnInserted {
                        ReadBook.loadContent(resetPageOffset = true)
                    }
                    showDialogFragment(dialog)
                }
                return true
            }

            R.id.menu_aloud -> {
                lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    binding.readView.aloudStartSelect()
                }
                return true
            }

            R.id.menu_bookmark -> binding.readView.curPage.let {
                val bookmark = it.createBookmark()
                if (bookmark == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    showDialogFragment(BookmarkDialog(bookmark))
                }
                return true
            }

            R.id.menu_paragraph_bookmark -> binding.readView.curPage.let {
                val bookmark = it.createParagraphBookmark()
                if (bookmark == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    showDialogFragment(BookmarkDialog(bookmark))
                }
                return true
            }

            R.id.menu_replace -> {
                val scopes = arrayListOf<String>()
                ReadBook.book?.name?.let {
                    scopes.add(it)
                }
                ReadBook.bookSource?.bookSourceUrl?.let {
                    scopes.add(it)
                }
                val text = selectedText.lineSequence().map { it.trim() }.joinToString("\n")
                replaceActivity.launch(
                    ReplaceEditActivity.startIntent(
                        this,
                        pattern = text,
                        scope = scopes.joinToString(";")
                    )
                )
                return true
            }

            R.id.menu_search_content -> {
                viewModel.searchContentQuery = selectedText
                openSearchActivity(selectedText)
                return true
            }

            R.id.menu_dict -> {
                showDialogFragment(DictDialog(selectedText))
                return true
            }
            R.id.menu_ask_ai -> {
                askAiBySelection()
                return true
            }
        }
        return false
    }

    private fun askAiBySelection() {
        val prompt = selectedText.trim()
        if (prompt.isEmpty()) return
        if (AppConfig.aiCurrentProvider?.baseUrl.isNullOrBlank() || AppConfig.aiCurrentModelConfig == null) {
            toastOnUi(R.string.ai_missing_config)
            return
        }
        if (!AppConfig.aiAssistantEnabled) {
            toastOnUi(R.string.ai_not_enabled)
            return
        }
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter?.chapter
        val anchor = lastTextMenuAnchor
            ?: ReadAiFloatingPanel.Anchor(
                centerX = binding.root.width / 2,
                topY = binding.root.height / 3,
                bottomY = binding.root.height / 2
            )
        binding.readAiPanel.open(
            ReadAiFloatingPanel.ReadContext(
                bookUrl = book?.bookUrl.orEmpty().ifBlank { book?.name.orEmpty() },
                bookName = book?.name.orEmpty(),
                author = book?.author.orEmpty(),
                sourceName = ReadBook.bookSource?.bookSourceName.orEmpty(),
                chapterTitle = chapter?.title.orEmpty(),
                chapterIndex = chapter?.index ?: ReadBook.durChapterIndex,
                selectedText = prompt
            ),
            anchor = anchor
        )
    }

    /**
     * 文本选择菜单操作完成
     */
    override fun onMenuActionFinally() = binding.run {
        textActionMenu.dismiss()
        readView.cancelSelect()
    }

    /**
     * 鼠标滚轮翻页
     */
    private fun mouseWheelPage(direction: PageDirection, distance: Float) {
        if (menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
            return
        }
        if (binding.readView.isScroll) {
            // 滚动视图时滚动，否则翻页
            (binding.readView.pageDelegate as? ScrollPageDelegate)?.curPage?.scroll((distance * 50).toInt())
        } else {
            keyPageDebounce(direction, mouseWheel = true, longPress = false)
        }
    }

    /**
     * 音量键翻页
     */
    private fun volumeKeyPage(direction: PageDirection, longPress: Boolean): Boolean {
        if (!AppConfig.volumeKeyPage) {
            return false
        }
        if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
            return false
        }
        // 内嵌音频块播放时，按设置决定音量键是否让位给系统调音量（像听书一样）
        if (AppConfig.illustrationAudioVolumeKey && AudioBlockPlayer.isPlaying) {
            return false
        }
        handleKeyPage(direction, longPress)
        return true
    }

    private fun handleKeyPage(direction: PageDirection, longPress: Boolean) {
        if (AppConfig.keyPageOnLongPress || direction == PageDirection.NONE) {
            keyPage(direction)
        } else {
            keyPageDebounce(direction, longPress = longPress)
        }
    }

    private fun keyPageDebounce(
        direction: PageDirection,
        mouseWheel: Boolean = false,
        longPress: Boolean
    ) {
        if (longPress) {
            return
        }
        nextPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        prevPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        when (direction) {
            PageDirection.NEXT -> nextPageDebounce.invoke()
            PageDirection.PREV -> prevPageDebounce.invoke()
            else -> {}
        }
    }

    private fun keyPage(direction: PageDirection) {
        binding.readView.cancelSelect()
        binding.readView.pageDelegate?.isCancel = false
        binding.readView.pageDelegate?.keyTurnPage(direction)
    }

    override fun upMenuView() {
        handler.post {
            upMenu()
            binding.readMenu.upBookView()
        }
    }

    override fun loadChapterList(book: Book) {
        ReadBook.upMsg(getString(R.string.toc_updateing))
        viewModel.loadChapterList(book)
    }

    /**
     * 内容加载完成
     */
    override fun contentLoadFinish(trigger: String) {
        if (intent.getBooleanExtra("readAloud", false)) {
            intent.removeExtra("readAloud")
            ReadBook.readAloud()
        }
        loadStates = true
        val currentChapterIndex = ReadBook.durChapterIndex
        val force = aiChapterPurifyRefreshChapterIndex == currentChapterIndex
        if (force) {
            aiChapterPurifyRefreshChapterIndex = null
        }
        scheduleAiChapterPurify(force, source = "contentLoadFinish:$trigger")
    }

    /**
     * 更新内容
     */
    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        lifecycleScope.launch {
            binding.readView.upContent(relativePosition, resetPageOffset)
            if (relativePosition == 0) {
                postAttachReadAloudProgressIfCurrentPage()
                upSeekBarProgress()
            }
            loadStates = false
            success?.invoke()
        }
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) = withContext(Main.immediate) {
        binding.readView.upContent(relativePosition, resetPageOffset)
        if (relativePosition == 0) {
            postAttachReadAloudProgressIfCurrentPage()
            upSeekBarProgress()
        }
        loadStates = false
    }

    override fun upPageAnim(upRecorder: Boolean) {
        lifecycleScope.launch {
            binding.readView.upPageAnim(upRecorder)
        }
    }

    override fun notifyBookChanged() {
        bookChanged = true
        if (!ReadBook.inBookshelf) {
            viewModel.removeFromBookshelf { super.finish() }
        }
    }

    override fun cancelSelect() {
        runOnUiThread {
            binding.readView.cancelSelect()
        }
    }

    /**
     * 页面改变
     */
    override fun pageChanged() {
        pageChanged = true
        upChapterBookmarks()
        binding.readView.onPageChange()
        postAttachReadAloudProgressIfCurrentPage()
        handler.post {
            upSeekBarProgress()
        }
        executor.execute {
            startBackupJob()
        }
    }

    /**
     * 加载当前章节的书签数据并注入阅读页（用于书签样式渲染与备注气泡）
     */
    private fun upChapterBookmarks() {
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        if (bookmarkLoadChapterIndex == chapterIndex) return
        bookmarkLoadChapterIndex = chapterIndex
        lifecycleScope.launch(IO) {
            val bookmarks = appDb.bookmarkDao.getByBook(book.name, book.author)
                .filter { it.chapterIndex == chapterIndex }
            withContext(Main) {
                binding.readView.setBookmarks(bookmarks)
            }
        }
    }

    /**
     * 书签新增/编辑/删除后重新排版当前章节，让阅读页应用最新的书签样式
     */
    private fun reloadCurrentChapterForBookmark() {
        if (!isInitFinish) return
        ReadBook.openChapter(ReadBook.durChapterIndex, ReadBook.durChapterPos, false)
    }

    /**
     * 更新进度条位置
     */
    private fun upSeekBarProgress() {
        val progress = when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.durPageIndex
            else /* chapter */ -> ReadBook.durChapterIndex
        }
        binding.readMenu.setSeekPage(progress)
    }

    /**
     * 显示菜单
     */
    override fun showMenuBar() {
        binding.readMenu.runMenuIn()
    }

    override val oldBook: Book?
        get() = ReadBook.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (!book.isAudio) {
            // 换源属于强制重算：内容重新加载完成后强制净化当前章
            requestAiChapterPurifyAfterRefresh()
            viewModel.changeTo(book, toc)
        } else {
            ReadAloud.stop(this)
            lifecycleScope.launch {
                withContext(IO) {
                    ReadBook.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    ReadBook.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun replaceContent(content: String) {
        ReadBook.book?.let {
            viewModel.saveContent(it, content)
        }
    }

    override fun showActionMenu() {
        when {
            isAutoPage -> showDialogFragment<AutoReadDialog>()
            isShowingSearchResult -> binding.searchMenu.runMenuIn()
            BaseReadAloudService.isRun -> showReadAloudDialog()
            else -> binding.readMenu.runMenuIn()
        }
    }

    /**
     * 强制显示主菜单（听书/自动翻页/全文搜索时也直接打开阅读主菜单）
     */
    override fun showForceMainMenu() {
        binding.readMenu.runMenuIn()
    }

    /**
     * 显示朗读菜单
     */
    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

    fun toReadAloudBackstage() {
        if (AppConfig.readAloudFloatOnDesktop) {
            requestReadAloudFloatPermissionIfNeeded()
        }
        ReadBook.saveRead()
        finishReadAloudBackstage = true
        finish()
    }

    private fun requestReadAloudFloatPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            return
        }
        alert(R.string.float_permission_rationale) {
            okButton {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri()
                    )
                )
            }
            noButton()
        }
    }

    private fun updateReadAloudPageFloating() {
        activeActivityRef = WeakReference(this)
        postEvent(EventBus.READ_BOOK_ACTIVITY_ACTIVE, true)
    }

    private fun updateReadAloudMainMenuVisibility(visible: Boolean) {
        ReadAloudUiState.setMainMenuVisible(visible)
        postEvent(EventBus.READ_MAIN_MENU_VISIBILITY, visible)
        updateReadAloudPanels()
    }

    private fun showReadAloudDialogFromFloating() {
        if (binding.readMenu.isVisible) {
            binding.readMenu.runMenuOut {
                showReadAloudDialog()
            }
        } else {
            showReadAloudDialog()
        }
    }

    private fun updateReadAloudPanels() {
        when (
            ReadAloudUiState.readerPanelMode(
                BaseReadAloudService.isRun,
                ReadBook.readAloudPageDetached,
            )
        ) {
            ReadAloudUiState.ReaderPanelMode.HIDDEN -> {
                hideReadAloudPagePanel()
                resetReadAloudPlaybackPresentation()
            }
            ReadAloudUiState.ReaderPanelMode.PLAYBACK -> {
                hideReadAloudPagePanel()
                if (AppConfig.readAloudHidePlaybackPanel) {
                    showReadAloudPlaybackInFooterOnly()
                } else {
                    updateReadAloudPlaybackPresentation()
                }
            }
            ReadAloudUiState.ReaderPanelMode.PAGE_ACTION -> {
                resetReadAloudPlaybackPresentation()
                if (AppConfig.readAloudHidePagePanel) {
                    hideReadAloudPagePanel()
                } else {
                    showReadAloudPagePanel()
                }
            }
        }
    }

    private fun updateReadAloudPlaybackPresentation() {
        when (readAloudPlaybackPresentation) {
            ReadAloudPlaybackPresentation.HIDDEN -> {
                readAloudPlaybackPresentation = ReadAloudPlaybackPresentation.PANEL
                clearReadAloudPlaybackInFooter()
                showReadAloudPlaybackPanel()
                handler.postDelayed(
                    collapseReadAloudPlaybackPanel,
                    AppConfig.readAloudPlaybackPanelDuration * 1_000L
                )
            }
            ReadAloudPlaybackPresentation.PANEL -> showReadAloudPlaybackPanel()
            ReadAloudPlaybackPresentation.FOOTER -> {
                hideReadAloudPlaybackPanelView()
                showReadAloudPlaybackInFooter()
            }
        }
    }

    private fun showReadAloudPlaybackPanel() {
        if (!BaseReadAloudService.isRun) return
        binding.readAloudPlaybackPanel.post {
            if (
                readAloudPlaybackPresentation != ReadAloudPlaybackPresentation.PANEL ||
                AppConfig.readAloudHidePlaybackPanel ||
                ReadAloudUiState.readerPanelMode(
                    BaseReadAloudService.isRun,
                    ReadBook.readAloudPageDetached,
                ) != ReadAloudUiState.ReaderPanelMode.PLAYBACK
            ) {
                return@post
            }
            val params = binding.readAloudPlaybackPanel.layoutParams as? FrameLayout.LayoutParams
                ?: return@post
            val bottomMargin = readAloudPanelBottomMargin()
            if (params.bottomMargin != bottomMargin) {
                params.bottomMargin = bottomMargin
                binding.readAloudPlaybackPanel.layoutParams = params
            }
            binding.btnReadAloudPlayback.setText(
                if (BaseReadAloudService.pause) {
                    R.string.read_aloud_resume_playback
                } else {
                    R.string.read_aloud_pause_playback
                }
            )
            fadeReadAloudPanel(binding.readAloudPlaybackPanel, true)
            postReadAloudFloatingAvoidanceForView(
                EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_PLAYBACK_PANEL,
                binding.readAloudPlaybackPanel,
            )
        }
    }

    private fun hideReadAloudPlaybackPanelView(immediate: Boolean = false) {
        fadeReadAloudPanel(binding.readAloudPlaybackPanel, false, immediate)
        clearReadAloudFloatingAvoidance(EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_PLAYBACK_PANEL)
    }

    private fun resetReadAloudPlaybackPresentation(immediate: Boolean = false) {
        handler.removeCallbacks(collapseReadAloudPlaybackPanel)
        readAloudPlaybackPresentation = ReadAloudPlaybackPresentation.HIDDEN
        hideReadAloudPlaybackPanelView(immediate)
        clearReadAloudPlaybackInFooter()
    }

    private fun showReadAloudPlaybackInFooterOnly() {
        handler.removeCallbacks(collapseReadAloudPlaybackPanel)
        readAloudPlaybackPresentation = ReadAloudPlaybackPresentation.FOOTER
        hideReadAloudPlaybackPanelView()
        showReadAloudPlaybackInFooter()
    }

    private fun showReadAloudPlaybackInFooter() {
        val text = getText(
            if (BaseReadAloudService.pause) {
                R.string.read_aloud_resume_playback
            } else {
                R.string.read_aloud_pause_playback
            }
        )
        binding.readView.setFooterCenterAction(text, ::toggleReadAloudPlayback)
    }

    private fun clearReadAloudPlaybackInFooter() {
        binding.readView.setFooterCenterAction(null, null)
    }

    private fun toggleReadAloudPlayback() {
        if (BaseReadAloudService.pause) {
            ReadAloud.resume(this)
        } else {
            ReadAloud.pause(this)
        }
    }

    private fun showReadAloudPagePanel() {
        if (!BaseReadAloudService.isRun) return
        binding.readAloudPagePanel.post {
            if (
                ReadAloudUiState.readerPanelMode(
                    BaseReadAloudService.isRun,
                    ReadBook.readAloudPageDetached,
                ) != ReadAloudUiState.ReaderPanelMode.PAGE_ACTION
            ) {
                return@post
            }
            val params = binding.readAloudPagePanel.layoutParams as? FrameLayout.LayoutParams
                ?: return@post
            val bottomMargin = readAloudPanelBottomMargin()
            if (params.bottomMargin != bottomMargin) {
                params.bottomMargin = bottomMargin
                binding.readAloudPagePanel.layoutParams = params
            }
            fadeReadAloudPanel(binding.readAloudPagePanel, true)
            postReadAloudFloatingAvoidanceForView(
                EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_PAGE_PANEL,
                binding.readAloudPagePanel
            )
        }
    }

    private fun hideReadAloudPagePanel(immediate: Boolean = false) {
        fadeReadAloudPanel(binding.readAloudPagePanel, false, immediate)
        clearReadAloudFloatingAvoidance(EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_PAGE_PANEL)
    }

    private fun readAloudPanelBottomMargin(): Int {
        val footerClearance = if (AppConfig.readAloudPanelOnPageFooter) {
            0
        } else {
            resources.getDimensionPixelSize(R.dimen.read_aloud_compact_panel_footer_clearance)
        }
        return binding.navigationBar.height + footerClearance
    }

    private fun fadeReadAloudPanel(view: View, show: Boolean, immediate: Boolean = false) {
        view.animate().cancel()
        val generation = ((view.getTag(R.id.tag1) as? Long) ?: 0L) + 1L
        view.setTag(R.id.tag1, generation)
        if (!show && immediate) {
            view.alpha = 0f
            view.gone()
            return
        }
        if (show) {
            val wasVisible = view.visibility == View.VISIBLE
            view.visible()
            if (!wasVisible) view.alpha = 0f
            view.animate()
                .alpha(1f)
                .setDuration(readAloudPanelFadeDuration)
                .start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate()
                .alpha(0f)
                .setDuration(readAloudPanelFadeDuration)
                .withEndAction {
                    if (view.getTag(R.id.tag1) == generation) {
                        view.gone()
                    }
                }
                .start()
        } else {
            view.alpha = 0f
        }
    }

    private fun restoreReadAloudPlayerPosition() {
        val chapterIndex = lastReadAloudChapterIndex ?: ReadBook.durChapterIndex
        val chapterPos = lastReadAloudChapterPos ?: ReadBook.durChapterPos
        if (chapterIndex != ReadBook.durChapterIndex) {
            ReadBook.skipReadAloudSyncOnce = true
            val opened = ReadBook.openChapter(chapterIndex, chapterPos, true) {
                ReadBook.skipReadAloudSyncOnce = false
                binding.readView.upContent(resetPageOffset = false)
                updateReadAloudPanels()
            }
            if (!opened) {
                ReadBook.skipReadAloudSyncOnce = false
            }
        } else {
            ReadBook.durChapterPos = chapterPos
            binding.readView.upContent(resetPageOffset = false)
            updateReadAloudPanels()
        }
    }

    private fun backToReadAloudProgress() {
        val chapterPos = lastReadAloudChapterPos ?: return
        val chapterIndex = lastReadAloudChapterIndex ?: ReadBook.durChapterIndex
        ReadBook.curTextChapter
            ?.getPageByReadPos(ReadBook.durChapterPos)
            ?.removePageAloudSpan()
        ReadBook.attachReadAloudPage()
        if (ReadBook.durChapterIndex != chapterIndex) {
            ReadBook.skipReadAloudSyncOnce = true
            val opened = ReadBook.openChapter(chapterIndex, chapterPos) {
                ReadBook.skipReadAloudSyncOnce = false
                restoreReadAloudProgress(chapterPos)
            }
            if (!opened) {
                ReadBook.skipReadAloudSyncOnce = false
            }
        } else {
            restoreReadAloudProgress(chapterPos)
        }
    }

    private fun restoreReadAloudProgress(chapterPos: Int) {
        val textChapter = ReadBook.curTextChapter ?: return
        ReadBook.durChapterPos = chapterPos
        val pageIndex = ReadBook.durPageIndex
        val aloudSpanStart = (chapterPos - textChapter.getReadLength(pageIndex)).coerceAtLeast(0)
        textChapter.getPage(pageIndex)?.upPageAloudSpan(aloudSpanStart)
        ReadBook.saveRead(true)
        hideReadAloudPagePanel()
        binding.readView.upContent(resetPageOffset = false)
        upSeekBarProgress()
        updateReadAloudPanels()
    }

    private fun postAttachReadAloudProgressIfCurrentPage() {
        handler.post {
            attachReadAloudProgressIfCurrentPage()
        }
    }

    private fun attachReadAloudProgressIfCurrentPage() {
        if (!ReadBook.readAloudPageDetached || !BaseReadAloudService.isRun) return
        val chapterIndex = lastReadAloudChapterIndex ?: return
        val chapterPos = lastReadAloudChapterPos ?: return
        if (ReadBook.durChapterIndex != chapterIndex) return
        val textChapter = ReadBook.curTextChapter ?: return
        val readAloudPage = textChapter.getPageByReadPos(chapterPos) ?: return
        if (readAloudPage.index != ReadBook.durPageIndex) return
        textChapter.getPageByReadPos(ReadBook.durChapterPos)?.removePageAloudSpan()
        ReadBook.attachReadAloudPage()
        restoreReadAloudProgress(chapterPos)
    }

    private fun readAloudFromCurrentPage() {
        hideReadAloudPagePanel()
        ReadBook.attachReadAloudPage()
        if (ReadBook.pageAnim() == 3) {
            val pos = binding.readView.getReadAloudPos()
            if (pos != null) {
                val (index, line) = pos
                if (ReadBook.durChapterIndex != index) {
                    ReadBook.skipReadAloudSyncOnce = true
                    ReadBook.openChapter(index, line.chapterPosition, false) {
                        ReadBook.readAloud(startPos = line.pagePosition)
                    }
                } else {
                    ReadBook.durChapterPos = line.chapterPosition
                    ReadBook.readAloud(startPos = line.pagePosition)
                }
                return
            }
        }
        ReadBook.readAloud()
    }

    private fun postReadAloudFloatingAvoidance(source: String, y: Int) {
        postEvent(EventBus.READ_ALOUD_FLOATING_AVOIDANCE, Bundle().apply {
            putString("source", source)
            putInt("y", y)
        })
    }

    fun postReadAloudFloatingAvoidanceForView(source: String, view: View?) {
        val generation = (readAloudAvoidanceGenerations[source] ?: 0L) + 1L
        readAloudAvoidanceGenerations[source] = generation
        fun postForView() {
            if (readAloudAvoidanceGenerations[source] != generation) return
            val target = view ?: return
            val rect = Rect()
            if (target.visibility != View.VISIBLE ||
                !target.getGlobalVisibleRect(rect) ||
                rect.height() <= 0
            ) return
            postReadAloudFloatingAvoidance(source, rect.top)
        }
        view?.post { postForView() }
        view?.postDelayed({ postForView() }, 80L)
        view?.postDelayed({ postForView() }, 160L)
        view?.postDelayed({ postForView() }, 240L)
        view?.postDelayed({ postForView() }, 360L)
        view?.postDelayed({ postForView() }, 500L)
    }

    fun clearReadAloudFloatingAvoidance(source: String) {
        readAloudAvoidanceGenerations[source] =
            (readAloudAvoidanceGenerations[source] ?: 0L) + 1L
        postReadAloudFloatingAvoidance(source, 0)
    }

    /**
     * 自动翻页
     */
    override fun autoPage() {
        ReadAloud.stop(this)
        if (isAutoPage) {
            autoPageStop()
        } else {
            binding.readView.autoPager.start()
            binding.readMenu.setAutoPage(true)
            screenTimeOut = -1L
            screenOffTimerStart()
        }
    }

    override fun autoPageStop() {
        if (isAutoPage) {
            binding.readView.autoPager.stop()
            binding.readMenu.setAutoPage(false)
            dismissDialogFragment<AutoReadDialog>()
            upScreenTimeOut()
        }
    }

    override fun openSourceEditActivity() {
        ReadBook.bookSource?.let {
            sourceEditActivity.launch {
                putExtra("sourceUrl", it.bookSourceUrl)
            }
        }
    }

    override fun openBookInfoActivity() {
        ReadBook.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
                putExtra("bookUrl", it.bookUrl)
            }
        }
    }

    /**
     * 替换
     */
    override fun openReplaceRule() {
        replaceActivity.launch(Intent(this, ReplaceRuleActivity::class.java))
    }

    /**
     * 打开目录
     */
    override fun openChapterList() {
        ReadBook.book?.let {
            tocActivity.launch(it.bookUrl)
        }
    }

    /**
     * 打开搜索界面
     */
    override fun openSearchActivity(searchWord: String?) {
        val book = ReadBook.book ?: return
        searchContentActivity.launch {
            putExtra("bookUrl", book.bookUrl)
            putExtra("searchWord", searchWord ?: viewModel.searchContentQuery)
            putExtra("searchResultIndex", viewModel.searchResultIndex)
            viewModel.searchResultList?.first()?.let {
                if (it.query == viewModel.searchContentQuery) {
                    IntentData.put("searchResultList", viewModel.searchResultList)
                }
            }
        }
    }

    /**
     * 禁用书源
     */
    override fun disableSource() {
        viewModel.disableSource()
    }

    /**
     * 显示阅读样式配置
     */
    override fun showReadStyle() {
        showDialogFragment<ReadStyleDialog>()
    }

    /**
     * 显示更多设置
     */
    override fun showMoreSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    override fun showSearchSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    /**
     * 更新状态栏,导航栏
     */
    override fun upSystemUiVisibility() {
        upSystemUiVisibility(isInMultiWindow, !menuLayoutIsVisible, bottomDialog > 0)
        upNavigationBarColor()
    }

    // 退出全文搜索
    override fun exitSearchMenu() {
        if (isShowingSearchResult) {
            isShowingSearchResult = false
            binding.searchMenu.invalidate()
            binding.searchMenu.invisible()
            ReadBook.clearSearchResult()
            binding.readView.cancelSelect(true)
        }
    }

    /* 恢复到：全文搜索/进度条跳转前的位置 */
    private fun restoreLastBookProcess() {
        if (confirmRestoreProcess == true) {
            ReadBook.restoreLastBookProgress()
        } else if (confirmRestoreProcess == null) {
            alert(R.string.draw) {
                setMessage(R.string.restore_last_book_process)
                yesButton {
                    confirmRestoreProcess = true
                    ReadBook.restoreLastBookProgress() //恢复启动全文搜索前的进度
                }
                noButton {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
                onCancelled {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
            }
        }
    }

    override fun showLogin() {
        ReadBook.bookSource?.let {
            startActivity<SourceLoginActivity> {
                putExtra("bookType", BookType.text)
            }
        }
    }

    override fun payAction() {
        val book = ReadBook.book ?: return
        if (book.isLocal) return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        if (chapter == null) {
            toastOnUi("no chapter")
            return
        }
        alert(R.string.chapter_pay) {
            setMessage(chapter.title)
            yesButton {
                Coroutine.async(lifecycleScope) {
                    val source =
                        ReadBook.bookSource ?: throw NoStackTraceException("no book source")
                    val payAction = source.getContentRule().payAction
                    if (payAction.isNullOrBlank()) {
                        throw NoStackTraceException("no pay action")
                    }
                    val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                    runScriptWithContext {
                        source.evalJS(payAction) {
                            put("java", java)
                            put("book", book)
                            put("chapter", chapter)
                            put("title", chapter.title)
                            put("baseUrl", chapter.url)
                            put("result", null)
                            put("src", null)
                        }.toString()
                    }
                }.onSuccess(IO) {
                    if (it.isAbsUrl()) {
                        startActivity<WebViewActivity> {
                            val bookSource = ReadBook.bookSource
                            putExtra("title", getString(R.string.chapter_pay))
                            putExtra("url", it)
                            putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                            putExtra("sourceName", bookSource?.bookSourceName)
                            putExtra("sourceType", bookSource?.getSourceType())
                        }
                    } else if (it.isTrue()) {
                        //购买成功后刷新目录
                        ReadBook.book?.let {
                            ReadBook.curTextChapter = null
                            BookHelp.delContent(book, chapter)
                            loadChapterList(book)
                        }
                    }
                }.onError {
                    AppLog.put("执行购买操作出错\n${it.localizedMessage}", it, true)
                }
            }
            noButton()
        }
    }

    /**
     * 点击图片
     */
    override fun oldClickImg(src: String): Boolean {
        val urlMatcher = paramPattern.matcher(src)
        if (urlMatcher.find()) {
            val urlOptionStr = src.substring(urlMatcher.end())
            val urlOptionMap = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
            val click = urlOptionMap?.get("click")
            if (click != null) {
                Coroutine.async(lifecycleScope,IO) {
                    val source = ReadBook.bookSource ?: return@async
                    val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                    val book = ReadBook.book ?: return@async
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                    runScriptWithContext {
                        source.evalJS(click) {
                            put("java", java)
                            put("book", book)
                            put("chapter", chapter)
                            put("result", src)
                        }
                    }
                }.onError {
                    AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
                }
                return true
            }
            val jsStr = urlOptionMap?.get("js") ?: return false
            Coroutine.async(lifecycleScope, IO) {
                val source = ReadBook.bookSource ?: return@async
                val book = ReadBook.book ?: return@async
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                val urlNoOption = src.take(urlMatcher.start())
                AnalyzeRule(book, source).apply {
                    setCoroutineContext(coroutineContext)
                    setBaseUrl(chapter.url)
                    setChapter(chapter)
                    evalJS(jsStr, urlNoOption)
                }
            }.onError {
                AppLog.put("执行图片链接js键值出错\n${it.localizedMessage}", it, true)
            }
            return true
        }
        return false
    }

    override fun clickImg(click: String, src: String) {
        Coroutine.async(lifecycleScope,IO) {
            val source = ReadBook.bookSource ?: return@async
            val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
            runScriptWithContext {
                source.evalJS(click) {
                    put("java", java)
                    put("book", book)
                    put("chapter", chapter)
                    put("result", src)
                }
            }
        }.onError {
            AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
        }
    }


    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                if (AppConfig.readAloudFloatOnDesktop) {
                    requestReadAloudFloatPermissionIfNeeded()
                }
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadBook.readAloud()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

    override fun showHelp() {
        showHelp("readMenuHelp")
    }

    /**
     * 长按图片
     */
    @SuppressLint("RtlHardcoded")
    override fun onImageLongPress(x: Float, y: Float, src: String) {
        val items = arrayListOf<SelectItem<String>>()
        if (src.startsWith(IllustrationHelp.SRC_PREFIX)) {
            // 配图：只保留保存（前）与删除（后），多图时增加"保存所有"
            items.add(SelectItem(getString(R.string.illustration_save_to_album), "saveToAlbum"))
            if (illustrationImageCount(src) >= 2) {
                items.add(
                    SelectItem(getString(R.string.illustration_save_all), "saveAllIllustrations")
                )
            }
            items.add(SelectItem(getString(R.string.illustration_delete), "deleteIllustration"))
        } else {
            // 普通图片：完全保留原始菜单
            items.add(SelectItem(getString(R.string.show), "show"))
            items.add(SelectItem(getString(R.string.refresh), "refresh"))
            items.add(SelectItem(getString(R.string.action_save), "save"))
            items.add(SelectItem(getString(R.string.menu), "menu"))
            items.add(SelectItem(getString(R.string.select_folder), "selectFolder"))
        }
        popupAction.setItems(items)
        popupAction.onActionClick = {
            when (it) {
                "show" -> showDialogFragment(PhotoDialog(src, isBook = true))
                "refresh" -> viewModel.refreshImage(src)
                "saveToAlbum" -> saveIllustrationToAlbum(src)
                "saveAllIllustrations" -> saveAllIllustrations(src)
                "deleteIllustration" -> deleteIllustration(src)
                "save" -> {
                    val path = ACache.get().getAsString(AppConst.imagePathKey)
                    if (path.isNullOrEmpty()) {
                        selectImageDir.launch {
                            value = src
                        }
                    } else {
                        viewModel.saveImage(src, path.toUri())
                    }
                }

                "menu" -> showActionMenu()
                "selectFolder" -> selectImageDir.launch()
            }
            popupAction.dismiss()
        }
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        popupAction.showAtLocation(
            binding.readView, Gravity.BOTTOM or Gravity.LEFT, x.toInt(),
            binding.root.height + navigationBarHeight - y.toInt()
        )
    }

    /** src 所属配图记录包含的图片总数（多图判定） */
    private fun illustrationImageCount(src: String): Int {
        val book = ReadBook.book ?: return 0
        return appDb.bookIllustrationDao.getByBook(book.bookUrl)
            .filter { it.imageSrcsFromJson().contains(src) }
            .sumOf { it.imageSrcsFromJson().size }
    }

    private fun saveIllustrationToAlbum(src: String) {
        val book = ReadBook.book ?: return
        lifecycleScope.launch(IO) {
            val ok = IllustrationHelp.saveToAlbum(this@ReadBookActivity, book, src)
            withContext(Main) {
                toastOnUi(
                    if (ok) R.string.illustration_saved_to_album else R.string.illustration_save_failed
                )
            }
        }
    }

    private fun saveAllIllustrations(src: String) {
        val book = ReadBook.book ?: return
        lifecycleScope.launch(IO) {
            val records = appDb.bookIllustrationDao.getByBook(book.bookUrl)
                .filter { it.imageSrcsFromJson().contains(src) }
            val srcs = records.flatMap { it.imageSrcsFromJson() }.distinct()
            var ok = true
            srcs.forEach { s ->
                if (!IllustrationHelp.saveToAlbum(this@ReadBookActivity, book, s)) {
                    ok = false
                }
            }
            withContext(Main) {
                toastOnUi(
                    if (ok) R.string.illustration_saved_to_album else R.string.illustration_save_failed
                )
            }
        }
    }

    private fun deleteIllustration(src: String) {
        val book = ReadBook.book ?: return
        val records = appDb.bookIllustrationDao.getByBook(book.bookUrl)
            .filter { it.imageSrcsFromJson().contains(src) }
        if (records.isEmpty()) return
        appDb.bookIllustrationDao.delete(*records.toTypedArray())
        records.forEach {
            IllustrationHelp.deleteImages(book, it.imageSrcsFromJson())
        }
        toastOnUi(R.string.illustration_deleted)
        ReadBook.loadContent(resetPageOffset = true)
    }

    /**
     * colorSelectDialog
     */
    override fun onColorSelected(dialogId: Int, color: Int) = ReadBookConfig.durConfig.run {
        when (dialogId) {
            TEXT_COLOR -> {
                setCurTextColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            TEXT_ACCENT_COLOR -> {
                setCurTextAccentColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            BG_COLOR -> {
                setCurBg(0, "#${color.hexString}")
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            READ_MENU_BG_COLOR -> {
                if (color == ColorPickerDialogCompat.DEFAULT_COLOR) {
                    clearCurReadMenuBgColor()
                } else {
                    setCurReadMenuBgColor(color)
                }
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }

            TIP_COLOR -> {
                ReadTipConfig.tipColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }

            TIP_DIVIDER_COLOR -> {
                ReadTipConfig.tipDividerColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }
        }
    }

    /**
     * colorSelectDialog
     */
    override fun onDialogDismissed(dialogId: Int) = Unit

    override fun onTocRegexDialogResult(tocRegex: String) {
        ReadBook.book?.let {
            it.tocUrl = tocRegex
            loadChapterList(it)
        }
    }

    private fun sureSyncProgress(progress: BookProgress) {
        alert(R.string.get_book_progress) {
            setMessage(R.string.current_progress_exceeds_cloud)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    /* 进度条跳转到指定章节 */
    override fun skipToChapter(index: Int) {
        ReadBook.saveCurrentBookProgress() //退出章节跳转恢复此时进度
        viewModel.openChapter(index)
    }

    /* 全文搜索跳转 */
    override fun navigateToSearch(searchResult: SearchResult, index: Int) {
        viewModel.searchResultIndex = index
        skipToSearch(searchResult)
    }

    override fun onMenuShow() {
        binding.readView.autoPager.pause()
    }

    override fun onMenuHide() {
        binding.readView.autoPager.resume()
    }

    override fun onReadMenuAvoidanceChanged(show: Boolean) {
        if (show) {
            postReadAloudFloatingAvoidanceForView(
                EventBus.FLOATING_AVOID_SOURCE_READ_MENU,
                binding.readMenu.bottomMenuView()
            )
        } else {
            clearReadAloudFloatingAvoidance(EventBus.FLOATING_AVOID_SOURCE_READ_MENU)
        }
        updateReadAloudMainMenuVisibility(show)
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upSeekBarThrottle.invoke()
        binding.readView.onLayoutPageCompleted(index, page)
    }

    /* 全文搜索跳转 */
    private fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != ReadBook.durChapterIndex) {
            viewModel.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = ReadBook.curTextChapter ?: return
        binding.searchMenu.updateSearchInfo()
        val searchResultPositions =
            viewModel.searchResultPositions(curTextChapter, searchResult)
        val (pageIndex, lineIndex, charIndex, addLine, charIndex2) = searchResultPositions
        ReadBook.skipToPage(pageIndex) {
            isSelectingSearchResult = true
            binding.readView.curPage.selectStartMoveIndex(0, lineIndex, charIndex)
            when (addLine) {
                0 -> binding.readView.curPage.selectEndMoveIndex(
                    0,
                    lineIndex,
                    charIndex + searchResultPositions[5] - 1
                )

                1 -> binding.readView.curPage.selectEndMoveIndex(
                    0, lineIndex + 1, charIndex2
                )
                //consider change page, jump to scroll position
                -1 -> binding.readView.curPage.selectEndMoveIndex(1, 0, charIndex2)
            }
            binding.readView.isTextSelected = true
            isSelectingSearchResult = false
        }
    }

    override fun addBookmark() {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book != null && page != null) {
            val bookmark = book.createBookMark().apply {
                chapterIndex = ReadBook.durChapterIndex
                chapterPos = ReadBook.durChapterPos
                chapterName = page.title
                bookText = page.text.trim()
            }
            showDialogFragment(BookmarkDialog(bookmark))
        }
    }

    override fun addPageBookmark(): Boolean {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book == null || page == null) {
            return false
        }
        val pageText = page.text.trim()
        if (pageText.isBlank()) {
            return false
        }
        // 同页已存在整页书签时不重复添加
        val exists = appDb.bookmarkDao.getByBook(book.name, book.author).any {
            it.isPageBookmark &&
                it.chapterIndex == ReadBook.durChapterIndex &&
                it.bookText.trim() == pageText
        }
        if (exists) {
            toastOnUi(R.string.page_bookmark_added)
            return false
        }
        val bookmark = book.createBookMark().apply {
            chapterIndex = ReadBook.durChapterIndex
            chapterPos = ReadBook.durChapterPos
            chapterName = page.title
            bookText = pageText
            isPageBookmark = true
        }
        appDb.bookmarkDao.insert(bookmark)
        bookmarkLoadChapterIndex = -1
        upChapterBookmarks()
        toastOnUi(R.string.page_bookmark_added)
        return true
    }

    /** 当前页是否有整页书签（按页首文字匹配） */
    override fun hasPageBookmarkOnCurrentPage(): Boolean {
        val book = ReadBook.book ?: return false
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex) ?: return false
        val pageHead = page.text.trim().take(40)
        if (pageHead.isBlank()) return false
        return appDb.bookmarkDao.getByBook(book.name, book.author).any {
            it.isPageBookmark && isPageTextMatched(it.bookText, pageHead)
        }
    }

    /** 是否处于菜单/弹层激活状态（此时下拉添加整页书签应无效） */
    override fun isMenuActive(): Boolean {
        return textActionMenu.isShowing || binding.readMenu.canShowMenu
    }

    override fun removePageBookmark() {
        val book = ReadBook.book ?: return
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex) ?: return
        val pageHead = page.text.trim().take(40)
        if (pageHead.isBlank()) return
        val target = appDb.bookmarkDao.getByBook(book.name, book.author).firstOrNull {
            it.isPageBookmark && isPageTextMatched(it.bookText, pageHead)
        } ?: return
        appDb.bookmarkDao.delete(target)
        bookmarkLoadChapterIndex = -1
        upChapterBookmarks()
        toastOnUi(R.string.page_bookmark_removed)
    }

    /** 整页书签匹配：书签记录文本与页首文本公共前缀占比 ≥ 80% 视为命中（与 ContentTextView 一致） */
    private fun isPageTextMatched(bookmarkText: String, pageHead: String): Boolean {
        val bm = bookmarkText.trim()
        if (bm.isEmpty() || pageHead.isEmpty()) return false
        var common = 0
        val max = minOf(bm.length, pageHead.length)
        while (common < max && bm[common] == pageHead[common]) {
            common++
        }
        return common.toFloat() >= max * 0.8f
    }

    /**
     * 整页书签/备注气泡外观设置（颜色、形状、透明度等）变更后立即重注当前章节书签，
     * 让阅读页按新设置刷新，无需等待 onResume 或翻章
     */
    fun reloadPageBookmarkConfig() {
        bookmarkLoadChapterIndex = -1
        upChapterBookmarks()
    }

    override fun changeReplaceRuleState() {
        ReadBook.book?.let {
            it.setUseReplaceRule(!it.getUseReplaceRule())
            if (!it.getUseReplaceRule() && it.getAiChapterPurifyEnabled()) {
                it.setAiChapterPurifyEnabled(false)
                cancelAiChapterPurify()
            }
            ReadBook.saveRead()
            menu?.findItem(R.id.menu_enable_replace)?.isChecked = it.getUseReplaceRule()
            menu?.findItem(R.id.menu_enable_ai_chapter_purify)?.isChecked =
                it.getAiChapterPurifyEnabled()
            viewModel.replaceRuleChanged()
        }
    }

    private fun changeAiChapterPurifyState() {
        val book = ReadBook.book ?: return
        val enabled = !book.getAiChapterPurifyEnabled()
        book.setAiChapterPurifyEnabled(enabled)
        if (enabled) {
            book.setUseReplaceRule(true)
        } else {
            cancelAiChapterPurify()
        }
        ReadBook.saveRead()
        menu?.findItem(R.id.menu_enable_replace)?.isChecked = book.getUseReplaceRule()
        menu?.findItem(R.id.menu_enable_ai_chapter_purify)?.isChecked = enabled
        viewModel.replaceRuleChanged()
    }

    private fun requestAiChapterPurifyAfterRefresh() {
        aiChapterPurifyRefreshChapterIndex = ReadBook.durChapterIndex
    }

    private fun scheduleAiChapterPurify(force: Boolean = false, source: String = "unknown") {
        val book = ReadBook.book ?: return
        if (!book.getUseReplaceRule() || !book.getAiChapterPurifyEnabled()) {
            AppLog.putAi(
                "CHAPTER_PURIFY SCHEDULE_SKIPPED\n" +
                    "source=$source\n" +
                    "force=$force\n" +
                    "chapter=${ReadBook.durChapterIndex + 1}\n" +
                    "reason=aiChapterPurifyDisabled"
            )
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        if (aiChapterPurifyJob?.isActive == true) {
            aiChapterPurifyPendingChapterIndex = chapterIndex
            aiChapterPurifyPendingForce = aiChapterPurifyPendingForce || force
            aiChapterPurifyPendingSource = source
            AppLog.putAi(
                "CHAPTER_PURIFY SCHEDULE_QUEUED\n" +
                    "source=$source\n" +
                    "force=$force\n" +
                    "chapter=${chapterIndex + 1}"
            )
            return
        }
        AppLog.putAi(
            "CHAPTER_PURIFY SCHEDULED\n" +
                "source=$source\n" +
                "force=$force\n" +
                "chapter=${chapterIndex + 1}"
        )
        startAiChapterPurify(book, chapterIndex, force, source)
    }

    private fun startAiChapterPurify(book: Book, chapterIndex: Int, force: Boolean, source: String) {
        aiChapterPurifyJob = lifecycleScope.launch {
            var completed = false
            AppLog.putAi(
                "CHAPTER_PURIFY START\n" +
                    "source=$source\n" +
                    "force=$force\n" +
                    "chapter=${chapterIndex + 1}"
            )
            try {
                withContext(IO) {
                    AiChapterPurifyService.processCachedRange(
                        book = book,
                        startChapterIndex = chapterIndex,
                        force = force,
                        triggerSource = source,
                        onProgress = { progress ->
                            withContext(Main) {
                                showAiChapterPurifyProgress(progress)
                                if (
                                    progress is AiChapterPurifyProgress.ChapterRulesStored &&
                                    progress.chapterIndex == chapterIndex &&
                                    progress.addedRules > 0 &&
                                    ReadBook.book?.bookUrl == book.bookUrl &&
                                    ReadBook.durChapterIndex == chapterIndex &&
                                    book.getUseReplaceRule() &&
                                    book.getAiChapterPurifyEnabled()
                                ) {
                                    viewModel.replaceRuleChanged()
                                }
                            }
                        }
                    )
                }
                completed = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (throwable: Throwable) {
                val message = throwable.message ?: throwable.javaClass.simpleName
                val debugLog = (throwable as? AiChapterPurifyException)?.debugLog
                AppLog.put(
                    buildString {
                        append("AI章节净化失败，书籍《${book.name}》，第 ${chapterIndex + 1} 章\n")
                        append(message)
                        if (!debugLog.isNullOrBlank()) {
                            append("\n").append(debugLog)
                        }
                    },
                    throwable
                )
                toastOnUi(getString(R.string.ai_chapter_purify_failed, message))
            } finally {
                aiChapterPurifyJob = null
                val pendingChapterIndex = aiChapterPurifyPendingChapterIndex
                val pendingForce = aiChapterPurifyPendingForce
                val pendingSource = aiChapterPurifyPendingSource
                aiChapterPurifyPendingChapterIndex = null
                aiChapterPurifyPendingForce = false
                aiChapterPurifyPendingSource = null
                if (!completed || pendingChapterIndex != null) {
                    aiChapterPurifySummarySnackbar?.dismiss()
                    aiChapterPurifySummarySnackbar = null
                    aiChapterPurifyLastStreamSnackbarAt = 0L
                }
                if (pendingChapterIndex != null) {
                    scheduleAiChapterPurify(pendingForce, pendingSource ?: "pending_reschedule")
                }
            }
        }
    }

    private fun cancelAiChapterPurify() {
        aiChapterPurifyJob?.cancel()
        aiChapterPurifyJob = null
        aiChapterPurifySummarySnackbar?.dismiss()
        aiChapterPurifySummarySnackbar = null
        aiChapterPurifyLastStreamSnackbarAt = 0L
        aiChapterPurifyPendingChapterIndex = null
        aiChapterPurifyPendingForce = false
        aiChapterPurifyPendingSource = null
        aiChapterPurifyRefreshChapterIndex = null
    }

    private fun showAiChapterPurifyProgress(progress: AiChapterPurifyProgress) {
        if (!AiChapterPurifyConfig.summaryEnabled) return
        if (progress is AiChapterPurifyProgress.StreamProgress) {
            val now = SystemClock.elapsedRealtime()
            if (now - aiChapterPurifyLastStreamSnackbarAt < 500L) return
            aiChapterPurifyLastStreamSnackbarAt = now
        }
        val message = when (progress) {
            is AiChapterPurifyProgress.RequestAccepted -> getString(
                R.string.ai_chapter_purify_request_accepted,
                progress.chapterIndex + 1,
                progress.chunkIndex,
                progress.totalChunks,
                progress.attempt
            )

            is AiChapterPurifyProgress.ResponseReceived -> getString(
                R.string.ai_chapter_purify_response_received,
                progress.chapterIndex + 1,
                progress.chunkIndex,
                progress.totalChunks
            )

            is AiChapterPurifyProgress.StreamProgress -> {
                val phase = getString(
                    when (progress.progress.phase) {
                        io.legado.app.help.ai.AiStreamProgress.Phase.THINKING ->
                            R.string.ai_chapter_purify_stream_phase_thinking
                        io.legado.app.help.ai.AiStreamProgress.Phase.OUTPUT ->
                            R.string.ai_chapter_purify_stream_phase_output
                        io.legado.app.help.ai.AiStreamProgress.Phase.ACTIVITY ->
                            R.string.ai_chapter_purify_stream_phase_activity
                    }
                )
                val tokenText = if (progress.progress.outputTokensEstimated) {
                    "~${progress.progress.outputTokens}"
                } else {
                    progress.progress.outputTokens.toString()
                }
                getString(
                    R.string.ai_chapter_purify_stream_progress,
                    progress.chapterIndex + 1,
                    progress.chunkIndex,
                    progress.totalChunks,
                    phase,
                    tokenText,
                    progress.progress.tokensPerSecond,
                    progress.progress.elapsedMs / 1_000
                )
            }

            is AiChapterPurifyProgress.ChapterRulesStored -> getString(
                R.string.ai_chapter_purify_rules_ready,
                progress.chapterIndex + 1,
                progress.candidateRules
            )

            is AiChapterPurifyProgress.ReplacementApplied -> getString(
                R.string.ai_chapter_purify_replacement_applied,
                progress.addedRules
            )
        }
        val duration = if (progress is AiChapterPurifyProgress.ReplacementApplied) {
            Snackbar.LENGTH_SHORT
        } else {
            Snackbar.LENGTH_INDEFINITE
        }
        val snackbar = aiChapterPurifySummarySnackbar
            ?: Snackbar.make(binding.root, message, duration).also {
                aiChapterPurifySummarySnackbar = it
            }
        snackbar.setText(message)
        snackbar.duration = duration
        snackbar.show()
    }

    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(300000)
            ReadBook.book?.let {
                ReadBook.saveReadNow()
                AppWebDav.uploadBookProgress(it)
                ensureActive()
                it.update()
                Backup.autoBack(this@ReadBookActivity)
            }
        }
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    override fun finish() {
        if (BaseReadAloudService.isRun
            && !finishReadAloudBackstage
        ) {
            toReadAloudBackstage()
            return
        }
        finishReadAloudBackstage = false
        val book = ReadBook.book ?: return super.finish()
        if (ReadBook.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert || ReadBook.skipAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadBook.book?.removeType(BookType.notShelf)
                    ReadBook.book?.save()
                    SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, ReadBook.bookSource, ReadBook.book)
                    ReadBook.inBookshelf = true
                    setResult(RESULT_OK)
                    callBackBookEnd()
                    super.finish()
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, ReadBook.bookSource, ReadBook.book, ReadBook.curTextChapter?.chapter)
    }

    override fun onDestroy() {
        cancelAiChapterPurify()
        super.onDestroy()
        if (activeActivityRef?.get() === this) {
            activeActivityRef = null
        }
        postEvent(EventBus.READ_BOOK_ACTIVITY_ACTIVE, false)
        textActionMenu.dismiss()
        popupAction.dismiss()
        resetReadAloudPlaybackPresentation(immediate = true)
        binding.readView.onDestroy()
        ReadBook.unregister(this)
        // 退出阅读停止内嵌音频块播放（配图音频不是听书）
        AudioBlockPlayer.stop()
        handler.removeCallbacksAndMessages(null) // 清理Handler消息
        if (!ReadBook.inBookshelf && !isChangingConfigurations) {
            viewModel.removeFromBookshelf(null)
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun observeLiveBus() = binding.run {
        observeEvent<String>(EventBus.TIME_CHANGED) { readView.upTime() }
        observeEvent<Int>(EventBus.BATTERY_CHANGED) { readView.upBattery(it) }
        observeEvent<Boolean>(EventBus.BOOKMARK_CHANGED) {
            bookmarkLoadChapterIndex = -1
            upChapterBookmarks()
            reloadCurrentChapterForBookmark()
        }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                ReadBook.readAloud(!BaseReadAloudService.pause)
            }
        }
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            it.forEach { value ->
                when (value) {
                    0 -> upSystemUiVisibility()
                    1 -> readView.upBg()
                    2 -> readView.upStyle()
                    3 -> readView.upBgAlpha()
                    4 -> readView.upPageSlopSquare()
                    5 -> if (isInitFinish) ReadBook.loadContent(resetPageOffset = false)
                    6 -> readView.upContent(resetPageOffset = false)
                    8 -> ChapterProvider.upStyle()
                    9 -> readView.invalidateTextPage()
                    10 -> ChapterProvider.upLayout()
                    11 -> readView.submitRenderTask()
                    12 -> readView.upPageTouchClick()
                }
            }
        }
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            updateReadAloudPageFloating()
            if (it == Status.STOP) {
                lastReadAloudChapterPos = null
                lastReadAloudChapterIndex = null
                ReadBook.attachReadAloudPage()
                hideReadAloudPagePanel()
            }
            if (it == Status.STOP || it == Status.PAUSE) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                    if (page != null) {
                        page.removePageAloudSpan()
                        readView.upContent(resetPageOffset = false)
                    }
                }
            }
            updateReadAloudPanels()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_PAGE_DETACHED) { detached ->
            if (detached) {
                pageChanged = false
            } else {
                hideReadAloudPagePanel()
            }
            updateReadAloudPanels()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_DIALOG_VISIBILITY) { visible ->
            if (visible) {
                binding.readAloudDialogOutsideTap.visible()
            } else {
                binding.readAloudDialogOutsideTap.gone()
            }
            updateReadAloudPanels()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_FLOATING_VISIBILITY) { visible ->
            if (visible) {
                hideReadAloudPagePanel(immediate = true)
                resetReadAloudPlaybackPresentation(immediate = true)
            } else {
                updateReadAloudPanels()
            }
        }
        observeEventSticky<Bundle>(EventBus.TTS_PROGRESS) { progress ->
            val chapterIndex = progress.getInt("chapterIndex", ReadBook.durChapterIndex)
            val chapterStart = progress.getInt("chapterPos")
            lastReadAloudChapterIndex = chapterIndex
            lastReadAloudChapterPos = chapterStart
            lifecycleScope.launch(IO) {
                if (BaseReadAloudService.isPlay()) {
                    ReadBook.curTextChapter?.let { textChapter ->
                        if (ReadBook.readAloudPageDetached || ReadBook.durChapterIndex != chapterIndex) {
                            return@let
                        }
                        ReadBook.durChapterPos = chapterStart
                        val pageIndex = ReadBook.durPageIndex
                        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                        textChapter.getPage(pageIndex)
                            ?.upPageAloudSpan(aloudSpanStart)
                        upContent()
                    }
                }
            }
        }
        observeEvent<Boolean>(PreferKey.keepLight) {
            upScreenTimeOut()
        }
        observeEvent<Boolean>(PreferKey.textSelectAble) {
            readView.curPage.upSelectAble(it)
        }
        observeEvent<String>(PreferKey.showBrightnessView) {
            readMenu.upBrightnessState()
        }
        observeEvent<String>(PreferKey.readAloudFloatOnDesktop) {
            updateReadAloudPageFloating()
        }
        observeEvent<String>(PreferKey.readAloudHidePlaybackPanel) {
            resetReadAloudPlaybackPresentation()
            updateReadAloudPanels()
        }
        observeEvent<String>(PreferKey.readAloudPlaybackPanelDuration) {
            resetReadAloudPlaybackPresentation()
            updateReadAloudPanels()
        }
        observeEvent<String>(PreferKey.readAloudHidePagePanel) {
            updateReadAloudPanels()
        }
        observeEvent<String>(PreferKey.readAloudPanelOnPageFooter) {
            updateReadAloudPanels()
        }
        observeEvent<List<SearchResult>>(EventBus.SEARCH_RESULT) {
            viewModel.searchResultList = it
        }
        observeEvent<Boolean>(EventBus.UPDATE_READ_ACTION_BAR) {
            readMenu.reset()
        }
        observeEvent<Boolean>(EventBus.UP_SEEK_BAR) {
            readMenu.upSeekBar()
        }
        observeEvent<Boolean>(EventBus.OPEN_READ_ALOUD_DIALOG) {
            showReadAloudDialogFromFloating()
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_CONTENT) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    ReadBook.curTextChapter = null
                    binding.readView.upContent()
                    viewModel.refreshContentDur(it)
                }
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    loadChapterList(it)
                }
            }
        }
    }

    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toInt() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /**
     * 重置黑屏时间
     */
    override fun screenOffTimerStart() {
        handler.post {
            if (screenTimeOut < 0) {
                keepScreenOn(true)
                return@post
            }
            val t = screenTimeOut - sysScreenOffTime
            if (t > 0) {
                keepScreenOn(true)
                handler.removeCallbacks(screenOffRunnable)
                handler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepScreenOn(false)
            }
        }
    }

    companion object {
        const val RESULT_DELETED = 100
        private var activeActivityRef: WeakReference<ReadBookActivity>? = null

        fun activeActivity(): ReadBookActivity? = activeActivityRef?.get()
    }

}
