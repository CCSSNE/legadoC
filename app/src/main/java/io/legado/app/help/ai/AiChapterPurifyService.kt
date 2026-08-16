package io.legado.app.help.ai

import io.legado.app.constant.AppLog
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
        force: Boolean = false,
        onProgress: suspend (AiChapterPurifyProgress) -> Unit = {}
    ): AiChapterPurifyRunResult {
        require(chapterCount >= AiChapterPurifyConfig.MIN_CHAPTER_COUNT) {
            "AI chapter purification chapter count must be positive"
        }
        AppLog.putAi(
            "CHAPTER_PURIFY TRIGGER\n" +
                "book=${book.name}\n" +
                "origin=${book.origin}\n" +
                "bookUrl=${book.bookUrl}\n" +
                "startChapter=${startChapterIndex + 1}\n" +
                "requestedChapters=$chapterCount\n" +
                "force=$force\n" +
                "replaceEnabled=${book.getUseReplaceRule()}\n" +
                "types=typo:${AiChapterPurifyConfig.typoEnabled}," +
                "noise:${AiChapterPurifyConfig.noiseEnabled}," +
                "ad:${AiChapterPurifyConfig.adEnabled}"
        )
        val chapters = appDb.bookChapterDao.getChapterList(
            book.bookUrl,
            startChapterIndex,
            startChapterIndex + chapterCount - 1
        )
        AppLog.putAi("CHAPTER_PURIFY CHAPTERS_FOUND count=${chapters.size}")
        var inspected = 0
        var skippedCompleted = 0
        var skippedUncached = 0
        var addedRules = 0
        chapters.forEach { chapter ->
            currentCoroutineContext().ensureActive()
            val cachedContent = BookHelp.getContent(book, chapter)
            if (cachedContent == null) {
                skippedUncached++
                AppLog.putAi(
                    "CHAPTER_PURIFY SKIP_UNCACHED chapter=${chapter.index + 1}"
                )
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
                AppLog.putAi(
                    "CHAPTER_PURIFY SKIP_COMPLETED chapter=${chapter.index + 1}\n" +
                        "fingerprint=$fingerprint\n" +
                        "recordRuleCount=${existingRecord.ruleCount}"
                )
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
                AppLog.putAi(
                    "CHAPTER_PURIFY CHAPTER_PREPARED chapter=${chapter.index + 1}\n" +
                        "fingerprint=$fingerprint\n" +
                        "paragraphCount=${paragraphs.size}\n" +
                        "paragraphChars=${paragraphs.sumOf { it.content.length }}"
                )
                val rules = AiChapterPurifyHelper.generateRules(
                    paragraphs = paragraphs,
                    chapterIndex = chapter.index,
                    onProgress = onProgress
                )
                currentCoroutineContext().ensureActive()
                val chapterAddedRules = insertNewRules(book, rules)
                addedRules += chapterAddedRules
                AppLog.putAi(
                    "CHAPTER_PURIFY RULES_READY chapter=${chapter.index + 1}\n" +
                        "candidateRules=${rules.size}\n" +
                        "addedRules=$chapterAddedRules\n" +
                        "rules=${formatRules(rules)}"
                )
                appDb.aiChapterPurifyRecordDao.insert(
                    AiChapterPurifyRecord(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapter.index,
                        contentFingerprint = fingerprint,
                        ruleCount = rules.size
                    )
                )
                onProgress(
                    AiChapterPurifyProgress.ChapterRulesStored(
                        chapterIndex = chapter.index,
                        candidateRules = rules.size,
                        addedRules = chapterAddedRules
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
                AppLog.putAi(
                    "CHAPTER_PURIFY CHAPTER_FAILED chapter=${chapter.index + 1}\n" +
                        "fingerprint=$fingerprint",
                    failure
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
            try {
                ContentProcessor.upReplaceRules()
                AppLog.putAi(
                    "CHAPTER_PURIFY REPLACEMENT_CACHE_REFRESHED addedRules=$addedRules"
                )
            } catch (throwable: Throwable) {
                AppLog.putAi(
                    "CHAPTER_PURIFY REPLACEMENT_CACHE_REFRESH_FAILED addedRules=$addedRules",
                    throwable
                )
                throw throwable
            }
        } else {
            AppLog.putAi("CHAPTER_PURIFY NO_NEW_RULES")
        }
        onProgress(AiChapterPurifyProgress.ReplacementApplied(addedRules))
        AppLog.putAi(
            "CHAPTER_PURIFY COMPLETE\n" +
                "requestedChapters=$chapterCount\n" +
                "inspectedChapters=$inspected\n" +
                "skippedCompleted=$skippedCompleted\n" +
                "skippedUncached=$skippedUncached\n" +
                "addedRules=$addedRules"
        )
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

    private fun formatRules(rules: List<AiChapterPurifyRule>): String {
        return rules.joinToString(" || ") { rule ->
            "id=${rule.id},type=${rule.type},old=${rule.old},new=${rule.new}"
        }.ifBlank { "<none>" }
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
