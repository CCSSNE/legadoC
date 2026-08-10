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
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor

class MoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "readPreferenceFragment"

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
        SharedPreferences.OnSharedPreferenceChangeListener {

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
            }
            return super.onPreferenceTreeClick(preference)
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
