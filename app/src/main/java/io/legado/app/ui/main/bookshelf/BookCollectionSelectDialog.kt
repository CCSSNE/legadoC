package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCollectionWithBooks
import io.legado.app.databinding.DialogBookGroupPickerBinding
import io.legado.app.databinding.ItemBookCollectionSelectBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCollectionSelectDialog() : BaseDialogFragment(R.layout.dialog_book_group_picker),
    Toolbar.OnMenuItemClickListener {

    constructor(bookUrls: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putStringArrayList("bookUrls", bookUrls)
        }
    }

    private val binding by viewBinding(DialogBookGroupPickerBinding::bind)
    private val adapter by lazy { CollectionAdapter() }
    private val bookUrls: List<String>
        get() = arguments?.getStringArrayList("bookUrls").orEmpty()

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.82f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = getString(R.string.select_book_collection)
        binding.toolBar.inflateMenu(R.menu.book_group_manage)
        binding.toolBar.menu.applyUiMenuStyle(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvOk.setTextColor(requireContext().accentColor)
        binding.tvOk.gone()
        lifecycleScope.launch {
            appDb.bookCollectionDao.flowCollections().conflate().collect {
                adapter.setItems(it)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        if (item?.itemId == R.id.menu_add) {
            showNewCollectionDialog()
        }
        return true
    }

    private fun showNewCollectionDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.book_collection_name_hint)
            setSingleLine()
        }
        alert(titleResource = R.string.new_book_collection) {
            customView { editText }
            okButton {
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@okButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val collectionId = appDb.bookCollectionDao.createCollection(name)
                    appDb.bookCollectionDao.addBookUrls(collectionId, bookUrls)
                    withContext(Dispatchers.Main) {
                        toastOnUi(R.string.book_collection_added)
                        dismissAllowingStateLoss()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun addToCollection(item: BookCollectionWithBooks) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.addBookUrls(item.collection.collectionId, bookUrls)
            withContext(Dispatchers.Main) {
                toastOnUi(R.string.book_collection_added)
                dismissAllowingStateLoss()
            }
        }
    }

    private inner class CollectionAdapter :
        RecyclerAdapter<BookCollectionWithBooks, ItemBookCollectionSelectBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemBookCollectionSelectBinding {
            return ItemBookCollectionSelectBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookCollectionSelectBinding,
            item: BookCollectionWithBooks,
            payloads: MutableList<Any>
        ) = binding.run {
            tvName.text = item.collection.name
            tvCount.text = context.getString(R.string.book_collection_count, item.books.size)
            listOf(
                coverMosaic.ivCover1,
                coverMosaic.ivCover2,
                coverMosaic.ivCover3,
                coverMosaic.ivCover4
            ).loadCollectionCovers(item.books.take(4), this@BookCollectionSelectDialog, lifecycle)
        }

        override fun registerListener(
            holder: ItemViewHolder,
            binding: ItemBookCollectionSelectBinding
        ) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let(::addToCollection)
            }
        }
    }
}
