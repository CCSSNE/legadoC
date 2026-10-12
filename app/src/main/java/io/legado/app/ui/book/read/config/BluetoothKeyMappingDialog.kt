package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBluetoothKeyMappingBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BluetoothKeyMappingDialog : BaseDialogFragment(R.layout.dialog_bluetooth_key_mapping) {

    private val binding by viewBinding(DialogBluetoothKeyMappingBinding::bind)

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
            ),
            ActionOption(BluetoothKeyMapping.ACTION_PREVIOUS_CHAPTER, R.string.previous_chapter),
            ActionOption(BluetoothKeyMapping.ACTION_NEXT_CHAPTER, R.string.next_chapter),
            ActionOption(BluetoothKeyMapping.ACTION_READ_ALOUD_TOGGLE, R.string.read_aloud_pause_resume)
        )
    }

    private var showReading = true

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val actionViews = listOf(
            binding.actionPreviousTrack,
            binding.actionRewind,
            binding.actionNextTrack,
            binding.actionFastForward,
            binding.actionPlayPause,
            binding.actionVolumeUp,
            binding.actionVolumeDown
        )
        val labelViews = listOf(
            binding.labelPreviousTrack,
            binding.labelRewind,
            binding.labelNextTrack,
            binding.labelFastForward,
            binding.labelPlayPause,
            binding.labelVolumeUp,
            binding.labelVolumeDown
        )
        BluetoothKeyMapping.keys.zip(labelViews).forEach { (key, labelView) ->
            labelView.setText(key.label)
        }
        BluetoothKeyMapping.keys.zip(actionViews).forEach { (key, actionView) ->
            actionView.setOnClickListener {
                showActionSelector(actionView, key)
            }
        }
        binding.tvModeRead.setOnClickListener {
            showReading = true
            refreshAll()
        }
        binding.tvModeAloud.setOnClickListener {
            showReading = false
            refreshAll()
        }
        binding.tvClose.setOnClickListener {
            dismissAllowingStateLoss()
        }
        refreshAll()
    }

    private fun refreshAll() {
        val actionViews = listOf(
            binding.actionPreviousTrack,
            binding.actionRewind,
            binding.actionNextTrack,
            binding.actionFastForward,
            binding.actionPlayPause,
            binding.actionVolumeUp,
            binding.actionVolumeDown
        )
        BluetoothKeyMapping.keys.zip(actionViews).forEach { (key, actionView) ->
            updateActionView(actionView, key)
        }
        binding.tvModeRead.setBackgroundResource(
            if (showReading) R.drawable.shape_fillet_btn_press else R.drawable.shape_fillet_btn
        )
        binding.tvModeAloud.setBackgroundResource(
            if (showReading) R.drawable.shape_fillet_btn else R.drawable.shape_fillet_btn_press
        )
    }

    private fun slotKey(key: BluetoothKeyMapping.Key): String {
        return if (showReading) key.preferenceKey else key.playPreferenceKey
    }

    private fun slotDefault(key: BluetoothKeyMapping.Key): Int {
        return if (showReading) key.defaultAction else key.defaultPlayAction
    }

    private fun showActionSelector(actionView: TextView, key: BluetoothKeyMapping.Key) {
        context?.selector(
            R.string.bluetooth_action_select,
            actionOptions.map { getString(it.title) }
        ) { _, index ->
            putPrefInt(slotKey(key), actionOptions[index].value)
            updateActionView(actionView, key)
        }
    }

    private fun updateActionView(actionView: TextView, key: BluetoothKeyMapping.Key) {
        val selected = actionOptions.firstOrNull {
            it.value == getPrefInt(slotKey(key), slotDefault(key))
        } ?: error("Unknown Bluetooth key action for ${slotKey(key)}")
        actionView.text = getString(selected.title)
        actionView.contentDescription = getString(selected.title)
    }
}
