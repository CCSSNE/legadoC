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
        check(book.getUseReplaceRule()) {
            "AI chapter purification requires ordinary purification replacement to be enabled"
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
            val rawFingerprint = cachedContent.sha256()
            var fingerprint = rawFingerprint
            try {
                val contentProcessor = ContentProcessor.get(book)
                val processedContent = contentProcessor.getContent(
                    book = book,
                    chapter = chapter,
                    content = cachedContent,
                    includeTitle = false,
                    useReplace = true
                )
                val preprocessRules = AiChapterPurifyConfig.preprocessRules
                val enabledTypes = AiChapterPurifyConfig.enabledTypes()
                if (enabledTypes.isEmpty()) {
                    throw AiChapterPurifyException(
                        "Enable at least one AI chapter purification type"
                    )
                }
                AppLog.putAi(
                    "CHAPTER_PURIFY PREPROCESS_CONFIG chapter=${chapter.index + 1}\n" +
                        "ruleCount=${preprocessRules.size}\n" +
                        "enabledRuleCount=${preprocessRules.count { it.enabled }}\n" +
                        "rules=${preprocessRules.joinToString(" || ") { rule ->
                            "name=${rule.name},enabled=${rule.enabled},order=${rule.order}," +
                                "scopes=${rule.effectiveScopes().joinToString(",")}," +
                                "pattern=${rule.pattern},replacement=${rule.replacement}"
                        }.ifBlank { "<none>" }}"
                )
                val paragraphs = processedContent.textList.mapIndexedNotNull { index, content ->
                    val normalized = content.trim()
                    normalized.takeIf { it.isNotEmpty() }?.let {
                        val preprocessedByType = AiChapterPurifyConfig.supportedTypes.associateWith { type ->
                            AiChapterPurifyHelper.prepareParagraphForModel(
                                content = it,
                                scope = type,
                                rules = preprocessRules
                            )
                        }
                        val nonBlankTypes = enabledTypes.filter { type ->
                            preprocessedByType.getValue(type).text.isNotBlank()
                        }
                        if (nonBlankTypes.isEmpty()) {
                            AppLog.putAi(
                                "CHAPTER_PURIFY SKIP_PREPROCESSED_EMPTY chapter=${chapter.index + 1}\n" +
                                    "paragraph=${index + 1}\n" +
                                    "sourceChars=${it.length}\n" +
                                    "enabledTypes=${enabledTypes.joinToString(",")}\n" +
                                    "appliedPreprocessRules=${preprocessedByType.values.flatMap { value -> value.appliedRuleNames }.distinct().joinToString(",")}"
                            )
                            null
                        } else {
                            AiChapterPurifyParagraph(
                                id = index + 1,
                                content = it,
                                preprocessedByType = preprocessedByType
                            )
                        }
                    }
                }
                if (paragraphs.isEmpty()) {
                    throw AiChapterPurifyException(
                        "AI chapter purification chapter ${chapter.index + 1} has no usable cached paragraphs"
                    )
                }
                fingerprint = buildString {
                    append(AiChapterPurifyConfig.preprocessJson).append('\u0000')
                    append(enabledTypes.joinToString(",")).append('\n')
                    paragraphs.forEach { paragraph ->
                        append(paragraph.id)
                        enabledTypes.forEach { type ->
                            append('\u0000').append(type).append('\u0000')
                            append(paragraph.preprocessedByType.getValue(type).text)
                        }
                        append('\n')
                    }
                }.sha256()
                val existingRecord = appDb.aiChapterPurifyRecordDao.get(book.bookUrl, chapter.index)
                if (!force &&
                    existingRecord?.contentFingerprint == fingerprint &&
                    existingRecord.state == AiChapterPurifyRecord.STATE_COMPLETED
                ) {
                    skippedCompleted++
                    AppLog.putAi(
                        "CHAPTER_PURIFY SKIP_COMPLETED chapter=${chapter.index + 1}\n" +
                            "rawFingerprint=$rawFingerprint\n" +
                            "inputFingerprint=$fingerprint\n" +
                            "existingRuleCount=${contentProcessor.getContentReplaceRules().size}\n" +
                            "effectiveRuleCount=${processedContent.effectiveReplaceRules?.size ?: 0}\n" +
                            "recordRuleCount=${existingRecord.ruleCount}"
                    )
                    return@forEach
                }
                AppLog.putAi(
                    "CHAPTER_PURIFY CHAPTER_PREPARED chapter=${chapter.index + 1}\n" +
                        "rawFingerprint=$rawFingerprint\n" +
                        "inputFingerprint=$fingerprint\n" +
                        "rawChars=${cachedContent.length}\n" +
                        "processedChars=${processedContent.toString().length}\n" +
                        "paragraphCount=${paragraphs.size}\n" +
                        "paragraphChars=${paragraphs.sumOf { it.content.length }}\n" +
                        "enabledTypes=${enabledTypes.joinToString(",")}\n" +
                        "modelInputChars=${paragraphs.sumOf { paragraph ->
                            enabledTypes.sumOf { type -> paragraph.preprocessedByType.getValue(type).text.length }
                        }}\n" +
                        "preprocessedCharsRemovedAcrossScopes=${paragraphs.sumOf { paragraph ->
                            enabledTypes.sumOf { type ->
                                paragraph.content.length - paragraph.preprocessedByType.getValue(type).text.length
                            }
                        }}\n" +
                        "preprocessRuleCount=${preprocessRules.size}\n" +
                        "preprocessEnabledRuleCount=${preprocessRules.count { it.enabled }}\n" +
                        "appliedPreprocessRules=${paragraphs.flatMap { paragraph ->
                            enabledTypes.flatMap { type -> paragraph.preprocessedByType.getValue(type).appliedRuleNames }
                        }.distinct().joinToString(",")}\n" +
                        "existingRuleCount=${contentProcessor.getContentReplaceRules().size}\n" +
                        "effectiveRuleCount=${processedContent.effectiveReplaceRules?.size ?: 0}\n" +
                        "rulesAppliedBeforeAi=true"
                )
                val rules = AiChapterPurifyHelper.generateRules(
                    paragraphs = paragraphs,
                    chapterIndex = chapter.index,
                    onProgress = onProgress
                )
                currentCoroutineContext().ensureActive()
                val chapterAddedRules = insertNewRules(book, rules)
                addedRules += chapterAddedRules
                if (chapterAddedRules > 0) {
                    try {
                        ContentProcessor.upReplaceRules()
                        AppLog.putAi(
                            "CHAPTER_PURIFY CHAPTER_REPLACEMENT_CACHE_REFRESHED chapter=${chapter.index + 1}\n" +
                                "addedRules=$chapterAddedRules"
                        )
                    } catch (throwable: Throwable) {
                        AppLog.putAi(
                            "CHAPTER_PURIFY CHAPTER_REPLACEMENT_CACHE_REFRESH_FAILED chapter=${chapter.index + 1}\n" +
                                "addedRules=$chapterAddedRules",
                            throwable
                        )
                        throw throwable
                    }
                }
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
                        "rawFingerprint=$rawFingerprint\n" +
                        "inputFingerprint=$fingerprint",
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
            AppLog.putAi(
                "CHAPTER_PURIFY REPLACEMENT_CACHE_REFRESHED totalAddedRules=$addedRules"
            )
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
