package io.legado.app.ui.book.changesource

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * 关键词校验对话框（对齐 Z·阅读 同名功能）：
 * 输入「校验章节名 / 必含词 / 禁词」，对每个候选书源抓该章正文并判定，
 * 列出通过与不通过的原因。设置不保存，关闭即失效。
 */
class KeywordVerifyDialog : DialogFragment() {

    /** 由宿主（ChangeBookSourceDialog）注入：按书源 URL 取该书源下的 Book 副本。 */
    object BookProvider {
        @Volatile
        var provider: ((String) -> Book?)? = null
    }

    private val results = Collections.synchronizedList(mutableListOf<KeywordVerifier.SourceResult>())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val sources = arguments?.getStringArrayList(ARG_SOURCES).orEmpty().map {
            val parts = it.split(SEP)
            parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
        }
        val initialChapter = arguments?.getString(ARG_CHAPTER).orEmpty()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        fun label(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFFB03A2E.toInt())
            setPadding(0, dp(10), 0, dp(2))
        }

        fun input(hint: String, initial: String): EditText = EditText(context).apply {
            this.hint = hint
            setText(initial)
            setSingleLine(true)
            textSize = 15f
        }

        root.addView(label("校验章节名"))
        val chapterInput = input("如：第一百四十五章 寻人", initialChapter)
        root.addView(chapterInput)

        root.addView(label("必含词"))
        val mustInput = input("多个词用 | 分隔，可留空", "")
        root.addView(mustInput)

        root.addView(label("禁词"))
        val banInput = input("多个词用 | 分隔，可留空", "")
        root.addView(banInput)

        root.addView(TextView(context).apply {
            text = "只显示校验章节正文中不含禁词、且包含全部必含词的书源。多词用 | 分隔。" +
                "设置不保存，关闭窗口后自动失效。"
            textSize = 12f
            setPadding(0, dp(10), 0, dp(8))
        })

        val resultView = TextView(context).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(context).apply {
            addView(resultView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
            )
        }
        root.addView(scroll)

        val dialog = AlertDialog.Builder(context)
            .setTitle("关键词校验")
            .setView(root)
            .setPositiveButton("开始校验", null)
            .setNeutralButton("复制结果", null)
            .setNegativeButton("关闭", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startVerify(
                    sources = sources,
                    chapterName = chapterInput.text?.toString()?.trim().orEmpty(),
                    mustRaw = mustInput.text?.toString().orEmpty(),
                    banRaw = banInput.text?.toString().orEmpty(),
                    resultView = resultView,
                    button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                )
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val text = buildString {
                    results.forEach { r ->
                        append(if (r.passed) "✅ " else "❌ ").append(r.sourceName)
                        append("  ").append(r.reason)
                        r.chapterTitle?.let { append("  [").append(it).append("]") }
                        append("\n")
                    }
                }
                if (text.isBlank()) return@setOnClickListener
                val clipboard = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("keywordVerify", text))
            }
        }
        return dialog
    }

    private fun startVerify(
        sources: List<Pair<String, String>>,
        chapterName: String,
        mustRaw: String,
        banRaw: String,
        resultView: TextView,
        button: Button
    ) {
        if (sources.isEmpty()) {
            resultView.text = "没有可校验的书源"
            return
        }
        val must = KeywordVerifier.parseKeywords(mustRaw)
        val ban = KeywordVerifier.parseKeywords(banRaw)
        results.clear()
        resultView.text = "开始校验 ${sources.size} 个书源…\n"
        button.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val semaphore = Semaphore(4)
            val provider = BookProvider.provider
            var passed = 0
            sources.forEachIndexed { index, (name, url) ->
                val source = withContext(Dispatchers.IO) {
                    runCatching { appDb.bookSourceDao.getBookSource(url) }.getOrNull()
                }
                if (source == null) {
                    results.add(KeywordVerifier.SourceResult(name, url, false, "书源不存在"))
                    return@forEachIndexed
                }
                val book = provider?.invoke(url) ?: Book(
                    bookUrl = "${url}#verify",
                    name = name,
                    origin = url
                )
                val result = KeywordVerifier.verify(
                    bookSource = source,
                    book = book,
                    chapterName = chapterName,
                    must = must,
                    ban = ban,
                    semaphore = semaphore
                )
                results.add(result)
                if (result.passed) passed++
                withContext(Dispatchers.Main) {
                    resultView.append(
                        (if (result.passed) "✅ " else "❌ ") + result.sourceName +
                            "  " + result.reason +
                            (result.chapterTitle?.let { "  [" + it + "]" } ?: "") + "\n"
                    )
                    resultView.append("进度 ${index + 1}/${sources.size}  通过 $passed\n")
                }
            }
            withContext(Dispatchers.Main) {
                resultView.append("\n完成：通过 $passed / 共 ${sources.size}")
                button.isEnabled = true
            }
        }
    }

    companion object {
        private const val ARG_SOURCES = "sources"
        private const val ARG_CHAPTER = "chapter"
        private const val SEP = "\u0001"

        fun show(
            manager: FragmentManager,
            sources: List<Pair<String, String>>,
            chapterTitle: String?,
            bookProvider: ((String) -> Book?)?
        ) {
            BookProvider.provider = bookProvider
            val args = Bundle().apply {
                putStringArrayList(
                    ARG_SOURCES,
                    ArrayList(sources.map { "${it.first}$SEP${it.second}" })
                )
                putString(ARG_CHAPTER, chapterTitle.orEmpty())
            }
            KeywordVerifyDialog().apply { arguments = args }.show(manager, "keywordVerify")
        }
    }
}
