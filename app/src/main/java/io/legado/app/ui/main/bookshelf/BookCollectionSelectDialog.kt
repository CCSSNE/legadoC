package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCollectionWithItems
import io.legado.app.databinding.DialogBookCollectionSelectBinding
import io.legado.app.databinding.ItemBookCollectionSelectBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCollectionSelectDialog() : BaseBottomSheetDialogFragment(R.layout.dialog_book_collection_select) {

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

    private data class CollectionSelectItem(
        val source: BookCollectionWithItems,
        val previewBooks: List<io.legado.app.data.entities.Book>
    )

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
        binding.btnClose.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvNewCollection.setOnClickListener {
            showNewCollectionDialog()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setOnTouchListener { recyclerView, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                (recyclerView as RecyclerView).findChildViewUnder(event.x, event.y) == null
            ) {
                moveToRoot()
                true
            } else {
                false
            }
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                adapter.getItem(position)?.let(::deleteCollection)
            }
        }).attachToRecyclerView(binding.recyclerView)
        lifecycleScope.launch {
            appDb.bookCollectionDao.flowCollections()
                .map { collections ->
                    collections
                        .filter { item -> item.collection.collectionId !in collectionIds }
                        .map { item ->
                            CollectionSelectItem(
                                source = item,
                                previewBooks = appDb.bookCollectionDao.previewBooksInCollection(
                                    item.collection.collectionId,
                                    4
                                )
                            )
                        }
                }
                .flowOn(Dispatchers.IO)
                .conflate()
                .collect(adapter::setItems)
        }
        if (arguments?.getBoolean("openCreate") == true) {
            arguments?.putBoolean("openCreate", false)
            binding.root.post {
                showNewCollectionDialog()
            }
        }
    }

    private fun moveToRoot() {
        if (bookUrls.isEmpty() && collectionIds.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.moveItemsToRoot(bookUrls, collectionIds)
            withContext(Dispatchers.Main) {
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
                dismissAllowingStateLoss()
            }
        }
    }

    private fun deleteCollection(item: CollectionSelectItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.deleteCollectionsAndRelease(
                listOf(item.source.collection.collectionId)
            )
            withContext(Dispatchers.Main) {
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
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

    private fun addToCollection(item: CollectionSelectItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookCollectionDao.addBookUrls(item.source.collection.collectionId, bookUrls)
            appDb.bookCollectionDao.addChildCollectionIds(
                item.source.collection.collectionId,
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
        RecyclerAdapter<CollectionSelectItem, ItemBookCollectionSelectBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemBookCollectionSelectBinding {
            return ItemBookCollectionSelectBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookCollectionSelectBinding,
            item: CollectionSelectItem,
            payloads: MutableList<Any>
        ) = binding.run {
            val source = item.source
            tvName.text = source.collection.name
            tvCount.text = context.getString(
                R.string.book_collection_count,
                source.books.size + source.childCollections.size
            )
            coverCard.setCardBackgroundColor(
                UiCorner.dialogSurfaceColor(
                    ContextCompat.getColor(context, R.color.background_card)
                )
            )
            coverMosaic.loadCollectionCovers(
                item.previewBooks,
                this@BookCollectionSelectDialog,
                lifecycle,
                dialogSurface = true
            )
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
