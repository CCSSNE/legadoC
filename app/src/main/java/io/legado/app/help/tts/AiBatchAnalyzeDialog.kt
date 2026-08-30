package io.legado.app.help.tts

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogAiBatchAnalyzeBinding
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 分镜批量分析：选择章节范围，后台逐章生成 + 收编 + 自动选音，实时显示进度。
 */
class AiBatchAnalyzeDialog : BaseDialogFragment(R.layout.dialog_ai_batch_analyze) {

    companion object {
        fun show(manager: FragmentManager) {
            AiBatchAnalyzeDialog().show(manager, "aiBatchAnalyzeDialog")
        }
    }

    private val binding: DialogAiBatchAnalyzeBinding by lazy {
        DialogAiBatchAnalyzeBinding.bind(requireView())
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val book = ReadBook.book
        if (book == null) {
            toastOnUi(R.string.ai_batch_no_book)
            dismiss()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
            withContext(Dispatchers.Main) {
                val current = ReadBook.curTextChapter?.chapter?.index ?: 0
                binding.etFrom.setText((current + 1).toString())
                binding.etTo.setText((current + 1).toString())
                binding.tvBatchTitle.text =
                    getString(R.string.ai_batch_title, book.name, chapterCount)
            }
        }
        binding.btnStart.setOnClickListener { startAnalyze(book) }
        binding.btnCancel.setOnClickListener {
            if (AiStoryboardBatchAnalyzer.progress.value.running) {
                alert(titleResource = R.string.ai_batch_cancel_title) {
                    setMessage(getString(R.string.ai_batch_cancel_message))
                    positiveButton(R.string.ok) { dialog ->
                        AiStoryboardBatchAnalyzer.cancel()
                        dialog.dismiss()
                    }
                    negativeButton(R.string.cancel) { dialog -> dialog.dismiss() }
                }
            } else {
                dismiss()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            AiStoryboardBatchAnalyzer.progress.collect { progress ->
                renderProgress(progress)
            }
        }
        renderProgress(AiStoryboardBatchAnalyzer.progress.value)
    }

    private fun renderProgress(progress: AiStoryboardBatchAnalyzer.Progress) {
        binding.progressBar.isVisible = progress.running
        binding.tvProgress.isVisible = progress.running || progress.message.isNotBlank()
        binding.btnStart.isEnabled = !progress.running
        binding.etFrom.isEnabled = !progress.running
        binding.etTo.isEnabled = !progress.running
        if (progress.running) {
            binding.progressBar.max = progress.total.coerceAtLeast(1)
            binding.progressBar.progress = progress.completed
            binding.tvProgress.text = getString(
                R.string.ai_batch_progress,
                progress.completed,
                progress.total,
                progress.chapterTitle
            )
        } else if (progress.message.isNotBlank()) {
            binding.tvProgress.text = progress.message
        }
    }

    private fun startAnalyze(book: io.legado.app.data.entities.Book) {
        val from = binding.etFrom.text?.toString()?.toIntOrNull()
        val to = binding.etTo.text?.toString()?.toIntOrNull()
        if (from == null || to == null || from < 1 || to < 1) {
            toastOnUi(R.string.ai_batch_range_invalid)
            return
        }
        if (!AiStoryboardConfig.isConfigured()) {
            toastOnUi(R.string.ai_storyboard_model_unset)
            return
        }
        BookTtsCastingCoordinator.multiRoleUnsupportedReason()?.let { reason ->
            toastOnUi(reason)
            return
        }
        AiStoryboardBatchAnalyzer.start(book, from - 1, to - 1)
    }
}
