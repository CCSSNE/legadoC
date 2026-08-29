package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.constant.LogModule
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.ReadBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 分镜批量分析：选定章节范围逐章生成（1号AI）+ 收编角色 + 自动选音（2号AI）。
 * 已有缓存的章节自动跳过（秒回），全程后台串行，不阻塞播放。
 */
object AiStoryboardBatchAnalyzer {

    data class Progress(
        val running: Boolean = false,
        val currentChapterIndex: Int = -1,
        val chapterTitle: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private var job: Job? = null

    fun start(book: Book, fromIndex: Int, toIndex: Int) {
        if (_progress.value.running) return
        val from = minOf(fromIndex, toIndex).coerceAtLeast(0)
        val to = maxOf(fromIndex, toIndex)
        job = scope.launch {
            _progress.value = Progress(running = true, total = to - from + 1)
            val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
            val automation = BookTtsAutomationConfig.get(workKey)
            var completed = 0
            var failed = 0
            try {
                for (index in from..to) {
                    if (!currentCoroutineContext().isActive) break
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
                    if (chapter == null) {
                        completed++
                        continue
                    }
                    _progress.value = _progress.value.copy(
                        currentChapterIndex = index,
                        chapterTitle = chapter.title,
                        completed = completed
                    )
                    try {
                        analyzeChapter(book, index, chapter.title)
                        if (automation.autoAssignVoices) {
                            runCatching { BookTtsCastingCoordinator.assignMissingVoices(workKey) }
                        }
                    } catch (e: Exception) {
                        failed++
                        AppLog.put("[AI分镜] 批量分析第 ${index + 1} 章失败\n${e.localizedMessage}", module = LogModule.AI_CAST)
                    }
                    completed++
                    _progress.value = _progress.value.copy(completed = completed)
                }
            } finally {
                _progress.value = _progress.value.copy(
                    running = false,
                    message = if (failed > 0) "完成 $completed 章，失败 $failed 章" else "分析完成"
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        _progress.value = _progress.value.copy(running = false, message = "已取消")
    }

    /**
     * 单章分析：离线构建 TextChapter（pageSplit=false），正文与朗读段落同源。
     * force=true 时忽略已有缓存重新生成。
     */
    suspend fun analyzeChapter(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        force: Boolean = false
    ): ChapterStoryboard = withContext(Dispatchers.IO) {
        val textChapter = ReadBook.loadTextChapterForReadAloud(chapterIndex, scope)
            ?: throw IllegalStateException("章节内容未就绪：${chapterTitle}")
        val content = textChapter.getNeedReadAloud(0, false, 0)
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("章节正文为空：${chapterTitle}")
        val storyboard = if (force) {
            AiTtsStoryboardHelper.regenerate(book, chapterIndex, chapterTitle, content)
        } else {
            AiTtsStoryboardHelper.getOrGenerate(book, chapterIndex, chapterTitle, content)
        }
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        BookTtsCastingCoordinator.syncCastRoles(workKey, chapterIndex, storyboard)
    }
}
