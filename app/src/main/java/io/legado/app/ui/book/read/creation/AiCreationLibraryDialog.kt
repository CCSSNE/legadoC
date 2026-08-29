package io.legado.app.ui.book.read.creation

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

    private val binding by viewBinding(DialogAiCreationLibraryBinding::bind)
    private val section: String by lazy { requireArguments().getString(ARG_SECTION).orEmpty() }
    private val bookName: String by lazy { requireArguments().getString(ARG_BOOK_NAME).orEmpty() }
    private val selectedIds = linkedSetOf<Long>()
    private var cards: List<CreationCard> = emptyList()
    private val cardAdapter: CardAdapter by lazy { CardAdapter() }

    private val cardEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val deleted = result.data?.getBooleanExtra("creationCardDeleted", false) ?: false
        val cardId = result.data?.getLongExtra("creationCardId", -1L) ?: -1L
        if (deleted && cardId > 0) {
            selectedIds.remove(cardId)
        } else if (cardId > 0) {
            selectedIds.add(cardId)
        }
        refreshCards()
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvSection.text = AiCreationSessionHolder.session.sectionLabel(section)
        binding.ivClose.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvOk.setOnClickListener {
            selectedIds.forEach { cardId ->
                AiCreationSessionHolder.session.addCard(section, cardId)
            }
            dismissAllowingStateLoss()
        }
        binding.btnAddCard.setOnClickListener { createNewCard() }
        binding.etSearch.addTextChangedListener { text ->
            refreshCards(text?.toString().orEmpty())
        }
        binding.rvCards.adapter = cardAdapter
        binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 4)
        refreshCards()
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
            selectedIds.add(cardId)
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
                    if (card.cardId in selectedIds) {
                        selectedIds.remove(card.cardId)
                    } else {
                        selectedIds.add(card.cardId)
                    }
                    notifyItemChanged(holder.layoutPosition)
                }
            }
            holder.itemView.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let { card ->
                    openCardEditor(card.cardId)
                }
                true
            }
        }
    }
}
