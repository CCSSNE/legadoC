package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.showIntegerInputDialog
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout

/** TTS 缓存设置弹窗：命中 key 维度勾选（引擎+章节+文本固定必选）与实时预取段数。 */
class TtsCacheConfigDialog : BasePrefDialogFragment() {
    private val ttsCachePreferTag = "ttsCachePreferTag"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext())
        view.id = R.id.tag2
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(ttsCachePreferTag)
        if (preferenceFragment == null) preferenceFragment = TtsCachePreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, ttsCachePreferTag)
            .commit()
    }

    class TtsCachePreferenceFragment : PreferenceFragment() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_tts_cache)
            upPrefetchCountSummary()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.background = null
            listView.clipToPadding = true
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsCachePrefetchCount -> showPrefetchCountDialog()
            }
            return super.onPreferenceTreeClick(preference)
        }

        private fun showPrefetchCountDialog() {
            showIntegerInputDialog(
                title = R.string.tts_cache_prefetch_count,
                currentValue = AppConfig.ttsCachePrefetchCount,
                validRange = 1..500,
                defaultValue = 5
            ) {
                AppConfig.ttsCachePrefetchCount = it
                upPrefetchCountSummary()
            }
        }

        private fun upPrefetchCountSummary() {
            findPreference<Preference>(PreferKey.ttsCachePrefetchCount)?.summary =
                getString(
                    R.string.tts_cache_prefetch_count_value,
                    AppConfig.ttsCachePrefetchCount
                )
        }
    }
}
