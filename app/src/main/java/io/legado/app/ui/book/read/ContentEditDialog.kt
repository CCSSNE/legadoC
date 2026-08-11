package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogContentEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 内容编辑
 */
class ContentEditDialog : BaseDialogFragment(R.layout.dialog_content_edit) {

    val binding by viewBinding(DialogContentEditBinding::bind)
    val viewModel by viewModels<ContentEditViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        val book = ReadBook.book
        binding.toolBar.title = if (book?.isLocalTxt == true) {
            book.name
        } else {
            ReadBook.curTextChapter?.title
        }
        initMenu()
        binding.toolBar.setOnClickListener {
            lifecycleScope.launch {
                val book = ReadBook.book ?: return@launch
                val chapter = withContext(IO) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                } ?: return@launch
                editTitle(chapter)
            }
        }
        viewModel.loadStateLiveData.observe(viewLifecycleOwner) {
            if (it) {
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
            }
        }
        viewModel.initContent {
            binding.contentView.setText(it)
            if (ReadBook.book?.isLocalTxt != true) {
                binding.contentView.post {
                    binding.contentView.apply {
                        val lineIndex = layout.getLineForOffset(ReadBook.durChapterPos)
                        val lineHeight = layout.getLineTop(lineIndex)
                        scrollTo(0, lineHeight)
                    }
                }
            }
        }
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.content_edit)
        binding.toolBar.menu.applyUiMenuStyle(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    save()
                    dismiss()
                }
                R.id.menu_reset -> viewModel.initContent(true) { content ->
                    binding.contentView.setText(content)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
                R.id.menu_copy_all -> requireContext()
                    .sendToClip("${binding.toolBar.title}\n${binding.contentView.text}")
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun editTitle(chapter: BookChapter) {
        alert {
            setTitle(R.string.edit)
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            alertBinding.editView.setText(chapter.title)
            setCustomView(alertBinding.root)
            okButton {
                chapter.title = alertBinding.editView.text.toString()
                lifecycleScope.launch {
                    withContext(IO) {
                        chapter.update()
                    }
                    binding.toolBar.title = chapter.getDisplayTitle()
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        save()
    }

    private fun save() {
        val content = binding.contentView.text?.toString() ?: return
        val readActivity = activity as? ReadBookActivity
        Coroutine.async {
            val book = ReadBook.book ?: return@async
            if (book.isLocalTxt) {
                kotlin.runCatching {
                    saveFullText(book, content, readActivity)
                }.onFailure {
                    withContext(Main) {
                        toastOnUi("保存失败\n${it.localizedMessage}")
                    }
                }
            } else {
                val chapter = appDb.bookChapterDao
                    .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@async
                BookHelp.saveText(book, chapter, content)
                ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
            }
        }
    }

    private suspend fun saveFullText(
        book: Book,
        content: String,
        readActivity: ReadBookActivity?
    ) {
        writeFullText(book, content)
        //清空目录规则缓存，编辑后重新做目录匹配
        book.tocUrl = ""
        appDb.bookDao.update(book)
        TextFile.clear()
        withContext(Main) {
            readActivity?.loadChapterList(book)
        }
    }

    private fun writeFullText(book: Book, content: String) {
        val uri = book.bookUrl.toUri()
        val charset = book.fileCharset()
        if (uri.isContentScheme()) {
            val output = requireContext().contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("无法写入文件")
            output.use { it.write(content.toByteArray(charset)) }
        } else {
            File(uri.path!!).writeText(content, charset)
        }
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        var content: String? = null

        fun initContent(reset: Boolean = false, success: (String) -> Unit) {
            execute {
                val book = ReadBook.book ?: return@execute null
                if (book.isLocalTxt) {
                    if (reset) {
                        content = null
                    }
                    return@execute content ?: LocalBook.getBookInputStream(book).use { input ->
                        input.readBytes().toString(book.fileCharset())
                    }
                }
                val chapter = appDb.bookChapterDao
                    .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@execute null
                if (reset) {
                    content = null
                    BookHelp.delContent(book, chapter)
                    if (!book.isLocal) ReadBook.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, book, chapter)
                    }
                }
                return@execute content ?: let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    val content = BookHelp.getContent(book, chapter) ?: return@let null
                    contentProcessor.getContent(book, chapter, content, includeTitle = false)
                        .toString()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                content = it
                success.invoke(it ?: "")
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }

    }

}
