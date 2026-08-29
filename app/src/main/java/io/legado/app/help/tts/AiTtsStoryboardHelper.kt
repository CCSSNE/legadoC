package io.legado.app.help.tts

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.help.ai.AiStructuredRequestTemplate
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * AI 听书分镜（1号AI）：章节文本 → 每个候选片段的说话人归因。
 *
 * 客户端先把段落切成候选 unit（引号对、冒号引语、心声），AI 只做归因，不返回正文。
 * 严格校验 AI 输出，失败整章重试、再失败二分重试。
 * 结果写入 StoryboardCacheStore，同章重读零 AI 调用。
 */
object AiTtsStoryboardHelper {

    private const val BASE_ROUTING_ASSET = "tts_storyboard/base-routing.md"

    private val quotePairs = mapOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
        '"' to '"'
    )
    private val quoteCloseCandidates = mapOf(
        '“' to listOf('”', '“'),
        '‘' to listOf('’', '‘'),
        '「' to listOf('」'),
        '『' to listOf('』'),
        '"' to listOf('"', '”')
    )
    private val sentencePunctuation = "。！？!?；;"
    private val thoughtCues = listOf("心想", "心道", "暗道", "想道", "心里想", "心中想", "心里暗道", "心中暗道")
    private val femaleAddresses = listOf(
        "小妹妹", "妹妹", "小姑娘", "姑娘", "小姐", "女士", "女侠", "夫人", "娘子"
    )
    private val maleAddresses = listOf(
        "小弟弟", "弟弟", "小公子", "公子", "少爷", "先生", "小哥", "大哥", "大叔", "老爷"
    )
    private val colonDialogueCues = listOf(
        "说", "说道", "问", "问道", "喊", "喊道", "叫", "叫道", "道", "开口",
        "吐槽", "坦言", "回答", "答道", "回道", "回复", "说了句", "喊上一句", "补了一句"
    )
    private val narratedQuoteStrongCues = listOf(
        "那句", "这句", "那句话", "这句话", "原话", "所谓", "口头禅",
        "字眼", "词语", "称呼", "标题", "名字", "写着", "显示"
    )
    private val narratedQuoteShortCues = listOf(
        "一句", "一声", "一串", "几个字", "两个字", "三个字", "四个字", "五个字"
    )
    private val baseUnitKeys = setOf(
        "unitId", "roleType", "characterName", "characterId", "castRoleId", "speakerGender",
        "identityType", "nameType", "identityEvidence", "genderEvidence", "mergeCastRoleIds",
        "status", "confidence", "evidence"
    )
    private val rootKeys = setOf("units", "newCharacters")
    private val roleTypes = setOf("narrator", "character", "thought", "other")
    private val statuses = setOf("assigned", "unknown")
    private val speakerGenders = setOf("male", "female", "unknown")
    private val identityTypes = setOf(
        StoryboardSegment.IdentityType.NONE,
        StoryboardSegment.IdentityType.FORMAL_CHARACTER,
        StoryboardSegment.IdentityType.CAST_ROLE,
        StoryboardSegment.IdentityType.STABLE_CANDIDATE,
        StoryboardSegment.IdentityType.PENDING,
        StoryboardSegment.IdentityType.GUEST
    )
    private val nameTypes = setOf(
        StoryboardSegment.NameType.PROPER_NAME,
        StoryboardSegment.NameType.ALIAS,
        StoryboardSegment.NameType.UNIQUE_TITLE,
        StoryboardSegment.NameType.GENERIC_LABEL,
        StoryboardSegment.NameType.UNKNOWN
    )
    private val evidenceLevels = setOf(
        StoryboardSegment.Evidence.EXPLICIT,
        StoryboardSegment.Evidence.CONTEXTUAL,
        StoryboardSegment.Evidence.INFERRED,
        StoryboardSegment.Evidence.UNKNOWN
    )

    internal data class ContextParagraph(val paragraphIndex: Int, val text: String)

    internal data class TextRange(val paragraphIndex: Int, val start: Int, val end: Int)

    internal data class CandidateUnit(
        val unitId: String,
        val kind: String,
        val roleHint: String,
        val ranges: List<TextRange>,
        val textPreview: String,
        val cueBefore: String,
        val cueAfter: String
    )

    private data class UnitSpan(val start: Int, val end: Int, val kind: String, val roleHint: String)

    internal data class KnownCharacter(
        val characterId: Long,
        val name: String,
        val aliases: List<String>,
        val gender: String
    )

    internal data class KnownCastRole(
        val castRoleId: Long,
        val name: String,
        val aliases: List<String>,
        val gender: String,
        val identityState: String,
        val occurrenceCount: Int
    )

    private data class KnownSpeakerIndex(
        val charactersById: Map<Long, KnownCharacter>,
        val charactersByName: Map<String, KnownCharacter>,
        val castRolesById: Map<Long, KnownCastRole>,
        val castRolesByName: Map<String, KnownCastRole>
    )

    internal data class ModelUnitResult(
        val unitId: String,
        val roleType: String,
        val characterName: String = "",
        val characterId: Long = 0L,
        val castRoleId: Long = 0L,
        val speakerGender: String = "unknown",
        val identityType: String = StoryboardSegment.IdentityType.NONE,
        val nameType: String = StoryboardSegment.NameType.UNKNOWN,
        val identityEvidence: String = StoryboardSegment.Evidence.UNKNOWN,
        val genderEvidence: String = StoryboardSegment.Evidence.UNKNOWN,
        val mergeCastRoleIds: List<Long> = emptyList(),
        val status: String = "unknown",
        val confidence: Float = 0f,
        val evidence: String = ""
    )

    // ===================== 对外入口 =====================

    suspend fun getOrGenerate(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        content: String
    ): ChapterStoryboard {
        val (provider, modelId) = AiStoryboardConfig.requireModelTarget()
        val contentHash = StoryboardCacheStore.contentHash(content)
        val key = StoryboardCacheStore.cacheKey(
            book.bookUrl, chapterIndex, chapterTitle, contentHash, provider.id, modelId
        )
        withContext(Dispatchers.IO) { StoryboardCacheStore.load(key) }?.let { return it }
        val storyboard = generate(book, chapterIndex, chapterTitle, content, contentHash, provider, modelId)
        withContext(Dispatchers.IO) { StoryboardCacheStore.save(key, storyboard) }
        return storyboard
    }

    fun loadCached(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        content: String
    ): ChapterStoryboard? {
        if (!AiStoryboardConfig.isConfigured()) return null
        val (provider, modelId) = runCatching { AiStoryboardConfig.requireModelTarget() }
            .getOrNull() ?: return null
        val contentHash = StoryboardCacheStore.contentHash(content)
        return StoryboardCacheStore.cacheKey(
            book.bookUrl, chapterIndex, chapterTitle, contentHash, provider.id, modelId
        ).let { StoryboardCacheStore.load(it) }
    }

    suspend fun regenerate(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        content: String
    ): ChapterStoryboard {
        val (provider, modelId) = AiStoryboardConfig.requireModelTarget()
        val contentHash = StoryboardCacheStore.contentHash(content)
        val key = StoryboardCacheStore.cacheKey(
            book.bookUrl, chapterIndex, chapterTitle, contentHash, provider.id, modelId
        )
        StoryboardCacheStore.delete(key)
        val storyboard = generate(book, chapterIndex, chapterTitle, content, contentHash, provider, modelId)
        StoryboardCacheStore.save(key, storyboard)
        return storyboard
    }

    private suspend fun generate(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        contentHash: String,
        provider: io.legado.app.ui.main.ai.AiProviderConfig,
        modelId: String
    ): ChapterStoryboard {
        val paragraphs = paragraphsFromContent(content)
        val units = buildCandidateUnits(paragraphs)
        val (knownCharacters, knownCastRoles) = withContext(Dispatchers.IO) {
            loadKnownCharacters(book) to loadKnownCastRoles(book, chapterIndex)
        }
        val assignments = requestModelUnits(
            provider = provider,
            modelId = modelId,
            paragraphs = paragraphs,
            units = units,
            knownCharacters = knownCharacters,
            knownCastRoles = knownCastRoles
        )
        val segments = buildSegments(paragraphs, units, assignments)
        return ChapterStoryboard(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            contentHash = contentHash,
            paragraphs = paragraphs.map { it.text },
            scenes = listOf(
                StoryboardScene(
                    sceneId = "scene_1",
                    title = "",
                    startParagraphIndex = paragraphs.firstOrNull()?.paragraphIndex ?: 0,
                    endParagraphIndex = paragraphs.lastOrNull()?.paragraphIndex ?: 0,
                    segments = segments
                )
            )
        )
    }

    private fun loadKnownCharacters(book: Book): List<KnownCharacter> {
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        return appDb.bookRoleDao.getRoles(workKey).map { role ->
            KnownCharacter(
                characterId = role.roleId,
                name = role.name,
                aliases = parseAliases(role.aliasesJson),
                gender = role.gender
            )
        }
    }

    private fun loadKnownCastRoles(book: Book, chapterIndex: Int): List<KnownCastRole> {
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        return appDb.bookRoleDao.getCastRoles(workKey)
            .filter { !it.ignored && it.linkedRoleId == 0L }
            .filter {
                it.identityState == BookTtsCastRole.IdentityState.STABLE ||
                    kotlin.math.abs(it.lastChapterIndex - chapterIndex) <= 12
            }
            .map { role ->
                KnownCastRole(
                    castRoleId = role.castRoleId,
                    name = role.name,
                    aliases = parseAliases(role.aliasesJson),
                    gender = role.gender,
                    identityState = role.identityState,
                    occurrenceCount = role.occurrenceCount
                )
            }
    }

    private fun parseAliases(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            GSON.fromJsonArray<String>(json).getOrNull().orEmpty()
        }.getOrDefault(emptyList())
    }

    // ===================== AI 请求与重试 =====================

    private suspend fun requestModelUnits(
        provider: io.legado.app.ui.main.ai.AiProviderConfig,
        modelId: String,
        paragraphs: List<ContextParagraph>,
        units: List<CandidateUnit>,
        knownCharacters: List<KnownCharacter>,
        knownCastRoles: List<KnownCastRole>
    ): List<ModelUnitResult> {
        if (units.isEmpty()) return emptyList()
        return runCatching {
            requestModelUnitsOnce(
                provider, modelId, paragraphs, units, knownCharacters, knownCastRoles
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (units.size < 2) throw error
            AppLog.put("AI听书分镜整章结果无效，改为两段重试\n${error.localizedMessage}")
            val midpoint = (units.size + 1) / 2
            val assignments = listOf(units.take(midpoint), units.drop(midpoint)).flatMap { chunk ->
                requestModelUnitsOnce(
                    provider, modelId, paragraphs, chunk, knownCharacters, knownCastRoles
                )
            }
            applyAdjacentGenderEvidence(assignments, units)
        }
    }

    private suspend fun requestModelUnitsOnce(
        provider: io.legado.app.ui.main.ai.AiProviderConfig,
        modelId: String,
        paragraphs: List<ContextParagraph>,
        units: List<CandidateUnit>,
        knownCharacters: List<KnownCharacter>,
        knownCastRoles: List<KnownCastRole>
    ): List<ModelUnitResult> {
        val payload = JsonObject().apply {
            add("contextParagraphs", GSON.toJsonTree(paragraphs))
            add("knownCharacters", GSON.toJsonTree(knownCharacters))
            add("knownCastRoles", GSON.toJsonTree(knownCastRoles))
            add("units", GSON.toJsonTree(units))
            add("targetUnitIds", GSON.toJsonTree(units.map { it.unitId }))
        }
        val result = AiChatService.generateStructuredText(
            provider = provider,
            model = modelId,
            systemPrompt = readBaseRoutingPrompt(),
            userContent = payload.toString(),
            temperature = 0.0,
            requestTemplate = AiStructuredRequestTemplate.default
        )
        check(result.isNotBlank()) { "AI 分镜返回为空" }
        val parsed = parseAndValidate(result, units, knownCharacters, knownCastRoles)
        return applyAdjacentGenderEvidence(parsed, units)
    }

    private fun readBaseRoutingPrompt(): String {
        return appCtx.assets.open(BASE_ROUTING_ASSET).bufferedReader().use { it.readText() }
    }

    // ===================== 输出解析与校验 =====================

    private fun normalizeModelOutput(text: String): String {
        return text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun findTextLeaks(element: com.google.gson.JsonElement, path: String = "root"): List<String> {
        val leaks = mutableListOf<String>()
        if (element is JsonObject) {
            element.entrySet().forEach { (key, value) ->
                if (key in setOf("text", "input", "content", "sourceText", "source_text")) {
                    leaks += "$path.$key"
                }
                if (value.isJsonObject || value.isJsonArray) {
                    leaks += findTextLeaks(value, "$path.$key")
                }
            }
        }
        return leaks
    }

    private fun parseAndValidate(
        raw: String,
        targetUnits: List<CandidateUnit>,
        knownCharacters: List<KnownCharacter>,
        knownCastRoles: List<KnownCastRole>
    ): List<ModelUnitResult> {
        val json = normalizeModelOutput(raw)
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        check(start >= 0 && end >= start) { "AI 未返回 JSON 对象" }
        val element = JsonParser.parseString(json.substring(start, end + 1))
        check(element.isJsonObject) { "AI 返回根节点不是 JSON 对象" }
        val root = element.asJsonObject
        val rootExtraKeys = root.keySet() - rootKeys
        check(rootExtraKeys.isEmpty()) { "AI 返回额外根字段：${rootExtraKeys.joinToString()}" }
        check(findTextLeaks(root).isEmpty()) { "AI 返回中包含正文字段" }
        val unitsElement = root.get("units")
        check(unitsElement != null && unitsElement.isJsonArray) { "AI 返回 units 不是数组" }
        val newCharacters = root.get("newCharacters")
        check(newCharacters == null || (newCharacters.isJsonArray && newCharacters.asJsonArray.size() == 0)) {
            "AI 返回了未允许的新角色"
        }
        val targetUnitIds = targetUnits.map { it.unitId }
        val targetSet = targetUnitIds.toSet()
        val unitArray = unitsElement.asJsonArray
        val output = mutableListOf<ModelUnitResult>()
        for (item in unitArray) {
            check(item.isJsonObject) { "AI 返回 unit 不是对象" }
            val obj = item.asJsonObject
            output += GSON.fromJson(obj, ModelUnitResult::class.java)
                ?: throw IllegalStateException("AI 返回 unit 无法解析")
        }
        val seen = output.map { it.unitId }
        val missing = targetUnitIds.filter { it !in seen }
        val duplicated = seen.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val unknown = seen.filter { it !in targetSet }
        check(missing.isEmpty()) { "AI 漏掉目标 unit：${missing.take(3).joinToString()}" }
        check(duplicated.isEmpty()) { "AI 重复返回 unit：${duplicated.take(3).joinToString()}" }
        check(unknown.isEmpty()) { "AI 返回未知 unit：${unknown.take(3).joinToString()}" }
        val knownIndex = knownSpeakerIndex(knownCharacters, knownCastRoles)
        return output.map { unit ->
            val obj = unitArray.firstOrNull {
                it.isJsonObject && it.asJsonObject.get("unitId")?.asString == unit.unitId
            }?.asJsonObject
            if (obj != null) {
                val extraKeys = obj.keySet() - baseUnitKeys
                check(extraKeys.isEmpty()) { "AI 返回 unit 额外字段：${extraKeys.joinToString()}" }
            }
            check(unit.roleType in roleTypes) { "AI 返回非法 roleType：${unit.roleType}" }
            check(unit.status in statuses) { "AI 返回非法 status：${unit.status}" }
            check(unit.speakerGender in speakerGenders) { "AI 返回非法 speakerGender：${unit.speakerGender}" }
            check(unit.identityType in identityTypes) { "AI 返回非法 identityType：${unit.identityType}" }
            check(unit.nameType in nameTypes) { "AI 返回非法 nameType：${unit.nameType}" }
            check(unit.identityEvidence in evidenceLevels) { "AI 返回非法 identityEvidence：${unit.identityEvidence}" }
            check(unit.genderEvidence in evidenceLevels) { "AI 返回非法 genderEvidence：${unit.genderEvidence}" }
            check(unit.confidence in 0f..1f) { "AI 返回非法 confidence：${unit.confidence}" }
            val source = targetUnits.first { it.unitId == unit.unitId }
            val roleType = routedRoleType(source.roleHint, unit.roleType)
            normalizeModelUnit(unit.copy(roleType = roleType), knownIndex)
        }
    }

    private fun knownSpeakerIndex(
        knownCharacters: List<KnownCharacter>,
        knownCastRoles: List<KnownCastRole>
    ): KnownSpeakerIndex {
        val charactersById = knownCharacters
            .filter { it.characterId > 0L }
            .associateBy { it.characterId }
        val charactersByName = buildMap {
            knownCharacters.forEach { character ->
                (listOf(character.name) + character.aliases).forEach { name ->
                    val key = normalizeIdentityName(name)
                    if (key.isNotBlank()) put(key, character)
                }
            }
        }
        val castRolesById = knownCastRoles.filter { it.castRoleId > 0L }.associateBy { it.castRoleId }
        val castRolesByName = buildMap {
            knownCastRoles.forEach { role ->
                (listOf(role.name) + role.aliases).forEach { name ->
                    val key = normalizeIdentityName(name)
                    if (key.isNotBlank()) put(key, role)
                }
            }
        }
        return KnownSpeakerIndex(charactersById, charactersByName, castRolesById, castRolesByName)
    }

    internal fun normalizeIdentityName(name: String): String {
        return name.trim()
            .replace(Regex("[\\s·・]"), "")
            .lowercase()
    }

    private fun normalizeModelUnit(
        unit: ModelUnitResult,
        knownIndex: KnownSpeakerIndex
    ): ModelUnitResult {
        if (unit.roleType == "narrator" || unit.roleType == "other") {
            return unit.copy(
                characterName = "",
                characterId = 0L,
                castRoleId = 0L,
                speakerGender = "unknown",
                identityType = StoryboardSegment.IdentityType.NONE,
                nameType = StoryboardSegment.NameType.UNKNOWN,
                identityEvidence = StoryboardSegment.Evidence.UNKNOWN,
                genderEvidence = StoryboardSegment.Evidence.UNKNOWN,
                mergeCastRoleIds = emptyList(),
                status = "unknown"
            )
        }
        val modelDisplayName = unit.characterName.trim()
        val normalizedDisplayName = normalizeIdentityName(modelDisplayName)
        val knownCharacter = knownIndex.charactersById[unit.characterId]
            ?: normalizedDisplayName.takeIf { it.isNotBlank() }?.let { knownIndex.charactersByName[it] }
        if (knownCharacter != null) {
            val explicitAlias = unit.nameType == StoryboardSegment.NameType.ALIAS &&
                unit.identityEvidence == StoryboardSegment.Evidence.EXPLICIT &&
                unit.confidence >= 0.85f &&
                normalizedDisplayName.isNotBlank() &&
                normalizedDisplayName != normalizeIdentityName(knownCharacter.name)
            return unit.copy(
                characterName = modelDisplayName.takeIf { explicitAlias } ?: knownCharacter.name,
                characterId = knownCharacter.characterId,
                castRoleId = 0L,
                speakerGender = knownCharacter.gender.takeIf { it in speakerGenders && it != "unknown" }
                    ?: unit.speakerGender,
                identityType = StoryboardSegment.IdentityType.FORMAL_CHARACTER,
                status = "assigned"
            )
        }
        val hasStableNameType = unit.nameType == StoryboardSegment.NameType.PROPER_NAME ||
            unit.nameType == StoryboardSegment.NameType.ALIAS ||
            unit.nameType == StoryboardSegment.NameType.UNIQUE_TITLE
        val knownCastRole = if (
            unit.identityType == StoryboardSegment.IdentityType.GUEST && !hasStableNameType
        ) {
            null
        } else {
            knownIndex.castRolesById[unit.castRoleId]
                ?: if (unit.identityType == StoryboardSegment.IdentityType.CAST_ROLE || hasStableNameType) {
                    normalizedDisplayName.takeIf { it.isNotBlank() }?.let { knownIndex.castRolesByName[it] }
                } else null
        }
        if (knownCastRole != null) {
            val incomingGenderIsStronger =
                evidenceRank(unit.genderEvidence) > evidenceRank(knownCastRole.gender)
            val resolvedGender = unit.speakerGender.takeIf {
                incomingGenderIsStronger && it in speakerGenders && it != "unknown"
            } ?: knownCastRole.gender.takeIf { it in speakerGenders && it != "unknown" }
                ?: unit.speakerGender
            return unit.copy(
                characterName = modelDisplayName.ifBlank { knownCastRole.name },
                characterId = 0L,
                castRoleId = knownCastRole.castRoleId,
                speakerGender = resolvedGender,
                identityType = if (unit.identityType == StoryboardSegment.IdentityType.PENDING) {
                    StoryboardSegment.IdentityType.PENDING
                } else {
                    StoryboardSegment.IdentityType.CAST_ROLE
                },
                status = "assigned"
            )
        }
        val hasClassifiedIdentity = shouldKeepUnboundSpeaker(
            modelDisplayName,
            unit.identityType,
            unit.nameType
        )
        if (hasClassifiedIdentity) {
            return unit.copy(
                characterName = modelDisplayName,
                characterId = 0L,
                castRoleId = 0L,
                status = "unknown"
            )
        }
        return unit.copy(
            roleType = "narrator",
            characterName = "",
            characterId = 0L,
            castRoleId = 0L,
            speakerGender = "unknown",
            identityType = StoryboardSegment.IdentityType.NONE,
            nameType = StoryboardSegment.NameType.UNKNOWN,
            identityEvidence = StoryboardSegment.Evidence.UNKNOWN,
            genderEvidence = StoryboardSegment.Evidence.UNKNOWN,
            mergeCastRoleIds = emptyList(),
            status = "unknown"
        )
    }

    private fun shouldKeepUnboundSpeaker(
        name: String,
        identityType: String,
        nameType: String
    ): Boolean {
        if (name.isBlank()) return false
        if (name in setOf("我", "你", "他", "她", "它", "他们", "她们", "对方", "某人", "那人", "这人")) {
            return false
        }
        return identityType == StoryboardSegment.IdentityType.STABLE_CANDIDATE ||
            identityType == StoryboardSegment.IdentityType.PENDING ||
            identityType == StoryboardSegment.IdentityType.GUEST
    }

    private fun evidenceRank(value: String): Int = when (value) {
        StoryboardSegment.Evidence.EXPLICIT -> 3
        StoryboardSegment.Evidence.CONTEXTUAL -> 2
        StoryboardSegment.Evidence.INFERRED -> 1
        else -> 0
    }

    private fun routedRoleType(roleHint: String, modelRoleType: String): String {
        return if (roleHint == "narrator") "narrator" else modelRoleType
    }

    // ===================== 相邻称呼性别补正 =====================

    private fun applyAdjacentGenderEvidence(
        assignments: List<ModelUnitResult>,
        targetUnits: List<CandidateUnit>
    ): List<ModelUnitResult> {
        if (assignments.isEmpty() || targetUnits.size < 2) return assignments
        val byId = assignments.associateBy { it.unitId }.toMutableMap()
        targetUnits.zipWithNext().forEach { (previousUnit, currentUnit) ->
            val previous = byId[previousUnit.unitId] ?: return@forEach
            val current = byId[currentUnit.unitId] ?: return@forEach
            if (previous.roleType !in setOf("character", "thought") ||
                current.roleType !in setOf("character", "thought") ||
                current.speakerGender != "unknown" ||
                sameSpeakerIdentity(previous, current)
            ) return@forEach
            val previousParagraph = previousUnit.ranges.firstOrNull()?.paragraphIndex ?: return@forEach
            val currentParagraph = currentUnit.ranges.firstOrNull()?.paragraphIndex ?: return@forEach
            if (currentParagraph - previousParagraph !in 0..1) return@forEach
            val text = previousUnit.textPreview.trimStart { character ->
                character.isWhitespace() || character in "“”‘’\"'"
            }
            val address = femaleAddresses.firstOrNull(text::startsWith)
                ?: maleAddresses.firstOrNull(text::startsWith)
                ?: return@forEach
            val gender = if (address in femaleAddresses) "female" else "male"
            byId[current.unitId] = current.copy(
                speakerGender = gender,
                genderEvidence = StoryboardSegment.Evidence.EXPLICIT
            )
        }
        return assignments.map { byId[it.unitId] ?: it }
    }

    private fun sameSpeakerIdentity(first: ModelUnitResult, second: ModelUnitResult): Boolean {
        if (first.characterId > 0L && first.characterId == second.characterId) return true
        if (first.castRoleId > 0L && first.castRoleId == second.castRoleId) return true
        val firstName = normalizeIdentityName(first.characterName)
        val secondName = normalizeIdentityName(second.characterName)
        return firstName.isNotBlank() && firstName == secondName
    }

    // ===================== 候选切分 =====================

    private fun paragraphsFromContent(content: String): List<ContextParagraph> {
        return content.split('\n')
            .map { it.trim() }
            .mapIndexed { index, text -> ContextParagraph(index, text) }
            .filter { it.text.isNotEmpty() }
    }

    private fun buildCandidateUnits(paragraphs: List<ContextParagraph>): List<CandidateUnit> {
        val texts = paragraphs.associate { it.paragraphIndex to it.text }
        val units = arrayListOf<CandidateUnit>()
        paragraphs.forEach { paragraph ->
            val text = paragraph.text
            val quoteSpans = findQuoteSpans(text)
            quoteSpans.forEach { span ->
                val preview = text.substring(span.start, span.end)
                val roleHint = quoteRoleHint(text, span.start, span.end)
                units += makeUnit(
                    kind = if (roleHint == "narrator") "quote_reference" else span.kind,
                    roleHint = roleHint,
                    ranges = listOf(TextRange(paragraph.paragraphIndex, span.start, span.end)),
                    textPreview = preview,
                    cueBefore = contextBefore(texts, paragraph.paragraphIndex, span.start),
                    cueAfter = contextAfter(texts, paragraph.paragraphIndex, span.end)
                )
            }
            findColonUnits(text, quoteSpans).forEach { span ->
                val preview = text.substring(span.start, span.end)
                units += makeUnit(
                    kind = span.kind,
                    roleHint = span.roleHint,
                    ranges = listOf(TextRange(paragraph.paragraphIndex, span.start, span.end)),
                    textPreview = preview,
                    cueBefore = contextBefore(texts, paragraph.paragraphIndex, span.start),
                    cueAfter = contextAfter(texts, paragraph.paragraphIndex, span.end)
                )
            }
        }
        return units.sortedWith(
            compareBy<CandidateUnit> { it.ranges.firstOrNull()?.paragraphIndex ?: 0 }
                .thenBy { it.ranges.firstOrNull()?.start ?: 0 }
                .thenBy { it.ranges.firstOrNull()?.end ?: 0 }
        )
    }

    private fun findQuoteSpans(text: String): List<UnitSpan> {
        val spans = arrayListOf<UnitSpan>()
        var index = 0
        while (index < text.length) {
            val open = text[index]
            if (open !in quotePairs) {
                index++
                continue
            }
            val close = findNextQuoteClose(text, index + 1, open)
            if (close < 0) {
                spans += UnitSpan(index, text.length, "quote_unclosed", "character")
                break
            }
            spans += UnitSpan(index, close + 1, "quote", "character")
            index = close + 1
        }
        return spans
    }

    private fun findNextQuoteClose(text: String, start: Int, open: Char): Int {
        return quoteCloseCandidates[open]
            .orEmpty()
            .map { text.indexOf(it, start) }
            .filter { it >= 0 }
            .minOrNull()
            ?: -1
    }

    private fun findColonUnits(text: String, quoteSpans: List<UnitSpan>): List<UnitSpan> {
        val results = arrayListOf<UnitSpan>()
        val quoteMask = BooleanArray(text.length)
        quoteSpans.forEach { span ->
            for (index in span.start until span.end.coerceAtMost(text.length)) {
                if (index >= 0) quoteMask[index] = true
            }
        }
        var index = 0
        while (index < text.length) {
            if (quoteMask[index] || text[index] !in "：:") {
                index++
                continue
            }
            if (isRatioOrTimeColon(text, index)) {
                index++
                continue
            }
            val prefixStart = previousBoundary(text, index)
            val roleHint = colonRoleHint(text.substring(prefixStart, index))
            var speechStart = index + 1
            while (speechStart < text.length && text[speechStart].isWhitespace()) {
                speechStart++
            }
            if (roleHint == null || speechStart >= text.length || text[speechStart] in quotePairs) {
                index++
                continue
            }
            val speechEnd = if (roleHint == "thought") text.length else nextSentenceEnd(text, speechStart)
            if (speechEnd <= speechStart) {
                index++
                continue
            }
            results += UnitSpan(
                speechStart,
                speechEnd,
                if (roleHint == "thought") "thought_colon" else "dialogue_colon",
                roleHint
            )
            index = speechEnd
        }
        return results
    }

    private fun isRatioOrTimeColon(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        return before?.isDigit() == true && after?.isDigit() == true
    }

    private fun previousBoundary(text: String, index: Int): Int {
        var start = 0
        "。！？!?；;\n".forEach { char ->
            start = maxOf(start, text.lastIndexOf(char, startIndex = index - 1) + 1)
        }
        return start
    }

    private fun nextSentenceEnd(text: String, index: Int): Int {
        var cursor = index
        while (cursor < text.length) {
            if (text[cursor] in sentencePunctuation) {
                var end = cursor + 1
                while (end < text.length && text[end] in "。！？!?…") {
                    end++
                }
                return end
            }
            cursor++
        }
        return text.length
    }

    private fun colonRoleHint(prefix: String): String? {
        val value = prefix.trim().trim('“', '”', '‘', '’', '"', '\'', '，', ',', '。', ':', '：')
        if (value.isBlank() || value.length > 40) return null
        if (thoughtCues.any { value.takeLast(16).contains(it) }) return "thought"
        if ((value.takeLast(16).contains("心里") || value.takeLast(16).contains("心中")) &&
            value.endsWith("想")
        ) return "thought"
        if (colonDialogueCues.any { value.takeLast(16).contains(it) }) return "character"
        return null
    }

    private fun looksLikeThought(text: String, start: Int, end: Int): Boolean {
        val before = text.substring(maxOf(0, start - 40), start)
        val after = text.substring(end, minOf(text.length, end + 40))
        return thoughtCues.any { before.contains(it) || after.contains(it) }
    }

    private fun quoteRoleHint(text: String, start: Int, end: Int): String {
        if (looksLikeThought(text, start, end)) return "thought"
        if (looksLikeNarratedQuote(text, start, end)) return "narrator"
        return "character"
    }

    private fun looksLikeNarratedQuote(text: String, start: Int, end: Int): Boolean {
        if (start !in 0..text.length || end !in start..text.length) return false
        val prefix = text.substring(previousBoundary(text, start), start)
            .trim()
            .trimEnd('，', ',', '、')
        if (prefix.isBlank() || prefix.endsWith('：') || prefix.endsWith(':')) return false
        val nearbyPrefix = prefix.takeLast(28)
        if (narratedQuoteStrongCues.any(nearbyPrefix::contains)) return true
        val quotedLength = (end - start - 2).coerceAtLeast(0)
        return quotedLength <= 16 && narratedQuoteShortCues.any(nearbyPrefix::contains)
    }

    private fun contextBefore(
        paragraphs: Map<Int, String>,
        paragraphIndex: Int,
        start: Int,
        limit: Int = 120
    ): String {
        val current = paragraphs[paragraphIndex].orEmpty().take(start)
        val previous = paragraphs[paragraphIndex - 1].orEmpty()
        return (previous.takeLast(40) + "\n" + current).trim().takeLast(limit)
    }

    private fun contextAfter(
        paragraphs: Map<Int, String>,
        paragraphIndex: Int,
        end: Int,
        limit: Int = 120
    ): String {
        val current = paragraphs[paragraphIndex].orEmpty().drop(end)
        val next = paragraphs[paragraphIndex + 1].orEmpty()
        return (current + "\n" + next.take(40)).trim().take(limit)
    }

    private fun makeUnit(
        kind: String,
        roleHint: String,
        ranges: List<TextRange>,
        textPreview: String,
        cueBefore: String,
        cueAfter: String
    ): CandidateUnit {
        val first = ranges.first()
        val last = ranges.last()
        val digest = io.legado.app.utils.MD5Utils.md5Encode(textPreview).take(8)
        return CandidateUnit(
            unitId = "u_${first.paragraphIndex}_${first.start}_${last.paragraphIndex}_${last.end}_${kind}_$digest",
            kind = kind,
            roleHint = roleHint,
            ranges = ranges,
            textPreview = textPreview,
            cueBefore = cueBefore,
            cueAfter = cueAfter
        )
    }

    // ===================== 归因结果 → 分镜段 =====================

    private fun buildSegments(
        paragraphs: List<ContextParagraph>,
        units: List<CandidateUnit>,
        assignments: List<ModelUnitResult>
    ): List<StoryboardSegment> {
        val assignmentById = assignments.associateBy { it.unitId }
        val segments = mutableListOf<StoryboardSegment>()
        paragraphs.forEach { paragraph ->
            segments += buildSegmentsForParagraph(paragraph, units, assignmentById)
        }
        return segments.sortedWith(compareBy({ it.paragraphIndex }, { it.start }))
    }

    /** 段落必须被 segments 完整覆盖：unit 之间的空隙与段落尾部都补旁白段。 */
    private fun buildSegmentsForParagraph(
        paragraph: ContextParagraph,
        units: List<CandidateUnit>,
        assignmentById: Map<String, ModelUnitResult>
    ): List<StoryboardSegment> {
        val paragraphUnits = units
            .filter { unit -> unit.ranges.any { it.paragraphIndex == paragraph.paragraphIndex } }
            .sortedBy { it.ranges.first { range -> range.paragraphIndex == paragraph.paragraphIndex }.start }
        val segments = mutableListOf<StoryboardSegment>()
        var cursor = 0
        paragraphUnits.forEach { unit ->
            val range = unit.ranges.firstOrNull { it.paragraphIndex == paragraph.paragraphIndex }
                ?: return@forEach
            if (range.start > cursor) {
                addNarrationSegment(paragraph, cursor, range.start, segments)
            }
            val assignment = assignmentById[unit.unitId]
            val type = when (assignment?.roleType) {
                "character" -> StoryboardSegmentType.DIALOGUE
                "thought" -> StoryboardSegmentType.THOUGHT
                else -> StoryboardSegmentType.NARRATION
            }
            val identityType = when {
                type != StoryboardSegmentType.DIALOGUE && type != StoryboardSegmentType.THOUGHT ->
                    StoryboardSegment.IdentityType.NONE
                assignment?.status == "assigned" && assignment.characterId > 0L ->
                    StoryboardSegment.IdentityType.FORMAL_CHARACTER
                assignment?.status == "assigned" && assignment.castRoleId > 0L ->
                    StoryboardSegment.IdentityType.CAST_ROLE
                else -> assignment?.identityType ?: StoryboardSegment.IdentityType.NONE
            }
            segments += StoryboardSegment(
                type = type,
                paragraphIndex = paragraph.paragraphIndex,
                text = paragraph.text.substring(range.start, range.end.coerceAtMost(paragraph.text.length)),
                start = range.start,
                end = range.end,
                speakerName = assignment?.characterName
                    ?.trim()
                    ?.takeIf { type != StoryboardSegmentType.NARRATION && it.isNotBlank() },
                speakerGender = if (type == StoryboardSegmentType.NARRATION) {
                    "unknown"
                } else {
                    assignment?.speakerGender ?: "unknown"
                },
                characterId = assignment?.characterId?.takeIf {
                    it > 0L && type != StoryboardSegmentType.NARRATION
                } ?: 0L,
                castRoleId = assignment?.castRoleId?.takeIf {
                    it > 0L && type != StoryboardSegmentType.NARRATION
                } ?: 0L,
                identityType = identityType,
                nameType = assignment?.nameType
                    ?.takeIf { type != StoryboardSegmentType.NARRATION }
                    ?: StoryboardSegment.NameType.UNKNOWN,
                evidence = when {
                    assignment?.evidence?.isNotBlank() == true &&
                        type != StoryboardSegmentType.NARRATION -> "AI归因：${assignment.evidence}"
                    else -> "旁白"
                },
                confidence = assignment?.confidence ?: 0f
            )
            cursor = range.end
        }
        if (cursor < paragraph.text.length) {
            addNarrationSegment(paragraph, cursor, paragraph.text.length, segments)
        }
        return segments.filter { it.text.isNotBlank() }.mergeAdjacent()
    }

    private fun addNarrationSegment(
        paragraph: ContextParagraph,
        start: Int,
        end: Int,
        segments: MutableList<StoryboardSegment>
    ) {
        val text = paragraph.text.substring(start, end.coerceAtMost(paragraph.text.length))
        if (text.isBlank()) return
        segments += StoryboardSegment(
            type = StoryboardSegmentType.NARRATION,
            paragraphIndex = paragraph.paragraphIndex,
            text = text,
            start = start,
            end = end,
            evidence = "旁白"
        )
    }

    private fun List<StoryboardSegment>.mergeAdjacent(): List<StoryboardSegment> {
        val result = mutableListOf<StoryboardSegment>()
        forEach { segment ->
            val last = result.lastOrNull()
            if (last != null &&
                last.type == segment.type &&
                last.characterId == segment.characterId &&
                last.castRoleId == segment.castRoleId &&
                last.speakerName == segment.speakerName &&
                last.speakerGender == segment.speakerGender &&
                last.identityType == segment.identityType
            ) {
                result[result.lastIndex] = last.copy(
                    text = last.text + segment.text,
                    end = segment.end
                )
            } else {
                result += segment
            }
        }
        return result
    }
}
