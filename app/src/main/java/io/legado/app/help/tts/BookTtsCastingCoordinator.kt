package io.legado.app.help.tts

import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.help.ai.AiStructuredRequestTemplate
import io.legado.app.help.bdtts.BdSpeakerRecord
import io.legado.app.help.bdtts.BdSpeakerStore
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import splitties.init.appCtx

/**
 * 演播选角协调：
 * 1. syncCastRoles —— 把 1号AI 发现的陌生人收编进临时角色表，回填 segment 的角色 ID；
 * 2. assignMissingVoices —— 对还没有音色绑定的角色调用 2号AI 选音并落库；
 * 3. resolveSpeaker —— 播放路由：角色绑定 → 性别兜底 → 旁白 → 引擎默认。
 */
object BookTtsCastingCoordinator {

    private const val MIN_AUTO_CONFIDENCE = 0.7f
    private const val CASTING_ASSET = "tts_storyboard/casting.md"
    private const val SPEAKER_CACHE_TTL = 10_000L
    private val reservedNames = setOf("旁白", "心声", "对白男", "对白女", "待确认说话人")
    private val pronouns = setOf("我", "你", "他", "她", "它", "他们", "她们", "对方", "某人", "那人", "这人")

    @Volatile
    private var speakerCache: List<BdSpeakerRecord> = emptyList()

    @Volatile
    private var speakerCacheAt = 0L

    /** 发音人列表短缓存：播放路由高频调用，避免每段读一次语音包 JSON 文件。 */
    private fun cachedSpeakers(): List<BdSpeakerRecord> {
        val now = System.currentTimeMillis()
        if (now - speakerCacheAt > SPEAKER_CACHE_TTL || speakerCache.isEmpty()) {
            speakerCache = BdSpeakerStore.load()
            speakerCacheAt = now
        }
        return speakerCache
    }

    // ===================== 角色收编 =====================

    /**
     * 把分镜结果中的陌生人写入临时角色表，应用别名链接，
     * 返回回填了 castRoleId / characterId 的分镜。
     */
    fun syncCastRoles(
        workKey: String,
        chapterIndex: Int,
        storyboard: ChapterStoryboard
    ): ChapterStoryboard {
        applyIdentityLinks(workKey, storyboard.identityLinks)
        val segments = storyboard.scenes.flatMap { it.segments }
        val discovered = segments
            .filter {
                it.type == StoryboardSegmentType.DIALOGUE || it.type == StoryboardSegmentType.THOUGHT
            }
            .filter { it.characterId <= 0L && it.castRoleId <= 0L }
            .mapNotNull { segment ->
                val name = segment.speakerName?.trim().orEmpty()
                if (!isStableCastName(name)) return@mapNotNull null
                val normalized = normalizeIdentityName(name)
                val roleNames = existingRoleNames(workKey)
                if (normalized in roleNames) return@mapNotNull null
                DiscoveredOccurrence(
                    name = name,
                    gender = segment.speakerGender,
                    text = segment.text.trim().take(120),
                    identityState = segment.identityType,
                    evidence = segment.evidence
                )
            }
        val dao = appDb.bookRoleDao
        discovered.groupBy { normalizeIdentityName(it.name) }.forEach { (normalized, occurrences) ->
            val existing = dao.getCastRoles(workKey).firstOrNull {
                normalizeIdentityName(it.name) == normalized
            }
            val state = occurrences.firstOrNull()?.identityState
                ?: BookTtsCastRole.IdentityState.STABLE
            val identityState = if (state == StoryboardSegment.IdentityType.PENDING) {
                BookTtsCastRole.IdentityState.PENDING
            } else {
                BookTtsCastRole.IdentityState.STABLE
            }
            if (existing == null) {
                dao.insertCastRole(
                    BookTtsCastRole(
                        workKey = workKey,
                        name = occurrences.first().name,
                        aliasesJson = GSON.toJson(listOf(occurrences.first().name)),
                        gender = occurrences.map { it.gender }.firstOrNull {
                            it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
                        } ?: BookRole.Gender.UNKNOWN,
                        identityState = identityState,
                        occurrenceCount = occurrences.size,
                        firstChapterIndex = chapterIndex,
                        lastChapterIndex = chapterIndex,
                        samplesJson = GSON.toJson(occurrences.map { it.text }.distinct().take(3)),
                        evidence = occurrences.map { it.evidence }.filter { it.isNotBlank() }
                            .joinToString("；").take(200),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                dao.updateCastRole(
                    existing.copy(
                        occurrenceCount = existing.occurrenceCount + occurrences.size,
                        firstChapterIndex = existing.firstChapterIndex,
                        lastChapterIndex = chapterIndex,
                        identityState = if (existing.identityState == BookTtsCastRole.IdentityState.STABLE) {
                            existing.identityState
                        } else {
                            identityState
                        },
                        samplesJson = mergeSamples(existing.samplesJson, occurrences.map { it.text }),
                        gender = existing.gender.takeIf {
                            it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
                        } ?: occurrences.map { it.gender }.firstOrNull {
                            it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
                        } ?: BookRole.Gender.UNKNOWN,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        return relinkStoryboard(workKey, storyboard)
    }

    private fun existingRoleNames(workKey: String): Set<String> {
        val dao = appDb.bookRoleDao
        val names = buildSet {
            dao.getAllRoles(workKey).forEach { role ->
                add(normalizeIdentityName(role.name))
                parseAliases(role.aliasesJson).forEach { add(normalizeIdentityName(it)) }
            }
            dao.getCastRoles(workKey).forEach { role ->
                add(normalizeIdentityName(role.name))
                parseAliases(role.aliasesJson).forEach { add(normalizeIdentityName(it)) }
            }
        }
        return names
    }

    private fun relinkStoryboard(workKey: String, storyboard: ChapterStoryboard): ChapterStoryboard {
        val dao = appDb.bookRoleDao
        val roles = dao.getAllRoles(workKey)
        val castRoles = dao.getCastRoles(workKey)
        val characterIndex = buildMap<String, Pair<Long, String>> {
            roles.forEach { role ->
                val id = role.roleId
                put(normalizeIdentityName(role.name), id to role.name)
                parseAliases(role.aliasesJson).forEach { put(normalizeIdentityName(it), id to role.name) }
            }
        }
        val castIndex = buildMap<String, Long> {
            castRoles.forEach { role ->
                if (role.ignored) return@forEach
                put(normalizeIdentityName(role.name), role.castRoleId)
                parseAliases(role.aliasesJson).forEach { put(normalizeIdentityName(it), role.castRoleId) }
            }
        }
        val relinked = storyboard.scenes.map { scene ->
            scene.copy(
                segments = scene.segments.map { segment ->
                    if (segment.type != StoryboardSegmentType.DIALOGUE &&
                        segment.type != StoryboardSegmentType.THOUGHT
                    ) {
                        return@map segment
                    }
                    if (segment.characterId > 0L || segment.castRoleId > 0L) return@map segment
                    val name = segment.speakerName?.trim().orEmpty()
                    val normalized = normalizeIdentityName(name)
                    if (normalized.isBlank()) return@map segment
                    val character = characterIndex[normalized]
                    if (character != null) {
                        return@map segment.copy(
                            characterId = character.first,
                            speakerName = character.second,
                            identityType = StoryboardSegment.IdentityType.FORMAL_CHARACTER
                        )
                    }
                    val castRole = castIndex[normalized]
                    if (castRole != null) {
                        return@map segment.copy(
                            castRoleId = castRole,
                            identityType = StoryboardSegment.IdentityType.CAST_ROLE
                        )
                    }
                    segment
                }
            )
        }
        return storyboard.copy(scenes = relinked)
    }

    private fun applyIdentityLinks(workKey: String, links: List<StoryboardIdentityLink>) {
        if (links.isEmpty()) return
        val dao = appDb.bookRoleDao
        links.forEach { link ->
            val alias = link.aliasName.trim()
            if (!isStableCastName(alias)) return@forEach
            if (link.characterId > 0L) {
                val role = dao.getRole(link.characterId) ?: return@forEach
                if (normalizeIdentityName(alias) == normalizeIdentityName(role.name)) return@forEach
                val aliases = parseAliases(role.aliasesJson).toMutableList()
                if (aliases.none { normalizeIdentityName(it) == normalizeIdentityName(alias) }) {
                    aliases.add(alias)
                    dao.updateRole(role.copy(aliasesJson = GSON.toJson(aliases), updatedAt = System.currentTimeMillis()))
                }
                return@forEach
            }
            if (link.castRoleId > 0L) {
                val role = dao.getCastRole(link.castRoleId) ?: return@forEach
                if (role.ignored) return@forEach
                if (normalizeIdentityName(alias) == normalizeIdentityName(role.name)) return@forEach
                val aliases = parseAliases(role.aliasesJson).toMutableList()
                if (aliases.none { normalizeIdentityName(it) == normalizeIdentityName(alias) }) {
                    aliases.add(alias)
                    dao.updateCastRole(
                        role.copy(aliasesJson = GSON.toJson(aliases), updatedAt = System.currentTimeMillis())
                    )
                }
            }
        }
    }

    internal fun normalizeIdentityName(name: String): String {
        return name.trim()
            .replace(Regex("[\\s·・]"), "")
            .lowercase()
    }

    internal fun isStableCastName(name: String): Boolean {
        val value = name.trim()
        if (value.isBlank() || value.length > 30) return false
        if (value in reservedNames || value in pronouns) return false
        return true
    }

    private fun parseAliases(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            GSON.fromJsonArray<String>(json).getOrNull().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun mergeSamples(existingJson: String, added: List<String>): String {
        val existing = parseAliases(existingJson).toMutableList()
        added.filter { it.isNotBlank() }.forEach { sample ->
            if (existing.none { it == sample }) existing.add(sample)
        }
        return GSON.toJson(existing.takeLast(3))
    }

    private data class DiscoveredOccurrence(
        val name: String,
        val gender: String,
        val text: String,
        val identityState: String,
        val evidence: String
    )

    // ===================== 自动选音（2号AI） =====================

    data class CastingTarget(
        val targetType: String,
        val targetId: Long,
        val name: String,
        val gender: String,
        val occurrenceCount: Int,
        val samples: List<String>,
        val candidateSpeakerIds: List<String>
    )

    data class CastingAssignment(
        val targetType: String,
        val targetId: Long,
        val decision: String,
        val speakerId: String?,
        val confidence: Float,
        val reason: String?
    )

    /**
     * 找出还没有音色绑定的角色，交给 2号AI 挑发音人。
     * pending 状态的临时角色不自动选音，等转正后分配。
     * @return 本次成功写入的绑定数
     */
    suspend fun assignMissingVoices(workKey: String): Int {
        val (provider, modelId) = AiStoryboardConfig.requireModelTarget()
        val dao = appDb.bookRoleDao
        val speakers = cachedSpeakers()
        if (speakers.isEmpty()) return 0
        val speakerIds = speakers.map { it.id }.toSet()
        val bindings = dao.getBindings(workKey)
            .associateBy { it.targetType to it.targetId }

        val targets = mutableListOf<CastingTarget>()
        dao.getAllRoles(workKey).filter { it.enabled && it.name.isNotBlank() }.forEach { role ->
            val key = BookTtsVoiceBinding.TargetType.CHARACTER to role.roleId
            if (bindings[key] == null) {
                targets += role.toCastingTarget(speakerIds)
            }
        }
        dao.getCastRoles(workKey).forEach { role ->
            if (role.ignored || role.linkedRoleId > 0L) return@forEach
            if (role.identityState != BookTtsCastRole.IdentityState.STABLE) return@forEach
            val key = BookTtsVoiceBinding.TargetType.CAST_ROLE to role.castRoleId
            if (bindings[key] == null) {
                targets += role.toCastingTarget(speakerIds)
            }
        }
        val eligible = targets.filter { it.candidateSpeakerIds.isNotEmpty() }
        if (eligible.isEmpty()) return 0
        val assignments = requestAssignments(provider, modelId, speakers, eligible)
        val assignmentIndex = assignments
            .filter { it.decision == "assigned" && it.confidence >= MIN_AUTO_CONFIDENCE }
            .associateBy { it.targetType to it.targetId }
        var saved = 0
        val now = System.currentTimeMillis()
        eligible.forEach { target ->
            val assignment = assignmentIndex[target.targetType to target.targetId] ?: return@forEach
            val speakerId = assignment.speakerId?.takeIf { it in target.candidateSpeakerIds } ?: return@forEach
            dao.insertBinding(
                BookTtsVoiceBinding(
                    workKey = workKey,
                    targetType = target.targetType,
                    targetId = target.targetId,
                    speakerId = speakerId,
                    bindingMode = BookTtsVoiceBinding.BindingMode.AUTO,
                    updatedAt = now
                )
            )
            saved++
        }
        return saved
    }

    private fun BookRole.toCastingTarget(speakerIds: Set<String>): CastingTarget = CastingTarget(
        targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
        targetId = roleId,
        name = name,
        gender = gender,
        occurrenceCount = 0,
        samples = emptyList(),
        candidateSpeakerIds = speakerIds.toList()
    )

    private fun BookTtsCastRole.toCastingTarget(speakerIds: Set<String>): CastingTarget = CastingTarget(
        targetType = BookTtsVoiceBinding.TargetType.CAST_ROLE,
        targetId = castRoleId,
        name = name,
        gender = gender,
        occurrenceCount = occurrenceCount,
        samples = parseAliases(samplesJson).take(3),
        candidateSpeakerIds = speakerIds.toList()
    )

    private suspend fun requestAssignments(
        provider: io.legado.app.ui.main.ai.AiProviderConfig,
        modelId: String,
        speakers: List<BdSpeakerRecord>,
        targets: List<CastingTarget>
    ): List<CastingAssignment> {
        val payload = JsonObject().apply {
            add(
                "voices",
                GSON.toJsonTree(
                    speakers.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "desc" to it.desc.orEmpty(),
                            "gender" to it.gender,
                            "locale" to it.locale
                        )
                    }
                )
            )
            add("targets", GSON.toJsonTree(targets))
        }
        val result = try {
            AiChatService.generateStructuredText(
                provider = provider,
                model = modelId,
                systemPrompt = appCtx.assets.open(CASTING_ASSET).bufferedReader().use { it.readText() },
                userContent = payload.toString(),
                temperature = 0.0,
                requestTemplate = AiStructuredRequestTemplate.default
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("AI自动选音失败，已保留对白兜底\n${e.localizedMessage}")
            return emptyList()
        }
        return runCatching { parseAssignments(result) }.getOrElse { error ->
            AppLog.put("AI自动选音返回无效\n${error.localizedMessage}")
            emptyList()
        }
    }

    private fun parseAssignments(raw: String): List<CastingAssignment> {
        val normalized = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = normalized.indexOf('{')
        val end = normalized.lastIndexOf('}')
        check(start >= 0 && end >= start) { "AI 自动选音未返回 JSON 对象" }
        val root = JsonParser.parseString(normalized.substring(start, end + 1)).asJsonObject
        val assignmentsElement = root.getAsJsonArray("assignments")
        val output = mutableListOf<CastingAssignment>()
        assignmentsElement.forEach { element ->
            val obj = element.asJsonObject
            output += CastingAssignment(
                targetType = obj.get("targetType").asString,
                targetId = obj.get("targetId").asLong,
                decision = obj.get("decision").asString,
                speakerId = obj.get("speakerId")?.takeIf { !it.isJsonNull }?.asString,
                confidence = obj.get("confidence")?.takeIf { !it.isJsonNull }?.asFloat ?: 0f,
                reason = obj.get("reason")?.takeIf { !it.isJsonNull }?.asString
            )
        }
        return output
    }

    // ===================== 播放路由 =====================

    /**
     * 播放路由：角色绑定 → 临时角色绑定 → 对白性别兜底 → 旁白 → 默认发音人。
     */
    fun resolveSpeaker(
        workKey: String,
        segment: StoryboardSegment?,
        fallbackSpeaker: BdSpeakerRecord
    ): BdSpeakerRecord {
        val dao = appDb.bookRoleDao
        val bindings = dao.getBindings(workKey).associateBy { it.targetType to it.targetId }
        val isSpoken = segment?.type == StoryboardSegmentType.DIALOGUE ||
            segment?.type == StoryboardSegmentType.THOUGHT
        if (isSpoken && segment != null) {
            if (segment.characterId > 0L) {
                val binding = bindings[
                    BookTtsVoiceBinding.TargetType.CHARACTER to segment.characterId
                ]
                val speaker = binding?.let { speakerById(it.speakerId) }
                if (speaker != null) return speaker
            }
            if (segment.castRoleId > 0L) {
                val binding = bindings[
                    BookTtsVoiceBinding.TargetType.CAST_ROLE to segment.castRoleId
                ]
                val speaker = binding?.let { speakerById(it.speakerId) }
                if (speaker != null) return speaker
            }
            val genderTarget = when (segment.speakerGender) {
                BookRole.Gender.MALE -> BookTtsVoiceBinding.TargetType.DIALOGUE_MALE
                BookRole.Gender.FEMALE -> BookTtsVoiceBinding.TargetType.DIALOGUE_FEMALE
                else -> null
            }
            if (genderTarget != null) {
                val speakerId = when (genderTarget) {
                    BookTtsVoiceBinding.TargetType.DIALOGUE_MALE -> AiMultiVoiceConfig.dialogueMaleSpeakerId
                    else -> AiMultiVoiceConfig.dialogueFemaleSpeakerId
                }
                val speaker = speakerById(speakerId)
                if (speaker != null) return speaker
            }
        }
        if (!isSpoken) {
            val narrator = speakerById(AiMultiVoiceConfig.narratorSpeakerId)
            if (narrator != null) return narrator
        }
        return fallbackSpeaker
    }

    private fun speakerById(speakerId: String): BdSpeakerRecord? {
        if (speakerId.isBlank()) return null
        return cachedSpeakers().firstOrNull { it.id == speakerId }
    }
}
