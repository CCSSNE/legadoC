package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Display
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.ActionMenuItemView
import androidx.appcompat.widget.ActionMenuView
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.Theme
import io.legado.app.help.ai.AiCreationImageTaskHolder
import io.legado.app.help.ai.AiCreationFloatingState
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.lib.theme.applyUiMenuTypefaceDeep
import io.legado.app.model.ReadBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.creation.AiCreationDialog
import io.legado.app.ui.book.read.creation.AiCreationFloatingHost
import io.legado.app.ui.widget.TitleBar
import io.legado.app.ui.widget.menu.SurfacePopupMenu
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyMenuScrollIndicators
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fullScreen
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.surfaceOverflowItems
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize
import kotlinx.coroutines.launch

abstract class BaseActivity<VB : ViewBinding>(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true,
    private val showOpenMenuIcon: Boolean = true
) : AppCompatActivity() {

    protected abstract val binding: VB
    private var lastThemeValuesChanged = 0L
    private var surfaceOverflowPopup: SurfacePopupMenu? = null
    /** AI 创作悬浮窗最近一次状态：onResume 重算外显门控用 */
    private var lastAiCreationFloatingState: AiCreationFloatingState? = null

    //AI 创作生成任务悬浮窗：应用内所有 Activity 统一挂载（预览页在前台时由状态统一拦截）
    private val aiCreationFloatingHost by lazy {
        AiCreationFloatingHost(
            container = findViewById<ViewGroup>(android.R.id.content),
            layoutParams = {
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END or Gravity.BOTTOM
                    marginEnd = 16.dpToPx()
                    bottomMargin = 120.dpToPx()
                }
            },
            onOpen = {
                showDialogFragment(
                    AiCreationDialog.newInstance(
                        ReadBook.book?.name.orEmpty(),
                        jumpToPreview = true
                    )
                )
            }
        )
    }

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        val view = super.onCreateView(parent, name, context, attrs)
        if (AppConst.menuViewNames.contains(name)) {
            val menuView = view ?: parent
            menuView?.post {
                menuView.applyUiMenuTypefaceDeep(context)
                menuView.applyMenuScrollIndicators()
            }
        }
        return view
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        initTheme()
        super.onCreate(savedInstanceState)
        applyPreferredRefreshRate()
        setupSystemBar()
        setContentView(binding.root)
        binding.root.applyUiBodyTypeface(this)
        applyRootBackgroundPolicy()
        upBackgroundImage()
        lastThemeValuesChanged = ThemeStore.valuesChanged(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        }
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        observeLiveBus()
        lifecycleScope.launch {
            AiCreationImageTaskHolder.floatingState.collect { state ->
                lastAiCreationFloatingState = state
                applyAiCreationFloating(state)
            }
        }
        onActivityCreated(savedInstanceState)
    }

    /**
     * 回退设置：关闭“AI 创作悬浮窗在阅读界面外显示”后，悬浮窗只在阅读界面显示；
     * AI 创作对话框/预览页由各自宿主独立挂载，不受此开关影响。
     */
    private fun applyAiCreationFloating(state: AiCreationFloatingState) {
        val show = state.shouldShow &&
            (AppConfig.aiCreationFloatingOutsideReader || this is ReadBookActivity)
        aiCreationFloatingHost.update(show, state.taskRunning)
    }

    override fun onResume() {
        super.onResume()
        ExportBookService.clearFinishedNotification()
        applyPreferredRefreshRate()
        refreshThemeBackgroundIfChanged()
        // 开关可能在设置页被修改，回到前台时按最新值重算悬浮窗外显门控
        lastAiCreationFloatingState?.let(::applyAiCreationFloating)
    }

    private fun refreshThemeBackgroundIfChanged() {
        val valuesChanged = ThemeStore.valuesChanged(this)
        if (valuesChanged != lastThemeValuesChanged) {
            lastThemeValuesChanged = valuesChanged
            setupSystemBar()
            applyRootBackgroundPolicy()
            upBackgroundImage()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPreferredRefreshRate()
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindow, fullScreen)
        setupSystemBar()
    }

    abstract fun onActivityCreated(savedInstanceState: Bundle?)

    final override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val bool = onCompatCreateOptionsMenu(menu)
        menu.applyUiMenuStyle(this, toolBarTheme)
        installSurfaceOverflow(menu)
        return bool
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val bool = super.onPrepareOptionsMenu(menu)
        menu.applyUiMenuStyle(this, toolBarTheme)
        installSurfaceOverflow(menu)
        return bool
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.applyOpenTint(this, showOpenMenuIcon)
        return super.onMenuOpened(featureId, menu)
    }

    @SuppressLint("RestrictedApi")
    private fun installSurfaceOverflow(menu: Menu) {
        // 多 Fragment 各自带 TitleBar 时 findViewById 会命中第一个，
        // 必须按 menu 对象精确匹配所属 Toolbar，否则溢出按钮拿到别人的菜单闭包
        val titleBars = buildList {
            collectTitleBars(window.decorView, this)
        }
        val toolbar = titleBars.firstOrNull { it.toolbar.menu === menu }?.toolbar ?: return
        toolbar.post {
            val menuView = toolbar.children
                .filterIsInstance<ActionMenuView>()
                .firstOrNull()
            val overflowButton = menuView?.children?.firstOrNull {
                (it.layoutParams as? ActionMenuView.LayoutParams)?.isOverflowButton == true
            }
            if (overflowButton == null) {
                if (menu.surfaceOverflowItems().isNotEmpty()) {
                    AppLog.put("Surface menu takeover failed: toolbar overflow button not found")
                }
            } else {
                overflowButton.setOnTouchListener(null)
                overflowButton.setOnClickListener {
                    val overflowItems = menu.surfaceOverflowItems()
                    if (overflowItems.isEmpty()) return@setOnClickListener
                    surfaceOverflowPopup?.dismiss()
                    dispatchSurfaceMenuOpened(menu)
                    surfaceOverflowPopup = SurfacePopupMenu(this, toolbar).apply {
                        setOnDismissListener {
                            dispatchSurfaceMenuClosed(menu)
                        }
                        setOnMenuItemClickListener { item ->
                            onCompatOptionsItemSelected(item)
                        }
                        show(menu, overflowItems)
                    }
                }
            }

            // AppCompat still opens action-item submenus through its legacy
            // ListPopupWindow. Route those children through the same owned
            // surface as overflow menus so group/sort menus cannot leak a
            // white platform popup.
            menuView?.children
                ?.filterIsInstance<ActionMenuItemView>()
                ?.forEach { actionView ->
                    val item = actionView.itemData
                    val subMenu = item.subMenu ?: return@forEach
                    if (!item.isVisible || !item.isEnabled) return@forEach
                    actionView.setOnClickListener {
                        surfaceOverflowPopup?.dismiss()
                        dispatchSurfaceMenuOpened(subMenu)
                        surfaceOverflowPopup = SurfacePopupMenu(this, actionView).apply {
                            setOnDismissListener {
                                dispatchSurfaceMenuClosed(subMenu)
                            }
                            setOnMenuItemClickListener { child ->
                                onCompatOptionsItemSelected(child)
                            }
                            show(subMenu)
                        }
                    }
                }
        }
    }

    private fun dispatchSurfaceMenuOpened(menu: Menu) {
        menu.applyOpenTint(this, showOpenMenuIcon)
        onSurfaceMenuOpened(menu)
    }

    private fun dispatchSurfaceMenuClosed(menu: Menu) {
        onSurfaceMenuClosed(menu)
    }

    private fun collectTitleBars(view: View, out: MutableList<TitleBar>) {
        if (view is TitleBar) {
            out.add(view)
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectTitleBars(view.getChildAt(index), out)
            }
        }
    }

    protected open fun onSurfaceMenuOpened(menu: Menu) = Unit

    protected open fun onSurfaceMenuClosed(menu: Menu) = Unit

    open fun onCompatCreateOptionsMenu(menu: Menu) = super.onCreateOptionsMenu(menu)

    final override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            supportFinishAfterTransition()
            return true
        }
        return onCompatOptionsItemSelected(item)
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            Theme.Dark -> {
                setTheme(R.style.AppTheme_Dark)
                applyInitialWindowBackground()
            }

            Theme.Light -> {
                setTheme(R.style.AppTheme_Light)
                applyInitialWindowBackground()
            }

            else -> {
                if (AppConfig.isNightTheme) {
                    setTheme(R.style.AppTheme_Dark)
                } else {
                    setTheme(R.style.AppTheme_Light)
                }
                applyInitialWindowBackground()
            }
        }
    }

    private fun applyWindowBackgroundColor() {
        ViewCompat.setBackgroundTintList(window.decorView, null)
        window.decorView.setBackgroundColor(ThemeConfig.getFallbackBackgroundColor(this))
    }

    private fun applyInitialWindowBackground() {
        if (imageBg && !AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(this)) {
            ViewCompat.setBackgroundTintList(window.decorView, null)
            window.decorView.background = null
        } else {
            applyWindowBackgroundColor()
        }
    }

    private fun applyRootBackgroundPolicy() {
        if (imageBg && !AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(this)) {
            ViewCompat.setBackgroundTintList(binding.root, null)
            binding.root.background = null
        }
    }

    open fun upBackgroundImage() {
        if (!imageBg || AppConfig.isEInkMode) {
            applyWindowBackgroundColor()
            return
        }
        val hasBgImage = ThemeConfig.hasUsableBgImage(this)
        try {
            val drawable = ThemeConfig.getBgImage(this, windowManager.windowSize)
            if (drawable != null) {
                ViewCompat.setBackgroundTintList(window.decorView, null)
                window.decorView.background = drawable
            } else if (hasBgImage) {
                ViewCompat.setBackgroundTintList(window.decorView, null)
                window.decorView.background = null
            } else {
                applyWindowBackgroundColor()
            }
        } catch (_: OutOfMemoryError) {
            if (hasBgImage) {
                ViewCompat.setBackgroundTintList(window.decorView, null)
                window.decorView.background = null
            } else {
                applyWindowBackgroundColor()
            }
            toastOnUi(R.string.background_image_too_large)
        } catch (e: Exception) {
            if (hasBgImage) {
                ViewCompat.setBackgroundTintList(window.decorView, null)
                window.decorView.background = null
            } else {
                applyWindowBackgroundColor()
            }
            AppLog.put(getString(R.string.background_image_load_error, e.localizedMessage), e)
        }
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    @SuppressLint("ObsoleteSdkInt")
    open fun applyPreferredRefreshRate() {
        val layoutParams = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = currentDisplay()
            val targetMode = resolvePreferredDisplayMode(display)
            layoutParams.preferredDisplayModeId = targetMode?.modeId ?: 0
            layoutParams.preferredRefreshRate = when {
                targetMode != null -> targetMode.refreshRate
                AppConfig.useHighRefreshRate -> 0f
                else -> 60f
            }
        } else {
            layoutParams.preferredRefreshRate = if (AppConfig.useHighRefreshRate) 0f else 60f
        }
        window.attributes = layoutParams
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun currentDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun resolvePreferredDisplayMode(display: Display?): Display.Mode? {
        display ?: return null
        val currentMode = display.mode
        val sameResolutionModes = display.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        if (sameResolutionModes.isEmpty()) return null
        return if (AppConfig.useHighRefreshRate) {
            sameResolutionModes.maxByOrNull { it.refreshRate }
        } else {
            sameResolutionModes
                .filter { it.refreshRate <= 61f }
                .maxByOrNull { it.refreshRate }
                ?: sameResolutionModes.minByOrNull { it.refreshRate }
        }
    }

    open fun upNavigationBarColor() {
        val nbColor = ThemeStore.navigationBarColor(this)
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(nbColor, transparent = true)
        } else {
            setNavigationBarColorAuto(ColorUtils.darkenColor(nbColor))
        }
    }

    open fun observeLiveBus() {
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
    }
}
