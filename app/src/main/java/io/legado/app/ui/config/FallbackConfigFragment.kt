package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.prefs.EditTextPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor

/**
 * 回退设置：回退激进的新功能行为
 */
class FallbackConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var targetKeyHandled = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_fallback)
        findPreference<EditTextPreference>(PreferKey.aiCreationFloatingAutoCloseSeconds)
            ?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }
        upAiCreationFloatingAutoCloseSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.fallback_setting)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        consumeTargetKey()
    }

    override fun onResume() {
        super.onResume()
        consumeTargetKey()
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.aiCreationFloatingAutoCloseSeconds -> upAiCreationFloatingAutoCloseSummary()
            PreferKey.readAloudFloatingOutsideReader,
            PreferKey.aiCreationFloatingOutsideReader -> postEvent(key, "")
        }
    }

    private fun upAiCreationFloatingAutoCloseSummary() {
        val seconds = AppConfig.aiCreationFloatingAutoCloseSeconds
        findPreference<Preference>(PreferKey.aiCreationFloatingAutoCloseSeconds)?.summary =
            if (seconds == null) {
                getString(R.string.ai_creation_floating_auto_close_off)
            } else {
                getString(R.string.ai_creation_floating_auto_close_current, seconds)
            }
    }

    private fun consumeTargetKey() {
        if (targetKeyHandled) return
        targetKeyHandled = consumeActivityTargetKey()
    }

}
