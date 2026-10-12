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
            )
        )
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val rows = listOf(
            binding.rowPreviousTrack,
            binding.rowRewind,
            binding.rowNextTrack,
            binding.rowFastForward,
            binding.rowPlayPause
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
            updateActionView(actionView, key.preferenceKey)
        }
        BluetoothKeyMapping.keys.zip(labelViews).forEach { (key, labelView) ->
            labelView.setText(key.label)
        }
        BluetoothKeyMapping.keys.zip(rows.zip(actionViews)).forEach { (key, rowAndAction) ->
            val (row, actionView) = rowAndAction
            row.setOnClickListener {
                showActionSelector(actionView, key.preferenceKey)
            }
        }
        binding.tvClose.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    private fun showActionSelector(actionView: TextView, preferenceKey: String) {
        context?.selector(
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
