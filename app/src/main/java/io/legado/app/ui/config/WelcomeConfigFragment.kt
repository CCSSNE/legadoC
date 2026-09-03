package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import io.legado.app.R
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setEdgeEffectColor

class WelcomeConfigFragment : PreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_welcome)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.welcome_style)
        listView.setEdgeEffectColor(primaryColor)
    }

}
