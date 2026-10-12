package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.widget.TextView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityBluetoothKeyMappingBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BluetoothKeyMappingActivity : BaseActivity<ActivityBluetoothKeyMappingBinding>() {

    override val binding by viewBinding(ActivityBluetoothKeyMappingBinding::inflate)

    private data class ActionOption(val value: Int, val title: Int)

    private val actionOptions by lazy {
        listOf(
            ActionOption(BluetoothKeyMapping.ACTION_NONE, R.string.non_action),
            ActionOption(
                BluetoothKeyMapping.ACTION_PAGE_BOOKMARK,
                R.string.bluetooth_action_page_bookmark
            ),
            ActionOption(BluetoothKeyMapping.ACTION_PREVIOUS_PAGE, R.string.prev_page),
            ActionOption(BluetoothKeyMapping.ACTION_NEXT_PAGE, R.string.next_page),
            ActionOption(
                BluetoothKeyMapping.ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH,
                R.string.bluetooth_action_read_aloud_previous_paragraph
            ),
            ActionOption(
                BluetoothKeyMapping.ACTION_READ_ALOUD_NEXT_PARAGRAPH,
                R.string.bluetooth_action_read_aloud_next_paragraph
            )
        )
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.bluetooth_key_mapping)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.cardContent.background = UiCorner.rounded(
            UiCorner.themeSurfaceCardColor(this),
            UiCorner.panelRadius(this)
        )

        val actionViews = listOf(
            binding.actionPreviousTrack,
            binding.actionRewind,
            binding.actionNextTrack,
            binding.actionFastForward,
            binding.actionPlayPause
        )
        val labelViews = listOf(
            binding.labelPreviousTrack,
            binding.labelRewind,
            binding.labelNextTrack,
            binding.labelFastForward,
            binding.labelPlayPause
        )
        BluetoothKeyMapping.keys.zip(actionViews).forEach { (key, actionView) ->
            actionView.background = UiCorner.rounded(
                UiCorner.themeSurfaceCardColor(this),
                UiCorner.actionRadius(this)
            )
            updateActionView(actionView, key.preferenceKey)
            actionView.setOnClickListener {
                showActionSelector(actionView, key.preferenceKey)
            }
        }
        BluetoothKeyMapping.keys.zip(labelViews).forEach { (key, labelView) ->
            labelView.setText(key.label)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showActionSelector(actionView: TextView, preferenceKey: String) {
        selector(
            R.string.bluetooth_action_select,
            actionOptions.map { getString(it.title) }
        ) { _, index ->
            putPrefInt(preferenceKey, actionOptions[index].value)
            updateActionView(actionView, preferenceKey)
        }
    }

    private fun updateActionView(actionView: TextView, preferenceKey: String) {
        val key = BluetoothKeyMapping.keys.firstOrNull {
            it.preferenceKey == preferenceKey
        } ?: error("Unknown Bluetooth key preference: $preferenceKey")
        val selected = actionOptions.firstOrNull {
            it.value == getPrefInt(preferenceKey, key.defaultAction)
        } ?: error("Unknown Bluetooth key action for $preferenceKey")
        actionView.text = if (selected.value == BluetoothKeyMapping.ACTION_NONE) {
            ""
        } else {
            getString(selected.title)
        }
        actionView.contentDescription = if (selected.value == BluetoothKeyMapping.ACTION_NONE) {
            getString(R.string.bluetooth_action_choose)
        } else {
            getString(selected.title)
        }
    }
}
