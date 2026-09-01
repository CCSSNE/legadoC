package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogContentSelectMenuConfigBinding
import io.legado.app.databinding.ItemContentSelectActionBinding
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

class ContentSelectMenuConfigDialog :
    BaseDialogFragment(R.layout.dialog_content_select_menu_config) {

    private val binding by viewBinding(DialogContentSelectMenuConfigBinding::bind)

    private data class ActionRow(val id: String, val labelRes: Int, var checked: Boolean)

    private val adapter = ActionAdapter()
    private lateinit var itemTouchHelper: ItemTouchHelper

    companion object {
        private val knownActions = listOf(
            "replace" to R.string.replace,
            "copy" to R.string.copy_text,
            "web_search" to R.string.search,
            "bookmark" to R.string.bookmark,
            "paragraph_bookmark" to R.string.paragraph_bookmark,
            "aloud" to R.string.read_aloud,
            "dict" to R.string.dict,
            "ask_ai" to R.string.ask_ai,
            "ai_create" to R.string.ai_create,
            "stage" to R.string.stage_text
        )
        private val defaultCheckedIds =
            setOf("replace", "copy", "bookmark", "paragraph_bookmark", "aloud", "ai_create", "stage")
        private val defaultOpenValues = listOf("", "web_search", "dict", "ask_ai")
        private val removedActionIds = setOf("generate_image")
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(Color.TRANSPARENT)
        initData()
        binding.tvCancel.onClick {
            dismissAllowingStateLoss()
        }
        binding.tvOk.onClick {
            saveConfig()
        }
    }

    private fun initData() {
        val checkedIds = requireContext().getPrefStringSet(PreferKey.contentSelectActions, null)
            ?.filterNot { it in removedActionIds }
            ?.toSet()
            ?: defaultCheckedIds
        val storedOrder = requireContext().getPrefString(PreferKey.contentSelectActionsOrder, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val orderedIds = buildList {
            storedOrder.filterTo(this) { id -> knownActions.any { it.first == id } }
            knownActions.forEach { (id, _) ->
                if (id !in this) add(id)
            }
        }
        adapter.items = orderedIds.map { id ->
            ActionRow(id, labelResOf(id), id in checkedIds)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        itemTouchHelper = ItemTouchHelper(
            ItemTouchCallback(adapter).apply {
                isCanDrag = false
            }
        )
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
        val defaultOpen = requireContext().getPrefString(PreferKey.contentSelectDefaultOpen, "").orEmpty()
            .takeIf { it !in removedActionIds }
            .orEmpty()
        val defaultIndex = defaultOpenValues.indexOf(defaultOpen).takeIf { it >= 0 } ?: 0
        binding.rgDefaultOpen.checkByIndex(defaultIndex)
    }

    private fun labelResOf(id: String): Int =
        knownActions.firstOrNull { it.first == id }?.second ?: R.string.replace

    private fun saveConfig() {
        val selected = adapter.items
            .filter { it.checked }
            .map { it.id }
            .toMutableSet()
        val defaultOpen = defaultOpenValues.getOrElse(binding.rgDefaultOpen.getCheckedIndex()) { "" }
        if (defaultOpen.isNotEmpty()) {
            selected += defaultOpen
        }
        if (selected.isEmpty()) {
            selected += "copy"
        }
        requireContext().putPrefStringSet(PreferKey.contentSelectActions, selected)
        requireContext().putPrefString(
            PreferKey.contentSelectActionsOrder,
            adapter.items.joinToString(",") { it.id }
        )
        requireContext().putPrefString(PreferKey.contentSelectDefaultOpen, defaultOpen)
        postEvent(EventBus.CONTENT_SELECT_MENU_CONFIG_CHANGED, true)
        dismissAllowingStateLoss()
    }

    private inner class ActionAdapter :
        RecyclerView.Adapter<ActionAdapter.ActionViewHolder>(), ItemTouchCallback.Callback {

        var items: List<ActionRow> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActionViewHolder {
            val itemBinding = ItemContentSelectActionBinding.inflate(layoutInflater, parent, false)
            return ActionViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ActionViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            if (srcPosition !in items.indices || targetPosition !in items.indices) return false
            items = items.toMutableList().apply {
                add(targetPosition, removeAt(srcPosition))
            }
            notifyItemMoved(srcPosition, targetPosition)
            return true
        }

        inner class ActionViewHolder(
            private val itemBinding: ItemContentSelectActionBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(row: ActionRow) = with(itemBinding) {
                cbAction.text = getString(row.labelRes)
                cbAction.setOnCheckedChangeListener(null)
                cbAction.isChecked = row.checked
                cbAction.setOnCheckedChangeListener { _, checked ->
                    row.checked = checked
                }
                ivDrag.setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(this@ActionViewHolder)
                    }
                    true
                }
            }
        }
    }
}
