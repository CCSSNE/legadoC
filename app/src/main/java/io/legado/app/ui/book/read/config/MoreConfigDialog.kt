package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.Preference
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.ColorShape
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.view.ThemeSeekBar
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import splitties.init.appCtx

class MoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "readPreferenceFragment"

    private companion object {
        const val COLOR_DIALOG_BUBBLE_BG = 1
        const val COLOR_DIALOG_BUBBLE_STROKE = 2
        const val COLOR_DIALOG_BUBBLE_ARROW = 3
        const val COLOR_DIALOG_PAGE_BOOKMARK = 4
        const val COLOR_DIALOG_SELECTION_BG = 5
        const val COLOR_DIALOG_SELECTION_HANDLE = 6
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            val sheetHeight = minOf(
                (resources.displayMetrics.heightPixels * 0.68f).toInt(),
                520.dpToPx()
            ).coerceAtLeast(360.dpToPx())
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight)
            (activity as? ReadBookActivity)?.postReadAloudFloatingAvoidanceForView(
                EventBus.FLOATING_AVOID_SOURCE_MORE_CONFIG_DIALOG,
                view
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as ReadBookActivity).bottomDialog++
        return FrameLayout(requireContext()).apply {
            background = ReaderSheetStyle.topSheetDrawable(ReaderSheetStyle.resolve(requireContext()))
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            id = R.id.tag1
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readPreferTag)
            .commit()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
        (activity as? ReadBookActivity)?.clearReadAloudFloatingAvoidance(
            EventBus.FLOATING_AVOID_SOURCE_MORE_CONFIG_DIALOG
        )
    }

    class ReadPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener,
        ColorPickerDialogListener {

        private val slopSquare by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

        @SuppressLint("RestrictedApi")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_read)
            upPreferenceSummary(PreferKey.pageTouchSlop, slopSquare.toString())
            upPreferenceSummary(
                PreferKey.readAloudDoubleTapTimeout,
                AppConfig.readAloudDoubleTapTimeout.toString()
            )
            upPreferenceSummary(PreferKey.pageAnimationSpeed, AppConfig.pageAnimationSpeed.toString())
            upPreferenceSummary(PreferKey.keyPageAnimationSpeed, AppConfig.keyPageAnimationSpeed.toString())
            if (!CanvasRecorderFactory.isSupport) {
                removePref(PreferKey.optimizeRender)
                preferenceScreen.removePreferenceRecursively(PreferKey.optimizeRender)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.background = null
            listView.clipToPadding = true
            listView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            listView.setPadding(0, 12.dpToPx(), 0, 24.dpToPx())
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager
                .sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager
                .sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readBodyToLh -> activity?.recreate()
                PreferKey.hideStatusBar -> {
                    ReadBookConfig.hideStatusBar = getPrefBoolean(PreferKey.hideStatusBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.hideNavigationBar -> {
                    ReadBookConfig.hideNavigationBar = getPrefBoolean(PreferKey.hideNavigationBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.keepLight -> postEvent(key, true)
                PreferKey.textSelectAble -> postEvent(key, getPrefBoolean(key))
                PreferKey.screenOrientation -> {
                    (activity as? ReadBookActivity)?.setOrientation()
                }

                PreferKey.textFullJustify,
                PreferKey.textBottomJustify,
                PreferKey.useZhLayout,
                PreferKey.adaptSpecialStyle-> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                }

                PreferKey.showBrightnessView -> {
                    postEvent(PreferKey.showBrightnessView, "")
                }

                PreferKey.expandTextMenu -> {
                    (activity as? ReadBookActivity)?.textActionMenu?.upMenu()
                }
                PreferKey.contentSelectActions,
                PreferKey.contentSelectDefaultOpen -> {
                    (activity as? ReadBookActivity)?.textActionMenu?.upMenu()
                }

                PreferKey.doublePageHorizontal -> {
                    ChapterProvider.upLayout()
                    ReadBook.loadContent(false)
                }

                PreferKey.showReadTitleAddition,
                PreferKey.readBarStyleFollowPage,

                PreferKey.progressBarBehavior -> {
                    postEvent(EventBus.UP_SEEK_BAR, true)
                }

                PreferKey.noAnimScrollPage -> {
                    ReadBook.callBack?.upPageAnim()
                }

                PreferKey.optimizeRender -> {
                    ChapterProvider.upStyle()
                    ReadBook.callBack?.upPageAnim(true)
                    ReadBook.loadContent(false)
                }

                PreferKey.paddingDisplayCutouts -> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }

                // 整页书签/备注气泡外观设置变更：立即重注书签刷新绘制，
                // 底部弹窗内修改不会触发 onResume，不刷新就看不到新颜色
                PreferKey.pageBookmarkColor,
                PreferKey.pageBookmarkStyle,
                PreferKey.bookmarkNoteBubbleColor,
                PreferKey.bookmarkNoteBubbleBgAlpha,
                PreferKey.bookmarkNoteBubbleStrokeColor,
                PreferKey.bookmarkNoteBubbleStrokeAlpha,
                PreferKey.bookmarkNoteBubbleArrowColor,
                PreferKey.bookmarkNoteBubbleArrowAlpha -> {
                    (activity as? ReadBookActivity)?.reloadPageBookmarkConfig()
                }
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "customPageKey" -> PageKeyDialog(requireContext()).show()
                "clickRegionalConfig" -> {
                    (activity as? ReadBookActivity)?.showClickRegionalConfig()
                }
                PreferKey.contentSelectMenuConfig -> {
                    ContentSelectMenuConfigDialog().show(parentFragmentManager, "contentSelectMenuConfig")
                }
                PreferKey.pageTouchSlop -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_slop_dialog_title))
                        .setMaxValue(9999)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchSlop)
                        .show {
                            AppConfig.pageTouchSlop = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(4))
                        }
                }

                PreferKey.pageTouchClick -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_click_dialog_title))
                        .setMaxValue(399)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchClick)
                        .show {
                            AppConfig.pageTouchClick = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(12))
                        }
                }

                PreferKey.readAloudDoubleTapTimeout -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.read_aloud_double_tap_timeout_dialog_title))
                        .setMaxValue(600)
                        .setMinValue(120)
                        .setValue(AppConfig.readAloudDoubleTapTimeout)
                        .setCustomButton(R.string.btn_default_s) {
                            AppConfig.readAloudDoubleTapTimeout = 200
                            upPreferenceSummary(
                                PreferKey.readAloudDoubleTapTimeout,
                                AppConfig.readAloudDoubleTapTimeout.toString()
                            )
                        }
                        .show {
                            AppConfig.readAloudDoubleTapTimeout = it
                            upPreferenceSummary(
                                PreferKey.readAloudDoubleTapTimeout,
                                AppConfig.readAloudDoubleTapTimeout.toString()
                            )
                        }
                }

                PreferKey.pageAnimationSpeed -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_animation_speed_dialog_title))
                        .setMaxValue(2000)
                        .setMinValue(0)
                        .setValue(AppConfig.pageAnimationSpeed)
                        .setCustomButton(R.string.btn_default_s) {
                            AppConfig.pageAnimationSpeed = 300
                            upPreferenceSummary(
                                PreferKey.pageAnimationSpeed,
                                AppConfig.pageAnimationSpeed.toString()
                            )
                        }
                        .show {
                            AppConfig.pageAnimationSpeed = it
                            upPreferenceSummary(
                                PreferKey.pageAnimationSpeed,
                                AppConfig.pageAnimationSpeed.toString()
                            )
                        }
                }

                PreferKey.keyPageAnimationSpeed -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.key_page_animation_speed_dialog_title))
                        .setMaxValue(2000)
                        .setMinValue(0)
                        .setValue(AppConfig.keyPageAnimationSpeed)
                        .setCustomButton(R.string.btn_default_s) {
                            AppConfig.keyPageAnimationSpeed = 100
                            upPreferenceSummary(
                                PreferKey.keyPageAnimationSpeed,
                                AppConfig.keyPageAnimationSpeed.toString()
                            )
                        }
                        .show {
                            AppConfig.keyPageAnimationSpeed = it
                            upPreferenceSummary(
                                PreferKey.keyPageAnimationSpeed,
                                AppConfig.keyPageAnimationSpeed.toString()
                            )
                        }
                }

                PreferKey.bookmarkNoteBubbleBgAlpha -> showBubbleBgAlphaDialog()
                PreferKey.bookmarkNoteBubbleColor -> showBubbleColorDialog()
                PreferKey.bookmarkNoteBubbleStroke -> showBubbleStrokeDialog()
                PreferKey.bookmarkNoteBubbleArrow -> showBubbleArrowDialog()
                PreferKey.pageBookmarkColor -> showPageBookmarkColorDialog()
                PreferKey.pageBookmarkStyle -> showPageBookmarkStyleDialog()
                PreferKey.selectionBgColor -> showSelectionColorDialog(
                    title = R.string.selection_bg_color,
                    prefKey = PreferKey.selectionBgColor,
                    dialogId = COLOR_DIALOG_SELECTION_BG
                )
                PreferKey.selectionHandleColor -> showSelectionColorDialog(
                    title = R.string.selection_handle_color,
                    prefKey = PreferKey.selectionHandleColor,
                    dialogId = COLOR_DIALOG_SELECTION_HANDLE
                )
                PreferKey.selectionHandleStyle -> showSelectionHandleStyleDialog()
            }
            return super.onPreferenceTreeClick(preference)
        }

        /**
         * 备注气泡背景透明度：弹窗内滑动选择百分比（与项目其他百分比设置一致）
         */
        private fun showBubbleBgAlphaDialog() {
            val current = requireContext()
                .getPrefInt(PreferKey.bookmarkNoteBubbleBgAlpha, 80)
                .coerceIn(0, 100)
            val valueView = TextView(requireContext()).apply {
                text = "$current%"
                textSize = 14f
                setTextColor(requireContext().getCompatColor(R.color.primaryText))
            }
            val seekBar = ThemeSeekBar(requireContext(), null).apply {
                max = 100
                progress = current
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        valueView.text = "$progress%"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    }
                })
            }
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val pad = 16.dpToPx()
                setPadding(pad, pad, pad, pad)
                addView(
                    valueView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    seekBar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            alert(getString(R.string.bookmark_note_bubble_bg_alpha)) {
                customView { container }
                okButton {
                    requireContext().putPrefInt(
                        PreferKey.bookmarkNoteBubbleBgAlpha,
                        seekBar.progress
                    )
                    // 返回阅读页时 onResume 会重新注入书签数据，气泡按新透明度刷新
                }
                noButton()
            }
        }

        /**
         * 备注气泡颜色：弹窗内选择颜色；"默认颜色"表示自动取背景主色
         */
        private fun showBubbleColorDialog() {
            val current = requireContext().getPrefInt(PreferKey.bookmarkNoteBubbleColor, 0)
            val dialog = ColorPreference.ColorPickerDialogCompat.newBuilder()
                .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                .setDialogTitle(R.string.bookmark_note_bubble_color)
                .setColorShape(ColorShape.CIRCLE)
                .setPresets(ColorPickerDialog.MATERIAL_COLORS)
                .setAllowPresets(true)
                .setAllowCustom(true)
                .setShowAlphaSlider(false)
                .setShowColorShades(true)
                .setShowDefaultColorButton(true)
                .setColor(if (current != 0) current else appCtx.accentColor)
                .setDialogId(COLOR_DIALOG_BUBBLE_BG)
                .create()
            dialog.setColorPickerDialogListener(this)
            dialog.show(parentFragmentManager, "bookmark_bubble_color_picker")
        }

        /**
         * 气泡框线：弹窗内设置是否显示、透明度与颜色
         */
        private fun showBubbleStrokeDialog() {
            showBubbleLineDialog(
                title = R.string.bookmark_note_bubble_stroke,
                showPrefKey = PreferKey.bookmarkNoteBubbleStrokeShow,
                alphaPrefKey = PreferKey.bookmarkNoteBubbleStrokeAlpha,
                colorPrefKey = PreferKey.bookmarkNoteBubbleStrokeColor,
                showTitle = R.string.bookmark_note_bubble_stroke_show,
                alphaTitle = R.string.bookmark_note_bubble_stroke_alpha,
                colorTitle = R.string.bookmark_note_bubble_stroke_color,
                dialogId = COLOR_DIALOG_BUBBLE_STROKE
            )
        }

        /**
         * 气泡箭头：弹窗内设置是否显示、透明度与颜色
         */
        private fun showBubbleArrowDialog() {
            showBubbleLineDialog(
                title = R.string.bookmark_note_bubble_arrow,
                showPrefKey = PreferKey.bookmarkNoteBubbleArrowShow,
                alphaPrefKey = PreferKey.bookmarkNoteBubbleArrowAlpha,
                colorPrefKey = PreferKey.bookmarkNoteBubbleArrowColor,
                showTitle = R.string.bookmark_note_bubble_arrow_show,
                alphaTitle = R.string.bookmark_note_bubble_arrow_alpha,
                colorTitle = R.string.bookmark_note_bubble_arrow_color,
                dialogId = COLOR_DIALOG_BUBBLE_ARROW
            )
        }

        /**
         * 气泡框线/箭头通用设置弹窗：是否显示开关 + 透明度滑杆 + 颜色选择行
         */
        private fun showBubbleLineDialog(
            title: Int,
            showPrefKey: String,
            alphaPrefKey: String,
            colorPrefKey: String,
            showTitle: Int,
            alphaTitle: Int,
            colorTitle: Int,
            dialogId: Int
        ) {
            val context = requireContext()
            val showSwitch = androidx.appcompat.widget.SwitchCompat(context)
            showSwitch.isChecked = context.getPrefBoolean(showPrefKey, true)
            val alphaValue = TextView(context).apply {
                val current = context.getPrefInt(alphaPrefKey, 80).coerceIn(0, 100)
                text = "$current%"
                textSize = 14f
                setTextColor(context.getCompatColor(R.color.primaryText))
            }
            val alphaSeekBar = ThemeSeekBar(context, null).apply {
                max = 100
                progress = context.getPrefInt(alphaPrefKey, 80).coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        alphaValue.text = "$progress%"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    }
                })
            }
            val currentColor = context.getPrefInt(colorPrefKey, 0)
            val colorRow = TextView(context).apply {
                text = if (currentColor != 0) {
                    "#%06X".format(0xFFFFFF and currentColor)
                } else {
                    context.getString(R.string.bookmark_note_bubble_color_default)
                }
                textSize = 14f
                setTextColor(context.getCompatColor(R.color.primaryText))
                setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
                setOnClickListener {
                    val dialog = ColorPreference.ColorPickerDialogCompat.newBuilder()
                        .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                        .setDialogTitle(colorTitle)
                        .setColorShape(ColorShape.CIRCLE)
                        .setPresets(ColorPickerDialog.MATERIAL_COLORS)
                        .setAllowPresets(true)
                        .setAllowCustom(true)
                        .setShowAlphaSlider(true)
                        .setShowColorShades(true)
                        .setShowDefaultColorButton(true)
                        .setColor(if (currentColor != 0) currentColor else appCtx.accentColor)
                        .setDialogId(dialogId)
                        .create()
                    dialog.setColorPickerDialogListener(this@ReadPreferenceFragment)
                    dialog.show(parentFragmentManager, "bookmark_bubble_line_color_picker")
                }
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = 16.dpToPx()
                setPadding(pad, pad, pad, pad)
                addView(
                    TextView(context).apply {
                        text = context.getString(showTitle)
                        textSize = 14f
                        setTextColor(context.getCompatColor(R.color.primaryText))
                    }
                )
                addView(showSwitch)
                addView(
                    TextView(context).apply {
                        text = context.getString(alphaTitle)
                        textSize = 14f
                        setTextColor(context.getCompatColor(R.color.primaryText))
                    }
                )
                addView(alphaValue)
                addView(alphaSeekBar)
                addView(
                    TextView(context).apply {
                        text = context.getString(colorTitle)
                        textSize = 14f
                        setTextColor(context.getCompatColor(R.color.primaryText))
                    }
                )
                addView(colorRow)
            }
            alert(getString(title)) {
                customView { container }
                okButton {
                    context.putPrefBoolean(showPrefKey, showSwitch.isChecked)
                    context.putPrefInt(alphaPrefKey, alphaSeekBar.progress)
                    // 返回阅读页时 onResume 会重新注入书签数据，气泡按新设置刷新
                }
                noButton()
            }
        }

        /**
         * 整页书签标签颜色：弹窗内选择颜色；"默认颜色"表示自动取主题强调色
         */
        private fun showPageBookmarkColorDialog() {
            val current = requireContext().getPrefInt(PreferKey.pageBookmarkColor, 0)
            val dialog = ColorPreference.ColorPickerDialogCompat.newBuilder()
                .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                .setDialogTitle(R.string.page_bookmark_color)
                .setColorShape(ColorShape.CIRCLE)
                .setPresets(ColorPickerDialog.MATERIAL_COLORS)
                .setAllowPresets(true)
                .setAllowCustom(true)
                .setShowAlphaSlider(false)
                .setShowColorShades(true)
                .setShowDefaultColorButton(true)
                .setColor(if (current != 0) current else appCtx.accentColor)
                .setDialogId(COLOR_DIALOG_PAGE_BOOKMARK)
                .create()
            dialog.setColorPickerDialogListener(this)
            dialog.show(parentFragmentManager, "page_bookmark_color_picker")
        }

        /**
         * 整页书签标签样式：尖角朝下，或底部凹口朝上
         */
        private fun showPageBookmarkStyleDialog() {
            val current = requireContext()
                .getPrefInt(PreferKey.pageBookmarkStyle, 1)
                .coerceIn(0, 1)
            alert(getString(R.string.page_bookmark_style)) {
                singleChoiceItems(
                    arrayOf(
                        getString(R.string.page_bookmark_style_pointed),
                        getString(R.string.page_bookmark_style_notched)
                    ),
                    checkedItem = current
                ) { dialog, index ->
                    requireContext().putPrefInt(PreferKey.pageBookmarkStyle, index)
                    dialog.dismiss()
                }
            }
        }

        /**
         * 选区颜色/选区手柄颜色：弹窗内选择颜色，支持调整透明度；"默认颜色"表示使用内置默认色
         */
        private fun showSelectionColorDialog(title: Int, prefKey: String, dialogId: Int) {
            val current = requireContext().getPrefInt(prefKey, 0)
            val dialog = ColorPreference.ColorPickerDialogCompat.newBuilder()
                .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                .setDialogTitle(title)
                .setColorShape(ColorShape.CIRCLE)
                .setPresets(ColorPickerDialog.MATERIAL_COLORS)
                .setAllowPresets(true)
                .setAllowCustom(true)
                .setShowAlphaSlider(true)
                .setShowColorShades(true)
                .setShowDefaultColorButton(true)
                .setColor(if (current != 0) current else appCtx.accentColor)
                .setDialogId(dialogId)
                .create()
            dialog.setColorPickerDialogListener(this)
            dialog.show(parentFragmentManager, "selection_color_picker")
        }

        /**
         * 选区手柄样式：圆球加杆 / 仅圆球 / 无手柄，三选一
         */
        private fun showSelectionHandleStyleDialog() {
            alert(getString(R.string.selection_handle_style)) {
                items(
                    listOf(
                        getString(R.string.selection_handle_style_ball_stem),
                        getString(R.string.selection_handle_style_ball),
                        getString(R.string.selection_handle_style_none)
                    ),
                    onItemSelected = { _, index ->
                        requireContext().putPrefInt(PreferKey.selectionHandleStyle, index)
                    }
                )
            }
        }

        override fun onColorSelected(dialogId: Int, color: Int) {
            when (dialogId) {
                COLOR_DIALOG_BUBBLE_BG -> {
                    val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
                        0
                    } else {
                        color
                    }
                    requireContext().putPrefInt(PreferKey.bookmarkNoteBubbleColor, value)
                }
                COLOR_DIALOG_BUBBLE_STROKE -> {
                    val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
                        0
                    } else {
                        color
                    }
                    requireContext().putPrefInt(PreferKey.bookmarkNoteBubbleStrokeColor, value)
                }
                COLOR_DIALOG_BUBBLE_ARROW -> {
                    val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
                        0
                    } else {
                        color
                    }
                    requireContext().putPrefInt(PreferKey.bookmarkNoteBubbleArrowColor, value)
                }
                COLOR_DIALOG_PAGE_BOOKMARK -> {
                    val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
                        0
                    } else {
                        color
                    }
                    requireContext().putPrefInt(PreferKey.pageBookmarkColor, value)
                }
                COLOR_DIALOG_SELECTION_BG -> {
                    val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
                        0
                    } else {
                        color
                    }
                    requireContext().putPrefInt(PreferKey.selectionBgColor, value)
                    // 重绘当前页，选区背景色立即生效
                    postEvent(EventBus.UP_CONFIG, arrayListOf(9))
                }
                COLOR_DIALOG_SELECTION_HANDLE -> {
                    val value = if (color == ColorPreference.ColorPickerDialogCompat.DEFAULT_COLOR) {
                        0
                    } else {
                        color
                    }
                    requireContext().putPrefInt(PreferKey.selectionHandleColor, value)
                }
            }
            // onSharedPreferenceChanged 已监听相关键：颜色保存后立即重注书签刷新绘制
        }

        override fun onDialogDismissed(dialogId: Int) {
        }

        @Suppress("SameParameterValue")
        private fun upPreferenceSummary(preferenceKey: String, value: String?) {
            val preference = findPreference<Preference>(preferenceKey) ?: return
            when (preferenceKey) {
                PreferKey.pageTouchSlop -> preference.summary =
                    getString(R.string.page_touch_slop_summary, value)
                PreferKey.readAloudDoubleTapTimeout -> preference.summary =
                    getString(R.string.read_aloud_double_tap_timeout_value, value)
                PreferKey.pageAnimationSpeed -> preference.summary =
                    getString(R.string.page_animation_speed_value, value)
                PreferKey.keyPageAnimationSpeed -> preference.summary =
                    getString(R.string.page_animation_speed_value, value)
            }
        }

    }
}
