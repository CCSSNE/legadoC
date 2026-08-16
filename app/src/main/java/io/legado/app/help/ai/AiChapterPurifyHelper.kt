package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.ui.main.ai.AiChatException
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class AiChapterPurifyParagraph(
    val id: Int,
    val content: String
)

data class AiChapterPurifyRule(
    val id: Int,
    val type: String,
    val old: String,
    val new: String
)

class AiChapterPurifyException(
    message: String,
    cause: Throwable? = null,
    val debugLog: String? = when (cause) {
        is AiChatException -> cause.debugLog
        is AiChapterPurifyException -> cause.debugLog
        else -> null
    }
) : IllegalStateException(message, cause)

sealed interface AiChapterPurifyProgress {
    data class RequestAccepted(
        val chapterIndex: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val attempt: Int
    ) : AiChapterPurifyProgress

    data class ResponseReceived(
        val chapterIndex: Int,
        val chunkIndex: Int,
        val totalChunks: Int
    ) : AiChapterPurifyProgress

    data class ChapterRulesStored(
        val chapterIndex: Int,
        val candidateRules: Int,
        val addedRules: Int
    ) : AiChapterPurifyProgress

    data class ReplacementApplied(
        val addedRules: Int
    ) : AiChapterPurifyProgress
}

object AiChapterPurifyHelper {

    private val supportedTypes = setOf("typo", "noise", "ad")

    private data class Response(
        val rules: List<ResponseRule>?
    )

    private data class ResponseRule(
        val id: Int,
        val type: String?,
        val old: String?,
        val new: String?
    )

    suspend fun generateRules(
        paragraphs: List<AiChapterPurifyParagraph>,
        chapterIndex: Int,
        onProgress: suspend (AiChapterPurifyProgress) -> Unit = {}
    ): List<AiChapterPurifyRule> {
        require(paragraphs.isNotEmpty()) { "No chapter paragraphs available for AI purification" }
        require(
            AiChapterPurifyConfig.typoEnabled ||
                AiChapterPurifyConfig.noiseEnabled ||
                AiChapterPurifyConfig.adEnabled
        ) { "Enable at least one AI chapter purification type" }

        val chunks = splitIntoChunks(paragraphs, AiChapterPurifyConfig.segmentLimit)
        val target = try {
            AiChapterPurifyConfig.requireModelTarget()
        } catch (throwable: Throwable) {
            AppLog.putAi(
                "CHAPTER_PURIFY MODEL_TARGET_FAILED chapter=${chapterIndex + 1}\n" +
                    "chunks=${chunks.size}",
                throwable
            )
            throw throwable
        }
        AppLog.putAi(
            "CHAPTER_PURIFY BATCHES_PREPARED\n" +
                "chapter=${chapterIndex + 1}\n" +
                "paragraphs=${paragraphs.size}\n" +
                "chunks=${chunks.size}\n" +
                "segmentLimit=${AiChapterPurifyConfig.segmentLimit}\n" +
                "concurrency=${AiChapterPurifyConfig.concurrency}\n" +
                "retryCount=${AiChapterPurifyConfig.retryCount}\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}"
        )
        val semaphore = Semaphore(AiChapterPurifyConfig.concurrency)
        return coroutineScope {
            chunks.mapIndexed { chunkIndex, chunk ->
                async {
                    semaphore.withPermit {
                        requestChunk(
                            target = target,
                            chapterIndex = chapterIndex,
                            chunkIndex = chunkIndex + 1,
                            totalChunks = chunks.size,
                            paragraphs = chunk,
                            onProgress = onProgress
                        )
                    }
                }
            }.awaitAll().flatten().distinctBy { it.old to it.new }
        }
    }

    private suspend fun requestChunk(
        target: AiChapterPurifyModelTarget,
        chapterIndex: Int,
        chunkIndex: Int,
        totalChunks: Int,
        paragraphs: List<AiChapterPurifyParagraph>,
        onProgress: suspend (AiChapterPurifyProgress) -> Unit
    ): List<AiChapterPurifyRule> {
        var lastFailure: Throwable? = null
        repeat(AiChapterPurifyConfig.retryCount + 1) { attempt ->
            try {
                AppLog.putAi(
                    "CHAPTER_PURIFY BATCH_REQUEST chapter=${chapterIndex + 1}\n" +
                        "batch=$chunkIndex/$totalChunks\n" +
                        "attempt=${attempt + 1}\n" +
                        "paragraphIds=${paragraphs.joinToString { it.id.toString() }}\n" +
                        "chars=${paragraphs.sumOf { it.content.length }}"
                )
                val response = AiChatService.generateStructuredText(
                    provider = target.provider,
                    model = target.modelId,
                    systemPrompt = buildSystemPrompt(),
                    userContent = buildUserContent(paragraphs),
                    temperature = 0.0,
                    onRequestAccepted = {
                        onProgress(
                            AiChapterPurifyProgress.RequestAccepted(
                                chapterIndex = chapterIndex,
                                chunkIndex = chunkIndex,
                                totalChunks = totalChunks,
                                attempt = attempt + 1
                            )
                        )
                    }
                )
                onProgress(
                    AiChapterPurifyProgress.ResponseReceived(
                        chapterIndex = chapterIndex,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks
                    )
                )
                val rules = parseAndValidate(response, paragraphs)
                AppLog.putAi(
                    "CHAPTER_PURIFY BATCH_PARSED chapter=${chapterIndex + 1}\n" +
                        "batch=$chunkIndex/$totalChunks\n" +
                        "attempt=${attempt + 1}\n" +
                        "rules=${rules.size}\n" +
                        "ruleDetails=${rules.joinToString(" || ") { rule ->
                            "id=${rule.id},type=${rule.type},old=${rule.old},new=${rule.new}"
                        }.ifBlank { "<none>" }}"
                )
                return rules
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                lastFailure = throwable
                AppLog.putAi(
                    "CHAPTER_PURIFY BATCH_FAILED chapter=${chapterIndex + 1}\n" +
                        "batch=$chunkIndex/$totalChunks\n" +
                        "attempt=${attempt + 1}",
                    throwable
                )
                if (attempt < AiChapterPurifyConfig.retryCount) {
                    delay(300L * (attempt + 1))
                }
            }
        }
        AppLog.putAi(
            "CHAPTER_PURIFY BATCH_EXHAUSTED chapter=${chapterIndex + 1}\n" +
                "batch=$chunkIndex/$totalChunks\n" +
                "attempts=${AiChapterPurifyConfig.retryCount + 1}",
            lastFailure
        )
        throw AiChapterPurifyException(
            message = "AI chapter purification batch $chunkIndex failed after " +
                "${AiChapterPurifyConfig.retryCount + 1} attempt(s)",
            cause = lastFailure
        )
    }

    private fun buildSystemPrompt(): String {
        val enabledTypes = buildList {
            if (AiChapterPurifyConfig.typoEnabled) add("typo")
            if (AiChapterPurifyConfig.noiseEnabled) add("noise")
            if (AiChapterPurifyConfig.adEnabled) add("ad")
        }.joinToString(",")
        return """
            You are a strict structured-rule generator. Do not use tools. Do not return analysis or reasoning.
            Return exactly one JSON object and no Markdown fence:
            {"rules":[{"id":1,"type":"ad|noise|typo","old":"exact source text","new":"replacement text"}]}
            The id must be the input paragraph number. The old field must be an exact contiguous substring
            of that one input paragraph. Only these types are enabled: $enabledTypes.
            For ad, old must be the entire input paragraph and new must be an empty string.
            For typo, old and new must both contain at least two characters.
            For noise, do not rewrite normal prose. Return an empty rules array when uncertain.

            Task prompt controlled by the user:
            ${AiChapterPurifyConfig.prompt}
        """.trimIndent()
    }

    private fun buildUserContent(paragraphs: List<AiChapterPurifyParagraph>): String {
        return buildString {
            append("Input paragraphs:\n")
            paragraphs.forEach { paragraph ->
                append('[').append(paragraph.id).append("] ")
                append(paragraph.content)
                append('\n')
            }
        }
    }

    private fun parseAndValidate(
        response: String,
        paragraphs: List<AiChapterPurifyParagraph>
    ): List<AiChapterPurifyRule> {
        val parsed = try {
            GSON.fromJson(response.trim(), Response::class.java)
        } catch (throwable: Throwable) {
            throw AiChapterPurifyException("AI chapter purification returned invalid JSON", throwable)
        } ?: throw AiChapterPurifyException("AI chapter purification returned an empty JSON value")
        val responseRules = parsed.rules
            ?: throw AiChapterPurifyException("AI chapter purification JSON has no rules array")
        val sourceById = paragraphs.associateBy { it.id }
        return responseRules.mapIndexed { index, rule ->
            val id = rule.id
            val source = sourceById[id]?.content
                ?: throw AiChapterPurifyException(
                    "AI chapter purification rule ${index + 1} references unknown paragraph id $id"
                )
            val type = rule.type?.lowercase()?.trim().orEmpty()
            if (type !in supportedTypes || !AiChapterPurifyConfig.isTypeEnabled(type)) {
                throw AiChapterPurifyException(
                    "AI chapter purification rule ${index + 1} has disabled or unsupported type '$type'"
                )
            }
            val old = rule.old ?: throw AiChapterPurifyException(
                "AI chapter purification rule ${index + 1} has no old text"
            )
            val new = rule.new ?: throw AiChapterPurifyException(
                "AI chapter purification rule ${index + 1} has no new text"
            )
            validateRule(index + 1, type, old, new, source)
            AiChapterPurifyRule(id, type, old, new)
        }
    }

    private fun validateRule(
        position: Int,
        type: String,
        old: String,
        new: String,
        source: String
    ) {
        if (old.isBlank()) {
            throw AiChapterPurifyException("AI chapter purification rule $position has blank old text")
        }
        if (old !in source) {
            throw AiChapterPurifyException(
                "AI chapter purification rule $position old text is not an exact substring of its paragraph"
            )
        }
        if (old == new) {
            throw AiChapterPurifyException("AI chapter purification rule $position makes no change")
        }
        when (type) {
            "ad" -> {
                if (old != source || new.isNotEmpty()) {
                    throw AiChapterPurifyException(
                        "AI chapter purification ad rule $position must remove one whole paragraph"
                    )
                }
            }

            "typo" -> {
                if (old.length < 2 || new.length < 2) {
                    throw AiChapterPurifyException(
                        "AI chapter purification typo rule $position must contain at least two characters"
                    )
                }
            }

            "noise" -> {
                if (new.isBlank() && old.length < 4) {
                    throw AiChapterPurifyException(
                        "AI chapter purification noise rule $position is too short to remove"
                    )
                }
            }
        }
    }

    private fun splitIntoChunks(
        paragraphs: List<AiChapterPurifyParagraph>,
        characterLimit: Int
    ): List<List<AiChapterPurifyParagraph>> {
        val chunks = mutableListOf<MutableList<AiChapterPurifyParagraph>>()
        var current = mutableListOf<AiChapterPurifyParagraph>()
        var currentLength = 0
        paragraphs.forEach { paragraph ->
            val estimatedLength = paragraph.content.length + paragraph.id.toString().length + 5
            require(estimatedLength <= characterLimit) {
                "Chapter paragraph ${paragraph.id} exceeds the AI chapter purification segment limit"
            }
            if (current.isNotEmpty() && currentLength + estimatedLength > characterLimit) {
                chunks.add(current)
                current = mutableListOf()
                currentLength = 0
            }
            current.add(paragraph)
            currentLength += estimatedLength
        }
        if (current.isNotEmpty()) {
            chunks.add(current)
        }
        return chunks
    }
}
