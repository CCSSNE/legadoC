package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

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
        view.background = requireContext().dialogSurfaceBackground
        view.clipToOutline = true
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readAloudPreferTag)
            .commit()
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.system_tts)
                }
                return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                    ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            initPhoneCallPausePreference()
            initFloatOnDesktopPreference()
            upFloatOnDesktopPreference()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.background = null
            listView.clipToPadding = true
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                "sysTtsConfig" -> IntentHelp.openTTSSetting()
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.readAloudFloatOnDesktop -> {
                    postEvent(PreferKey.readAloudFloatOnDesktop, "")
                }

                PreferKey.readAloudHideFloatingWindow -> {
                    upFloatOnDesktopPreference()
                    postEvent(PreferKey.readAloudHideFloatingWindow, "")
                }

                PreferKey.ignoreAudioFocus -> {
                    Unit
                }
            }
        }

        private fun initFloatOnDesktopPreference() {
            findPreference<SwitchPreference>(PreferKey.readAloudFloatOnDesktop)
                ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as? Boolean ?: return@OnPreferenceChangeListener true
                    if (enabled && !hasOverlayPermission()) {
                        requestOverlayPermission()
                    }
                    true
                }
        }

        private fun upFloatOnDesktopPreference() {
            findPreference<SwitchPreference>(PreferKey.readAloudFloatOnDesktop)?.isEnabled =
                !AppConfig.readAloudHideFloatingWindow
        }

        private fun initPhoneCallPausePreference() {
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)
                ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
                    val enabled = newValue as? Boolean ?: return@OnPreferenceChangeListener true
                    if (!enabled || hasReadPhoneStatePermission()) {
                        return@OnPreferenceChangeListener true
                    }
                    PermissionsCompat.Builder()
                        .addPermissions(Permissions.READ_PHONE_STATE)
                        .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                        .onGranted {
                            AppConfig.pauseReadAloudWhilePhoneCalls = true
                            (preference as? SwitchPreference)?.isChecked = true
                        }
                        .onDenied {
                            AppConfig.pauseReadAloudWhilePhoneCalls = false
                            (preference as? SwitchPreference)?.isChecked = false
                        }
                        .request()
                    false
                }
        }

        private fun hasReadPhoneStatePermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                requireContext(),
                Permissions.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun hasOverlayPermission(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(requireContext())
        }

        private fun requestOverlayPermission() {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
                .rationale(R.string.float_permission_rationale)
                .request()
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }

                else -> {
                    preference?.summary = value
                }
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }
    }
}
