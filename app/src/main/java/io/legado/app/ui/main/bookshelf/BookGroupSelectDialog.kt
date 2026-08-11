package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookGroupSelectBinding
import io.legado.app.databinding.ItemBookGroupSelectBinding
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookGroupSelectDialog() : BaseDialogFragment(R.layout.dialog_book_group_select) {

    constructor(bookUrls: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putStringArrayList("bookUrls", bookUrls)
        }
    }

    private val binding by viewBinding(DialogBookGroupSelectBinding::bind)
    private val adapter by lazy { GroupAdapter() }
    private val bookUrls: List<String>
        get() = arguments?.getStringArrayList("bookUrls").orEmpty()

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
        binding.tvNewGroup.setOnClickListener {
            binding.createGroupPanel.isGone = false
            binding.editGroupName.requestFocus()
        }
        binding.btnCreateGroup.setOnClickListener {
            createAndAddGroup()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            appDb.bookGroupDao.flowSelect()
                .map { groups -> groups.filter { it.groupId > 0 } }
                .conflate()
                .collect {
                    adapter.setItems(it)
                }
        }
    }

    private fun createAndAddGroup() {
        val name = binding.editGroupName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (!appDb.bookGroupDao.canAddGroup) {
                withContext(Dispatchers.Main) {
                    toastOnUi("分组已达上限")
                }
                return@launch
            }
            val groupId = appDb.bookGroupDao.getUnusedId()
            val group = BookGroup(
                groupId = groupId,
                groupName = name,
                order = appDb.bookGroupDao.maxOrder + 1
            )
            appDb.bookGroupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
            appDb.bookGroupDao.insert(group)
            addBookUrlsToGroup(groupId)
        }
    }

    private fun addToGroup(group: BookGroup) {
        lifecycleScope.launch(Dispatchers.IO) {
            addBookUrlsToGroup(group.groupId)
        }
    }

    private suspend fun addBookUrlsToGroup(groupId: Long) {
        val books = bookUrls.mapNotNull { appDb.bookDao.getBook(it) }
        val updatedBooks = books.map { it.copy(group = it.group or groupId) }
        if (updatedBooks.isNotEmpty()) {
            appDb.bookDao.update(*updatedBooks.toTypedArray())
        }
        withContext(Dispatchers.Main) {
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            toastOnUi(R.string.book_group_added)
            dismissAllowingStateLoss()
        }
    }

    private inner class GroupAdapter :
        RecyclerAdapter<BookGroup, ItemBookGroupSelectBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemBookGroupSelectBinding {
            return ItemBookGroupSelectBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookGroupSelectBinding,
            item: BookGroup,
            payloads: MutableList<Any>
        ) = binding.run {
            tvName.text = item.groupName
        }

        override fun registerListener(
            holder: ItemViewHolder,
            binding: ItemBookGroupSelectBinding
        ) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let(::addToGroup)
            }
        }
    }
}
