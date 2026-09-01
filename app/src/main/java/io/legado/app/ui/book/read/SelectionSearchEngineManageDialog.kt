package io.legado.app.ui.book.read

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogSelectionSearchEngineManageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

class SelectionSearchEngineManageDialog(
    private val onChanged: (() -> Unit)? = null
) : BaseDialogFragment(R.layout.dialog_selection_search_engine_manage) {

    private val binding by viewBinding(DialogSelectionSearchEngineManageBinding::bind)
    private val adapter by lazy { EngineAdapter() }
    private var engines: MutableList<ContentSelectConfig.SearchEngine> = mutableListOf()

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.72f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        ItemTouchHelper(ItemTouchCallback(adapter).apply {
            isCanDrag = true
        }).attachToRecyclerView(binding.recyclerView)
        engines = ContentSelectConfig.searchEngines(requireContext()).toMutableList()
        renderList()
        binding.tvCancel.onClick { dismissAllowingStateLoss() }
        binding.tvAdd.onClick { showEditDialog(null) }
        binding.tvRestoreDefault.onClick {
            ContentSelectConfig.resetSearchEngines(requireContext())
            engines = ContentSelectConfig.searchEngines(requireContext()).toMutableList()
            renderList()
            onChanged?.invoke()
        }
    }

    private fun renderList() {
        adapter.setItems(engines)
    }

    private inner class EngineAdapter :
        RecyclerView.Adapter<EngineViewHolder>(),
        ItemTouchCallback.Callback {

        private var isMoved = false
        val items: MutableList<ContentSelectConfig.SearchEngine> = mutableListOf()

        fun setItems(newItems: List<ContentSelectConfig.SearchEngine>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EngineViewHolder {
            return EngineViewHolder(parent)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: EngineViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            if (srcPosition !in items.indices || targetPosition !in items.indices) return false
            val item = items.removeAt(srcPosition)
            items.add(targetPosition, item)
            notifyItemMoved(srcPosition, targetPosition)
            isMoved = true
            return true
        }

        override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            if (isMoved) {
                engines = items.toMutableList()
                ContentSelectConfig.saveSearchEngines(requireContext(), engines)
                onChanged?.invoke()
            }
            isMoved = false
        }
    }

    private inner class EngineViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 10.dpToPx())
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dpToPx()
            }
        }
    ) {
        private val rootView = itemView as LinearLayout
        private val titleView = TextView(parent.context).apply {
            textSize = 16f
            typeface = requireContext().uiTypeface()
            maxLines = 1
        }
        private val urlView = TextView(parent.context).apply {
            textSize = 12f
            typeface = requireContext().uiTypeface()
            setTextColor(requireContext().secondaryTextColor)
            setPadding(0, 5.dpToPx(), 0, 6.dpToPx())
        }
        private val cssView = TextView(parent.context).apply {
            text = getString(R.string.selection_search_engine_hide_css_enabled)
            textSize = 12f
            typeface = requireContext().uiTypeface()
            setPadding(0, 0, 0, 6.dpToPx())
        }
        private val editButton = rowButton(getString(R.string.edit))
        private val deleteButton = rowButton(getString(R.string.delete))

        init {
            rootView.addView(titleView)
            rootView.addView(urlView)
            rootView.addView(cssView)
            rootView.addView(LinearLayout(parent.context).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                addView(editButton)
                addView(deleteButton)
            })
        }

        fun bind(engine: ContentSelectConfig.SearchEngine) {
            val palette = ReaderSheetStyle.resolve(requireContext())
            rootView.background = UiCorner.rounded(
                palette.panel,
                UiCorner.panelRadius(requireContext())
            )
            titleView.text = engine.name
            titleView.setTextColor(palette.textColor)
            urlView.text = engine.url
            cssView.isVisible = !engine.hideCss.isNullOrBlank()
            cssView.setTextColor(palette.accentColor)
            editButton.setOnClickListener { showEditDialog(engine) }
            deleteButton.setOnClickListener { confirmDelete(engine) }
        }
    }

    private fun rowButton(text: String): TextView {
        val palette = ReaderSheetStyle.resolve(requireContext())
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            typeface = requireContext().uiTypeface()
            gravity = Gravity.CENTER
            setTextColor(palette.accentColor)
            setPadding(12.dpToPx(), 7.dpToPx(), 12.dpToPx(), 7.dpToPx())
            background = UiCorner.actionSelector(
                palette.panel,
                palette.panelStrong,
                UiCorner.actionRadius(requireContext())
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 8.dpToPx()
            }
        }
    }

    private fun showEditDialog(engine: ContentSelectConfig.SearchEngine?) {
        val nameEdit = EditText(requireContext()).apply {
            hint = getString(R.string.selection_search_engine_name)
            setSingleLine(true)
            setText(engine?.name.orEmpty())
        }
        val urlEdit = EditText(requireContext()).apply {
            hint = getString(R.string.selection_search_engine_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(engine?.url.orEmpty())
        }
        val cssEdit = EditText(requireContext()).apply {
            hint = getString(R.string.selection_search_engine_hide_css_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 8
            setSingleLine(false)
            setHorizontallyScrolling(false)
            setText(engine?.hideCss.orEmpty())
        }
        val input = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 0)
            addView(nameEdit)
            addView(urlEdit)
            addView(cssEdit)
        }
        alert {
            setTitle(if (engine == null) R.string.add else R.string.edit)
            customView { input }
            okButton {
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val url = urlEdit.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || url.isBlank()) {
                    toastOnUi(R.string.cannot_empty)
                    return@okButton
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    toastOnUi(R.string.selection_search_engine_url_error)
                    return@okButton
                }
                val edited = ContentSelectConfig.SearchEngine(
                    id = engine?.id ?: "engine_${System.currentTimeMillis()}",
                    name = name,
                    url = url,
                    hideCss = cssEdit.text?.toString()?.trim().orEmpty()
                )
                val index = engines.indexOfFirst { it.id == edited.id }
                if (index >= 0) {
                    engines[index] = edited
                } else {
                    engines += edited
                }
                saveAndRefresh()
            }
            cancelButton()
        }
    }

    private fun confirmDelete(engine: ContentSelectConfig.SearchEngine) {
        alert {
            setTitle(R.string.delete)
            setMessage(engine.name)
            yesButton {
                engines.removeAll { it.id == engine.id }
                if (engines.isEmpty()) {
                    engines = ContentSelectConfig.defaultSearchEngines.toMutableList()
                }
                saveAndRefresh()
            }
            noButton()
        }
    }

    private fun saveAndRefresh() {
        ContentSelectConfig.saveSearchEngines(requireContext(), engines)
        engines = ContentSelectConfig.searchEngines(requireContext()).toMutableList()
        renderList()
        onChanged?.invoke()
    }
}
