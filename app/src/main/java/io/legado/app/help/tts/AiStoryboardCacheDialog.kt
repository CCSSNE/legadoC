package io.legado.app.help.tts

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogAiStoryboardCacheBinding
import io.legado.app.databinding.ItemAiStoryboardCacheBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分镜缓存管理：按书列出各章缓存（说话人数、角色名单），
 * 支持单章重新生成与删除，全部清空。
 */
class AiStoryboardCacheDialog : BaseDialogFragment(R.layout.dialog_ai_storyboard_cache) {

    companion object {
        fun show(manager: FragmentManager) {
            AiStoryboardCacheDialog().show(manager, "aiStoryboardCacheDialog")
        }
    }

    private val binding: DialogAiStoryboardCacheBinding by lazy {
        DialogAiStoryboardCacheBinding.bind(requireView())
    }

    private val cacheAdapter by lazy { CacheAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerCache.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCache.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerCache.adapter = cacheAdapter
        binding.btnClearCache.setOnClickListener {
            alert(titleResource = R.string.ai_clear_storyboard_cache) {
                setMessage(getString(R.string.ai_clear_storyboard_cache_message))
                positiveButton(R.string.ok) { dialog ->
                    StoryboardCacheStore.clear()
                    loadCaches()
                    dialog.dismiss()
                }
                negativeButton(R.string.cancel) { dialog -> dialog.dismiss() }
            }
        }
        loadCaches()
    }

    private fun loadCaches() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bookName = ReadBook.book?.name
            val all = StoryboardCacheStore.list()
            val caches = if (bookName.isNullOrBlank()) {
                all
            } else {
                all.filter { it.second.bookName == bookName }
            }
            val entries = caches.map { (key, storyboard) ->
                CacheEntry(key, storyboard)
            }
            withContext(Dispatchers.Main) {
                cacheAdapter.setItems(entries)
                binding.tvCacheSummary.text = getString(R.string.ai_storyboard_cache_summary, entries.size)
            }
        }
    }

    private data class CacheEntry(
        val key: String,
        val storyboard: ChapterStoryboard
    )

    private inner class CacheAdapter(context: android.content.Context) :
        RecyclerAdapter<CacheEntry, ItemAiStoryboardCacheBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAiStoryboardCacheBinding {
            return ItemAiStoryboardCacheBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiStoryboardCacheBinding,
            item: CacheEntry,
            payloads: MutableList<Any>
        ) = binding.run {
            tvChapterTitle.text = getString(
                R.string.ai_storyboard_chapter_title,
                item.storyboard.chapterIndex + 1,
                item.storyboard.chapterTitle
            )
            val segments = item.storyboard.allSegments()
            val dialogueCount = segments.count {
                it.type == StoryboardSegmentType.DIALOGUE || it.type == StoryboardSegmentType.THOUGHT
            }
            val speakers = segments
                .mapNotNull { it.speakerName }
                .distinct()
            tvSpeakerCount.text = getString(R.string.ai_storyboard_speaker_count, speakers.size)
            tvChapterDetail.text = getString(
                R.string.ai_storyboard_chapter_detail,
                segments.size - dialogueCount,
                dialogueCount,
                speakers.joinToString("、").take(60)
            )
            btnRegenerate.setOnClickListener { regenerate(item) }
            btnDelete.setOnClickListener { delete(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiStoryboardCacheBinding) =
            Unit
    }

    private fun regenerate(entry: CacheEntry) {
        val book = ReadBook.book ?: return
        if (!AiStoryboardConfig.isConfigured()) {
            toastOnUi(R.string.ai_storyboard_model_unset)
            return
        }
        toastOnUi(R.string.ai_storyboard_regenerating)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                AiStoryboardBatchAnalyzer.analyzeChapter(
                    book, entry.storyboard.chapterIndex, entry.storyboard.chapterTitle, force = true
                )
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    io.legado.app.constant.AppLog.put("AI分镜重新生成失败\n${error.localizedMessage}")
                }
            }
            withContext(Dispatchers.Main) { loadCaches() }
        }
    }

    private fun delete(entry: CacheEntry) {
        StoryboardCacheStore.delete(entry.key)
        loadCaches()
    }
}
