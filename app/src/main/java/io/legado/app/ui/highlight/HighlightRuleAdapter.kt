package io.legado.app.ui.highlight

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.HighlightRule
import io.legado.app.databinding.ItemHighlightRuleBinding
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.showPopupMenu

class HighlightRuleAdapter(context: Context, var callBack: CallBack) :
    RecyclerAdapter<HighlightRule, ItemHighlightRuleBinding>(context),
    ItemTouchCallback.Callback {

    val diffItemCallBack = object : DiffUtil.ItemCallback<HighlightRule>() {

        override fun areItemsTheSame(oldItem: HighlightRule, newItem: HighlightRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: HighlightRule, newItem: HighlightRule): Boolean {
            return oldItem.name == newItem.name
                && oldItem.pattern == newItem.pattern
                && oldItem.isEnabled == newItem.isEnabled
                && oldItem.style == newItem.style
        }

        override fun getChangePayload(oldItem: HighlightRule, newItem: HighlightRule): Any? {
            val payload = Bundle()
            if (oldItem.name != newItem.name || oldItem.pattern != newItem.pattern) {
                payload.putBoolean("upSummary", true)
            }
            if (oldItem.isEnabled != newItem.isEnabled) {
                payload.putBoolean("enabled", newItem.isEnabled)
            }
            if (payload.isEmpty) {
                return null
            }
            return payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemHighlightRuleBinding {
        return ItemHighlightRuleBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemHighlightRuleBinding,
        item: HighlightRule,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.name
                tvPattern.text = item.pattern
                swtEnabled.isChecked = item.isEnabled
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "upSummary" -> {
                                tvName.text = item.name
                                tvPattern.text = item.pattern
                            }
                            "enabled" -> swtEnabled.isChecked = item.isEnabled
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemHighlightRuleBinding) {
        binding.apply {
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            swtEnabled.setOnUserCheckedChangeListener { isChecked ->
                getItem(holder.layoutPosition)?.let {
                    it.isEnabled = isChecked
                    callBack.update(it)
                }
            }
            ivEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holder.layoutPosition)
            }
        }
    }

    private fun showMenu(view: View, position: Int) {
        val item = getItem(position) ?: return
        view.showPopupMenu(
            R.menu.highlight_rule_item,
        ) { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_top -> callBack.toTop(item)
                R.id.menu_bottom -> callBack.toBottom(item)
                R.id.menu_del -> callBack.delete(item)
            }
            true
        }
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val srcItem = getItem(srcPosition)
        val targetItem = getItem(targetPosition)
        if (srcItem != null && targetItem != null) {
            if (srcItem.order == targetItem.order) {
                callBack.upOrder()
            } else {
                val srcOrder = srcItem.order
                srcItem.order = targetItem.order
                targetItem.order = srcOrder
                movedItems.add(srcItem)
                movedItems.add(targetItem)
            }
        }
        swapItem(srcPosition, targetPosition)
        return true
    }

    private val movedItems = linkedSetOf<HighlightRule>()

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (movedItems.isNotEmpty()) {
            callBack.update(*movedItems.toTypedArray())
            movedItems.clear()
        }
    }

    interface CallBack {
        fun update(vararg rule: HighlightRule)
        fun delete(rule: HighlightRule)
        fun edit(rule: HighlightRule)
        fun toTop(rule: HighlightRule)
        fun toBottom(rule: HighlightRule)
        fun upOrder()
    }
}
