package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterPurifyRecord
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

data class AiChapterPurifyRunResult(
    val requestedChapters: Int,
    val inspectedChapters: Int,
    val skippedCompleted: Int,
    val skippedUncached: Int,
    val addedRules: Int
)

object AiChapterPurifyService {

    private const val RULE_GROUP = "AI净化"

    suspend fun processCachedRange(
        book: Book,
        startChapterIndex: Int,
        chapterCount: Int = AiChapterPurifyConfig.chapterCount,
        force: Boolean = false
    ): AiChapterPurifyRunResult {
        require(chapterCount >= AiChapterPurifyConfig.MIN_CHAPTER_COUNT) {
            "AI chapter purification chapter count must be positive"
        }
        val chapters = appDb.bookChapterDao.getChapterList(
            book.bookUrl,
            startChapterIndex,
            startChapterIndex + chapterCount - 1
        )
        var inspected = 0
        var skippedCompleted = 0
        var skippedUncached = 0
        var addedRules = 0
        chapters.forEach { chapter ->
            currentCoroutineContext().ensureActive()
            val cachedContent = BookHelp.getContent(book, chapter)
            if (cachedContent == null) {
                skippedUncached++
                return@forEach
            }
            inspected++
            val fingerprint = cachedContent.sha256()
            val existingRecord = appDb.aiChapterPurifyRecordDao.get(book.bookUrl, chapter.index)
            if (!force &&
                existingRecord?.contentFingerprint == fingerprint &&
                existingRecord.state == AiChapterPurifyRecord.STATE_COMPLETED
            ) {
                skippedCompleted++
                return@forEach
            }
            try {
                val paragraphs = ContentProcessor.get(book).getContent(
                    book = book,
                    chapter = chapter,
                    content = cachedContent,
                    includeTitle = false,
                    useReplace = false
                ).textList.mapIndexedNotNull { index, content ->
                    val normalized = content.trim()
                    normalized.takeIf { it.isNotEmpty() }?.let {
                        AiChapterPurifyParagraph(index + 1, it)
                    }
                }
                if (paragraphs.isEmpty()) {
                    throw AiChapterPurifyException(
                        "AI chapter purification chapter ${chapter.index + 1} has no usable cached paragraphs"
                    )
                }
                val rules = AiChapterPurifyHelper.generateRules(paragraphs)
                currentCoroutineContext().ensureActive()
                addedRules += insertNewRules(book, rules)
                appDb.aiChapterPurifyRecordDao.insert(
                    AiChapterPurifyRecord(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapter.index,
                        contentFingerprint = fingerprint,
                        ruleCount = rules.size
                    )
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (throwable: Throwable) {
                val failure = AiChapterPurifyException(
                    "AI chapter purification failed for chapter ${chapter.index + 1}: " +
                        (throwable.message ?: throwable.javaClass.simpleName),
                    throwable
                )
                try {
                    appDb.aiChapterPurifyRecordDao.insert(
                        AiChapterPurifyRecord(
                            bookUrl = book.bookUrl,
                            chapterIndex = chapter.index,
                            contentFingerprint = fingerprint,
                            ruleCount = 0,
                            state = AiChapterPurifyRecord.STATE_FAILED,
                            failureMessage = failure.message
                        )
                    )
                } catch (recordFailure: Throwable) {
                    failure.addSuppressed(recordFailure)
                }
                throw failure
            }
        }
        if (addedRules > 0) {
            ContentProcessor.upReplaceRules()
        }
        return AiChapterPurifyRunResult(
            requestedChapters = chapterCount,
            inspectedChapters = inspected,
            skippedCompleted = skippedCompleted,
            skippedUncached = skippedUncached,
            addedRules = addedRules
        )
    }

    private fun insertNewRules(book: Book, rules: List<AiChapterPurifyRule>): Int {
        if (rules.isEmpty()) return 0
        val scope = listOf(book.name, book.origin)
            .filter { it.isNotBlank() }
            .joinToString(";")
        check(scope.isNotBlank()) { "AI chapter purification cannot create a rule without book scope" }
        var nextOrder = appDb.replaceRuleDao.maxOrder + 1
        val newRules = rules.mapNotNull { rule ->
            if (appDb.replaceRuleDao.findLiteralByScopePatternReplacement(scope, rule.old, rule.new) != null) {
                null
            } else {
                ReplaceRule(
                    name = "AI净化 ${rule.type}: ${rule.old.take(40)}",
                    group = RULE_GROUP,
                    pattern = rule.old,
                    replacement = rule.new,
                    scope = scope,
                    scopeTitle = false,
                    scopeContent = true,
                    isEnabled = true,
                    isRegex = false,
                    timeoutMillisecond = 3_000L,
                    order = nextOrder++
                )
            }
        }
        if (newRules.isNotEmpty()) {
            appDb.replaceRuleDao.insert(*newRules.toTypedArray())
        }
        return newRules.size
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
