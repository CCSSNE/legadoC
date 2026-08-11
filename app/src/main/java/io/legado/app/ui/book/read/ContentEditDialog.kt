package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.DialogContentEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.gone
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * 内容编辑：本地 TXT 编辑整个文件（分块懒加载），其它格式编辑当前章节
 */
class ContentEditDialog : BaseDialogFragment(R.layout.dialog_content_edit) {

    val binding by viewBinding(DialogContentEditBinding::bind)
    val viewModel by viewModels<ContentEditViewModel>()

    private lateinit var adapter: ContentEditAdapter
    private var chunks: List<String> = emptyList()

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
        adapter = ContentEditAdapter()
        binding.rvContent.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContent.adapter = adapter
        binding.rvContent.itemAnimator = null
        viewModel.loadStateLiveData.observe(viewLifecycleOwner) {
            if (it) {
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
            }
        }
        viewModel.initContent {
            chunks = it
            adapter.submit(chunks)
            scrollToReadPosition()
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
                R.id.menu_reset -> viewModel.initContent(true) { list ->
                    chunks = list
                    adapter.submit(chunks)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                    scrollToReadPosition()
                }
                R.id.menu_copy_all -> requireContext()
                    .sendToClip("${binding.toolBar.title}\n${adapter.getText()}")
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
        val content = adapter.getText()
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

    /**
     * 滚动到当前阅读位置所在的分块
     */
    private fun scrollToReadPosition() {
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        val chapterPos = ReadBook.durChapterPos
        lifecycleScope.launch {
            val target = withContext(IO) {
                if (book.isLocalTxt) {
                    val chapter = appDb.bookChapterDao
                        .getChapter(book.bookUrl, chapterIndex) ?: return@withContext 0
                    byteOffsetToCharIndex(book, chapter.start ?: 0L) + chapterPos
                } else {
                    chapterPos
                }
            }
            scrollToChunk(target)
        }
    }

    /**
     * 把文件字节偏移换算成字符偏移（用于定位当前章节开头）
     */
    private fun byteOffsetToCharIndex(book: Book, target: Long): Int {
        if (target <= 0) return 0
        return LocalBook.getBookInputStream(book).use { input ->
            val max = input.available().coerceAtLeast(0)
            val count = target.coerceAtMost(max.toLong()).toInt()
            if (count <= 0) return@use 0
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(bytes, read, count - read)
                if (n <= 0) break
                read += n
            }
            String(bytes, 0, read, book.fileCharset()).length
        }
    }

    private fun scrollToChunk(target: Int) {
        if (chunks.isEmpty()) return
        var acc = 0
        var chunkIndex = chunks.lastIndex
        var offsetInChunk = chunks.last().length
        for (i in chunks.indices) {
            if (target < acc + chunks[i].length) {
                chunkIndex = i
                offsetInChunk = (target - acc).coerceAtLeast(0)
                break
            }
            acc += chunks[i].length
        }
        binding.rvContent.scrollToPosition(chunkIndex)
        binding.rvContent.post {
            val holder = binding.rvContent.findViewHolderForAdapterPosition(chunkIndex)
            val edit = holder?.itemView as? ThemeEditText
            if (edit != null && offsetInChunk <= (edit.text?.length ?: 0)) {
                edit.setSelection(offsetInChunk)
            }
        }
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        var chunks: List<String>? = null

        fun initContent(reset: Boolean = false, success: (List<String>) -> Unit) {
            execute {
                val book = ReadBook.book ?: return@execute null
                if (book.isLocalTxt) {
                    if (reset) {
                        chunks = null
                    }
                    return@execute chunks ?: LocalBook.getBookInputStream(book).use { input ->
                        input.readBytes().toString(book.fileCharset()).toEditChunks()
                    }
                }
                val chapter = appDb.bookChapterDao
                    .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@execute null
                if (reset) {
                    chunks = null
                    BookHelp.delContent(book, chapter)
                    if (!book.isLocal) ReadBook.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, book, chapter)
                    }
                }
                return@execute chunks ?: let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    val content = BookHelp.getContent(book, chapter) ?: return@let null
                    contentProcessor.getContent(book, chapter, content, includeTitle = false)
                        .toString().toEditChunks()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                chunks = it
                success.invoke(it ?: emptyList())
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }

    }

}

/**
 * 把长文本按块拆分，尽量在换行处断开，避免一次性塞进单个 EditText
 */
private fun String.toEditChunks(chunkSize: Int = 2000): List<String> {
    if (isEmpty()) return listOf("")
    if (length <= chunkSize) return listOf(this)
    val result = arrayListOf<String>()
    var start = 0
    while (start < length) {
        var end = min(start + chunkSize, length)
        if (end < length) {
            val searchStart = (end - 100).coerceAtLeast(start + 1)
            val newline = indexOf('\n', searchStart)
            if (newline != -1 && newline - end <= 100) {
                end = newline + 1
            }
        }
        result.add(substring(start, end))
        start = end
    }
    return result
}
