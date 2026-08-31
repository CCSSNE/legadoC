package io.legado.app.ui.book.read.creation

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.CreationCard
import io.legado.app.databinding.DialogAiCreationLibraryBinding
import io.legado.app.databinding.ItemAiCreationCardBinding
import io.legado.app.help.ai.AiCreationConfig
import io.legado.app.help.ai.AiCreationSessionHolder
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiCreationLibraryDialog : BaseDialogFragment(R.layout.dialog_ai_creation_library) {

    companion object {
        const val ARG_SECTION = "section"
        const val ARG_BOOK_NAME = "bookName"

        fun newInstance(section: String, bookName: String): AiCreationLibraryDialog {
            return AiCreationLibraryDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION, section)
                    putString(ARG_BOOK_NAME, bookName)
                }
            }
        }
    }

    /** 「添加」提交后通知组合素材页刷新对应分区 */
    interface OnCardsAddedListener {
        fun onCardsAddedToSection(section: String)
    }

    private val binding by viewBinding(DialogAiCreationLibraryBinding::bind)
    private val section: String by lazy { requireArguments().getString(ARG_SECTION).orEmpty() }
    private val bookName: String by lazy { requireArguments().getString(ARG_BOOK_NAME).orEmpty() }
    private val selectedIds = linkedSetOf<Long>()
    private var selectionMode = false
    private var cards: List<CreationCard> = emptyList()
    private val cardAdapter: CardAdapter by lazy { CardAdapter() }

    private val cardEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        refreshCards()
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvSection.text = AiCreationSessionHolder.session.sectionLabel(section)
        binding.ivClose.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvCancel.setOnClickListener {
            if (selectionMode) exitSelectionMode() else dismissAllowingStateLoss()
        }
        binding.tvOk.setOnClickListener {
            if (selectedIds.isNotEmpty()) {
                selectedIds.forEach { cardId ->
                    AiCreationSessionHolder.session.addCard(section, cardId)
                }
                (parentFragment as? OnCardsAddedListener)?.onCardsAddedToSection(section)
            }
            dismissAllowingStateLoss()
        }
        binding.tvSelectAll.setOnClickListener { selectAllToggle() }
        binding.tvDelete.setOnClickListener { deleteSelected() }
        binding.btnAddCard.setOnClickListener { createNewCard() }
        binding.etSearch.addTextChangedListener { text ->
            refreshCards(text?.toString().orEmpty())
        }
        binding.rvCards.adapter = cardAdapter
        binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 4)
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP &&
                selectionMode
            ) {
                exitSelectionMode()
                true
            } else {
                false
            }
        }
        upChrome()
        refreshCards()
    }

    private fun enterSelectionMode(initialCardId: Long) {
        selectionMode = true
        selectedIds.add(initialCardId)
        cardAdapter.notifyDataSetChanged()
        upChrome()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        cardAdapter.notifyDataSetChanged()
        upChrome()
    }

    private fun toggleSelect(cardId: Long) {
        if (cardId in selectedIds) {
            selectedIds.remove(cardId)
        } else {
            selectedIds.add(cardId)
        }
        cardAdapter.notifyDataSetChanged()
        upChrome()
    }

    /** 全选开关：未选满则全选，已选满则全部取消 */
    private fun selectAllToggle() {
        if (cards.isNotEmpty() && selectedIds.size >= cards.size) {
            selectedIds.clear()
        } else {
            cards.forEach { selectedIds.add(it.cardId) }
        }
        cardAdapter.notifyDataSetChanged()
        upChrome()
    }

    /** 立刻删除所选卡片；会话内各分区的引用、链接组与待链接一并摘除 */
    private fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                ids.forEach { appDb.creationCardDao.deleteById(it) }
            }
            ids.forEach { cardId ->
                AiCreationConfig.sectionOrder.forEach { sec ->
                    AiCreationSessionHolder.session.removeCard(sec, cardId)
                }
            }
            selectedIds.removeAll(ids.toSet())
            refreshCards()
        }
    }

    private fun upChrome() {
        binding.llBottomBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        if (selectionMode) {
            val full = cards.isNotEmpty() && selectedIds.size >= cards.size
            binding.tvSelectAll.text = getString(
                if (full) R.string.select_cancel_count else R.string.select_all_count,
                selectedIds.size,
                cards.size
            )
        }
    }

    private fun refreshCards(keyword: String = binding.etSearch.text?.toString().orEmpty()) {
        viewLifecycleOwner.lifecycleScope.launch {
            cards = withContext(IO) {
                if (keyword.isBlank()) {
                    appDb.creationCardDao.listBySection(section, bookName)
                } else {
                    appDb.creationCardDao.search(section, bookName, keyword.trim())
                }
            }
            cardAdapter.setItems(cards)
            upChrome()
        }
    }

    private fun openCardEditor(cardId: Long) {
        val intent = Intent(requireContext(), CodeEditActivity::class.java).apply {
            putExtra("creationCardId", cardId)
        }
        cardEditLauncher.launch(intent)
    }

    private fun createNewCard() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cardId = withContext(IO) {
                appDb.creationCardDao.insert(
                    CreationCard(
                        section = section,
                        name = AiCreationSessionHolder.session.sectionLabel(section),
                        content = "",
                        bookName = bookName
                    )
                )
            }
            openCardEditor(cardId)
        }
    }

    inner class CardAdapter :
        RecyclerAdapter<CreationCard, ItemAiCreationCardBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemAiCreationCardBinding {
            return ItemAiCreationCardBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiCreationCardBinding,
            item: CreationCard,
            payloads: MutableList<Any>
        ) = with(binding) {
            val selected = item.cardId in selectedIds
            tvName.text = item.name.ifBlank {
                AiCreationSessionHolder.session.sectionLabel(section)
            }
            tvContent.text = item.content.replace('\n', ' ').trim()
            tvCheck.visibility = if (selected) View.VISIBLE else View.GONE
            tvCheck.text = "✓"
            val border = GradientDrawable().apply {
                cornerRadius = 8.dpToPx().toFloat()
                setColor(context.backgroundColor)
                setStroke(
                    (if (selected) 2 else 1).dpToPx(),
                    if (selected) context.accentColor else Color.parseColor("#33808080")
                )
            }
            flRoot.background = border
            tvName.setTextColor(context.primaryTextColor)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiCreationCardBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.let { card ->
                    if (selectionMode) {
                        toggleSelect(card.cardId)
                    } else {
                        openCardEditor(card.cardId)
                    }
                }
            }
            holder.itemView.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let { card ->
                    if (selectionMode) {
                        toggleSelect(card.cardId)
                    } else {
                        enterSelectionMode(card.cardId)
                    }
                }
                true
            }
        }
    }
}
