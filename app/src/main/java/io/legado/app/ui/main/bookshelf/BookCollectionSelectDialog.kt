package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCollectionWithItems
import io.legado.app.databinding.DialogBookCollectionSelectBinding
import io.legado.app.databinding.ItemBookCollectionSelectBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCollectionSelectDialog() : BaseDialogFragment(R.layout.dialog_book_collection_select) {

    constructor(bookUrls: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putStringArrayList("bookUrls", bookUrls)
        }
    }

    constructor(bookUrls: ArrayList<String>, openCreate: Boolean) : this() {
        arguments = Bundle().apply {
            putStringArrayList("bookUrls", bookUrls)
            putBoolean("openCreate", openCreate)
        }
    }

    constructor(
        bookUrls: ArrayList<String>,
        collectionIds: LongArray,
        openCreate: Boolean = false,
        parentCollectionId: Long = 0L
    ) : this() {
        arguments = Bundle().apply {
            putStringArrayList("bookUrls", bookUrls)
            putLongArray("collectionIds", collectionIds)
            putBoolean("openCreate", openCreate)
            putLong("parentCollectionId", parentCollectionId)
        }
    }

    private val binding by viewBinding(DialogBookCollectionSelectBinding::bind)
    private val adapter by lazy { CollectionAdapter() }
    private val bookUrls: List<String>
        get() = arguments?.getStringArrayList("bookUrls").orEmpty()
    private val collectionIds: List<Long>
        get() = arguments?.getLongArray("collectionIds")?.toList().orEmpty()
    private val parentCollectionId: Long
        get() = arguments?.getLong("parentCollectionId") ?: 0L

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val attrs = window.attributes
            attrs.gravity = Gravity.BOTTOM
            attrs.width = WindowManager.LayoutParams.MATCH_PARENT
            window.attributes = attrs
        }
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.58f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundResource(R.drawable.bg_book_collection_sheet)
        binding.btnClose.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvNewCollection.setOnClickListener {
            showNewCollectionDialog()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            appDb.bookCollectionDao.flowCollections().conflate().collect {
                adapter.setItems(it.filter { item ->
                    item.collection.collectionId !in collectionIds
                })
            }
        }
        if (arguments?.getBoolean("openCreate") == true) {
            arguments?.putBoolean("openCreate", false)
            binding.root.post {
                showNewCollectionDialog()
            }
        }
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
                    appDb.bookCollectionDao.addChildCollectionIds(collectionId, collectionIds)
                    if (parentCollectionId > 0) {
                        appDb.bookCollectionDao.addChildCollectionIds(
                            parentCollectionId,
                            listOf(collectionId)
                        )
                    }
                    withContext(Dispatchers.Main) {
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                        toastOnUi(R.string.book_collection_added)
                        dismissAllowingStateLoss()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun addToCollection(item: BookCollectionWithItems) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.addBookUrls(item.collection.collectionId, bookUrls)
            appDb.bookCollectionDao.addChildCollectionIds(
                item.collection.collectionId,
                collectionIds
            )
            withContext(Dispatchers.Main) {
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
                toastOnUi(R.string.book_collection_added)
                dismissAllowingStateLoss()
            }
        }
    }

    private inner class CollectionAdapter :
        RecyclerAdapter<BookCollectionWithItems, ItemBookCollectionSelectBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemBookCollectionSelectBinding {
            return ItemBookCollectionSelectBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookCollectionSelectBinding,
            item: BookCollectionWithItems,
            payloads: MutableList<Any>
        ) = binding.run {
            tvName.text = item.collection.name
            tvCount.text = context.getString(
                R.string.book_collection_count,
                item.books.size + item.childCollections.size
            )
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
