package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookmarkStyle
import io.legado.app.databinding.DialogBookmarkBinding
import io.legado.app.utils.gone
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarkDialog() : BaseDialogFragment(R.layout.dialog_bookmark, true) {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    private val binding by viewBinding(DialogBookmarkBinding::bind)
    private var bookmark: Bookmark? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.background = null
        val arguments = arguments ?: let {
            dismiss()
            return
        }

        @Suppress("DEPRECATION")
        val bookmark = arguments.getParcelable<Bookmark>("bookmark")
        bookmark ?: let {
            dismiss()
            return
        }
        this.bookmark = bookmark
        val editPos = arguments.getInt("editPos", -1)
        if (bookmark.isPageBookmark) {
            // 整页书签只保存页面位置，不允许在普通书签编辑器里写备注或效果。
            binding.toolBar.title = getString(R.string.bookmark_page_tag)
            binding.editBookText.isEnabled = false
            binding.editBookText.isFocusable = false
            binding.editBookText.isFocusableInTouchMode = false
            binding.editBookText.isClickable = false
            (binding.editContent.parent as? View)?.gone()
            binding.tvBookmarkStyle.gone()
            binding.effectStylePicker.gone()
        } else {
            binding.effectStylePicker.setFragmentManager(childFragmentManager)
            binding.effectStylePicker.setStyles(
                bookmark.style,
                BookmarkStyle.parseStyleColors(bookmark.styleColors),
                bookmark.color
            )
        }
        binding.tvFooterLeft.visible(editPos >= 0)
        binding.run {
            tvChapterName.text = bookmark.chapterName
            editBookText.setText(bookmark.bookText)
            editContent.setText(bookmark.content)
            tvCancel.setOnClickListener {
                dismiss()
            }
            tvOk.setOnClickListener {
                if (bookmark.isPageBookmark) {
                    // 防止历史数据或批量编辑遗留的普通书签属性污染整页书签。
                    bookmark.content = ""
                    bookmark.style = BookmarkStyle.NONE
                    bookmark.color = 0
                    bookmark.styleColors = ""
                } else {
                    bookmark.bookText = editBookText.text?.toString() ?: ""
                    bookmark.content = editContent.text?.toString() ?: ""
                    bookmark.style = effectStylePicker.getCheckedStyles()
                    bookmark.styleColors = effectStylePicker.getStyleColorsJson()
                }
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookmarkDao.insert(bookmark)
                    }
                    postEvent(EventBus.BOOKMARK_CHANGED, true)
                    dismiss()
                }
            }
            tvFooterLeft.setOnClickListener {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookmarkDao.delete(bookmark)
                    }
                    postEvent(EventBus.BOOKMARK_CHANGED, true)
                    dismiss()
                }
            }
        }
    }

}
