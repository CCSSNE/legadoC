package io.legado.app.help.tts

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.constant.LogModule
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
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import splitties.init.appCtx

/**
 * 演播选角协调：
 * 1. syncCastRoles —— 把 1号AI 发现的陌生人收编进临时角色表，消费段上声明的角色并归，
 *    回填 segment 的角色 ID；autoCreateRoles=false 的书只回链不收编；
 * 2. assignMissingVoices —— 对还没有音色绑定的角色调用 2号AI 选音并落库；
 * 3. resolveSpeaker —— 播放路由：角色绑定 → 临时角色绑定 → 性别兜底（段性别，缺省回查角色档案）→ 旁白 → 引擎默认。
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
     * 把分镜结果中的陌生人收编进临时角色表，应用别名链接与段上声明的角色并归，
     * 返回回填了 castRoleId / characterId 的分镜。
     * autoCreateRoles=false 的书只做并归与回链，不再收编新角色。
     */
    fun syncCastRoles(
        workKey: String,
        chapterIndex: Int,
        storyboard: ChapterStoryboard
    ): ChapterStoryboard {
        applyIdentityLinks(workKey, storyboard.identityLinks)
        val segments = storyboard.scenes.flatMap { it.segments }
        val idRemap = mergeDeclaredCastRoles(workKey, segments)
        if (BookTtsAutomationConfig.get(workKey).autoCreateRoles) {
            adoptDiscoveredSpeakers(workKey, chapterIndex, segments)
        }
        val prepared = storyboard.remapCastRoleIds(idRemap)
        return relinkStoryboard(workKey, prepared)
    }

    /** 消费分镜段声明的 mergeCastRoleIds：把误建的旧临时角色并归进规范角色。返回 旧ID→规范ID 映射。 */
    private fun mergeDeclaredCastRoles(
        workKey: String,
        segments: List<StoryboardSegment>
    ): Map<Long, Long> {
        val declared = segments
            .filter {
                it.type == StoryboardSegmentType.DIALOGUE || it.type == StoryboardSegmentType.THOUGHT
            }
            .flatMap { segment ->
                segment.mergeCastRoleIds.filter { it > 0L }.map { it to segment }
            }
        if (declared.isEmpty()) return emptyMap()
        val dao = appDb.bookRoleDao
        val remap = LinkedHashMap<Long, Long>()
        declared.forEach { (staleId, segment) ->
            if (remap.containsKey(staleId)) return@forEach
            val loser = dao.getCastRole(staleId) ?: return@forEach
            val target = resolveMergeTarget(workKey, segment, staleId) ?: return@forEach
            if (target.castRoleId == loser.castRoleId) return@forEach
            mergeCastRole(workKey, target, loser)
            remap[staleId] = target.castRoleId
            AppLog.put("AI分镜临时角色并归：${loser.name} → ${target.name}", module = LogModule.AI_CAST)
        }
        return remap
    }

    private fun resolveMergeTarget(
        workKey: String,
        segment: StoryboardSegment,
        staleId: Long
    ): BookTtsCastRole? {
        val dao = appDb.bookRoleDao
        if (segment.castRoleId > 0L && segment.castRoleId != staleId) {
            dao.getCastRole(segment.castRoleId)?.let { return it }
        }
        val normalized = normalizeIdentityName(segment.speakerName.orEmpty())
        if (normalized.isBlank()) return null
        return dao.getCastRoles(workKey).firstOrNull { role ->
            !role.ignored && role.castRoleId != staleId && (
                normalizeIdentityName(role.name) == normalized ||
                    parseAliases(role.aliasesJson).any { normalizeIdentityName(it) == normalized }
                )
        }
    }

    /** 把 loser 角色的别名/样本/计数/绑定并入 target 后删除 loser。 */
    private fun mergeCastRole(workKey: String, target: BookTtsCastRole, loser: BookTtsCastRole) {
        val dao = appDb.bookRoleDao
        val mergedAliases = buildList {
            addAll(parseAliases(target.aliasesJson))
            add(loser.name)
            addAll(parseAliases(loser.aliasesJson))
        }.filter { it.isNotBlank() && normalizeIdentityName(it) != normalizeIdentityName(target.name) }
            .distinctBy { normalizeIdentityName(it) }
        val mergedSamples = (parseAliases(target.samplesJson) + parseAliases(loser.samplesJson))
            .filter { it.isNotBlank() }.distinct().takeLast(3)
        val now = System.currentTimeMillis()
        val loserBinding = dao.getBinding(
            workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, loser.castRoleId
        )
        dao.updateCastRole(
            target.copy(
                aliasesJson = GSON.toJson(mergedAliases),
                samplesJson = GSON.toJson(mergedSamples),
                occurrenceCount = target.occurrenceCount + loser.occurrenceCount,
                firstChapterIndex = if (target.firstChapterIndex < 0) {
                    loser.firstChapterIndex
                } else {
                    minOf(target.firstChapterIndex, loser.firstChapterIndex)
                },
                lastChapterIndex = maxOf(target.lastChapterIndex, loser.lastChapterIndex),
                gender = target.gender.takeIf {
                    it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
                } ?: loser.gender,
                identityState = if (loser.identityState == BookTtsCastRole.IdentityState.STABLE) {
                    BookTtsCastRole.IdentityState.STABLE
                } else {
                    target.identityState
                },
                ignored = target.ignored && loser.ignored,
                linkedRoleId = if (target.linkedRoleId == 0L) loser.linkedRoleId else target.linkedRoleId,
                updatedAt = now
            )
        )
        if (loserBinding != null &&
            dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, target.castRoleId) == null
        ) {
            dao.insertBinding(loserBinding.copy(targetId = target.castRoleId, updatedAt = now))
        }
        dao.deleteBinding(workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, loser.castRoleId)
        dao.deleteCastRole(loser)
    }

    private fun ChapterStoryboard.remapCastRoleIds(remap: Map<Long, Long>): ChapterStoryboard {
        if (remap.isEmpty()) return this
        return copy(
            scenes = scenes.map { scene ->
                scene.copy(
                    segments = scene.segments.map { segment ->
                        val mapped = segment.castRoleId.takeIf { it > 0L }?.let { remap[it] }
                        if (mapped != null && mapped != segment.castRoleId) {
                            segment.copy(castRoleId = mapped)
                        } else {
                            segment
                        }
                    }
                )
            }
        )
    }

    /**
     * 收编本分镜发现的说话人：
     * 带临时角色 id 的段走按 id 更新（pending 角色的统计/状态也能推进）；
     * 未绑定 id 的陌生说话人按名收编，guest（一次性泛称）不入池；
     * 命中正式角色或被忽略临时角色的名称/别名一律拦截；
     * 同一次收编按最终角色聚合（同角色可能同时出现带 id 与仅名字的段），每角色只更新一次；
     * 更新分支对同章重复收编（重读/重播）不重复计数。
     */
    private fun adoptDiscoveredSpeakers(
        workKey: String,
        chapterIndex: Int,
        segments: List<StoryboardSegment>
    ) {
        val blocked = blockedIdentityNames(workKey)
        val discovered = segments
            .filter {
                it.type == StoryboardSegmentType.DIALOGUE || it.type == StoryboardSegmentType.THOUGHT
            }
            .mapNotNull { segment ->
                val name = segment.speakerName?.trim().orEmpty()
                if (!isStableCastName(name)) return@mapNotNull null
                val normalized = normalizeIdentityName(name)
                if (normalized in blocked) return@mapNotNull null
                when {
                    segment.characterId > 0L -> null
                    segment.castRoleId > 0L -> DiscoveredOccurrence(
                        name = name,
                        gender = segment.speakerGender,
                        text = segment.text.trim().take(120),
                        identityState = segment.identityType,
                        evidence = segment.evidence,
                        knownCastRoleId = segment.castRoleId
                    )
                    segment.identityType != StoryboardSegment.IdentityType.GUEST -> DiscoveredOccurrence(
                        name = name,
                        gender = segment.speakerGender,
                        text = segment.text.trim().take(120),
                        identityState = segment.identityType,
                        evidence = segment.evidence,
                        knownCastRoleId = 0L
                    )
                    else -> null
                }
            }
        if (discovered.isEmpty()) return
        val dao = appDb.bookRoleDao
        // 先按 id/名字分组解析出最终角色，再按角色聚合，保证每角色一次更新。
        val aggregated = linkedMapOf<String, MutableList<DiscoveredOccurrence>>()
        discovered.groupBy { occurrence ->
            occurrence.knownCastRoleId.takeIf { it > 0L }?.let { "id:$it" }
                ?: "name:${normalizeIdentityName(occurrence.name)}"
        }.forEach { (_, occurrences) ->
            val existing = resolveExistingCastRole(workKey, occurrences.first())
            val key = existing?.castRoleId?.let { "role:$it" }
                ?: "new:${normalizeIdentityName(occurrences.first().name)}"
            aggregated.getOrPut(key) { mutableListOf() }.addAll(occurrences)
        }
        aggregated.forEach { (_, occurrences) ->
            val first = occurrences.first()
            val existing = resolveExistingCastRole(workKey, first)
            // 有任一 STABLE_CANDIDATE/CAST_ROLE 证据即视为稳定；全部 PENDING 才保持观察。
            val identityState = if (
                occurrences.any {
                    it.identityState == StoryboardSegment.IdentityType.STABLE_CANDIDATE ||
                        it.identityState == StoryboardSegment.IdentityType.CAST_ROLE
                }
            ) {
                BookTtsCastRole.IdentityState.STABLE
            } else {
                BookTtsCastRole.IdentityState.PENDING
            }
            if (existing == null) {
                // 带 id 的段必然指向在册角色；找不到说明角色已删除，不重建。
                if (first.knownCastRoleId > 0L) return@forEach
                dao.insertCastRole(
                    BookTtsCastRole(
                        workKey = workKey,
                        name = first.name,
                        aliasesJson = "[]",
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
                // 同章重复收编（重读/重播同章）不重复累计，避免 occurrenceCount 双计。
                val sameChapterSynced = existing.lastChapterIndex == chapterIndex
                dao.updateCastRole(
                    existing.copy(
                        occurrenceCount = existing.occurrenceCount +
                            if (sameChapterSynced) 0 else occurrences.size,
                        firstChapterIndex = existing.firstChapterIndex,
                        lastChapterIndex = maxOf(existing.lastChapterIndex, chapterIndex),
                        identityState = if (existing.identityState == BookTtsCastRole.IdentityState.STABLE) {
                            existing.identityState
                        } else {
                            identityState
                        },
                        samplesJson = if (sameChapterSynced) {
                            existing.samplesJson
                        } else {
                            mergeSamples(existing.samplesJson, occurrences.map { it.text })
                        },
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
    }

    private fun resolveExistingCastRole(
        workKey: String,
        occurrence: DiscoveredOccurrence
    ): BookTtsCastRole? {
        val dao = appDb.bookRoleDao
        occurrence.knownCastRoleId.takeIf { it > 0L }?.let { return dao.getCastRole(it) }
        val normalized = normalizeIdentityName(occurrence.name)
        return dao.getCastRoles(workKey).firstOrNull { role ->
            !role.ignored && (
                normalizeIdentityName(role.name) == normalized ||
                    parseAliases(role.aliasesJson).any { normalizeIdentityName(it) == normalized }
                )
        }
    }

    /** 收编黑名单：全部正式角色（含停用）与被忽略临时角色的名称和别名。 */
    private fun blockedIdentityNames(workKey: String): Set<String> {
        val dao = appDb.bookRoleDao
        val names = buildSet {
            dao.getAllRoles(workKey).forEach { role ->
                add(normalizeIdentityName(role.name))
                parseAliases(role.aliasesJson).forEach { add(normalizeIdentityName(it)) }
            }
            dao.getCastRoles(workKey).filter { it.ignored }.forEach { role ->
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

    /** 与上游阅读 NG 同构：去边界标点、折叠内部空白为单空格、小写。 */
    internal fun normalizeIdentityName(name: String): String {
        val boundaryPunctuation = setOf(
            '“', '”', '‘', '’', '「', '」', '『', '』', ':', '：', '，', ',', '。', '.', '！', '!', '？', '?'
        )
        return name.trim { it.isWhitespace() || it in boundaryPunctuation }
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    /** 与上游阅读 NG 同构：长度 2..16、排除等人/未知/待确认、必须含字母。 */
    internal fun isStableCastName(name: String): Boolean {
        val value = name.trim().trim('“', '”', '‘', '’', '「', '」', '『', '』', ':', '：')
        if (value.length !in 2..16 || value in reservedNames || value in pronouns) return false
        if (value.endsWith("等人") || value.contains("未知") || value.contains("待确认")) return false
        return value.any { it.isLetter() }
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
        val evidence: String,
        /** 段上已绑定的临时角色 id；0 表示未绑定、需要按名解析。 */
        val knownCastRoleId: Long = 0L
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
                            "gender" to BdSpeakerRecord.genderTag(it.gender),
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
            AppLog.put("AI自动选音失败，已保留对白兜底\n${e.localizedMessage}", module = LogModule.AI_CAST)
            return emptyList()
        }
        return runCatching { parseAssignments(result) }.getOrElse { error ->
            AppLog.put("AI自动选音返回无效\n${error.localizedMessage}", module = LogModule.AI_CAST)
            emptyList()
        }
    }

    private fun parseAssignments(raw: String): List<CastingAssignment> {
        val json = raw.trim().extractJsonObjectCandidate()
        check(json.isNotBlank()) { "AI 自动选音未返回 JSON 对象" }
        val root = JsonParser.parseString(json).asJsonObject
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
     * 性别兜底优先取段的 AI 性别，AI 未给性别时回查角色档案性别。
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
            resolveGenderSpeaker(segment)?.let { return it }
        }
        if (!isSpoken) {
            val narrator = speakerById(AiMultiVoiceConfig.narratorSpeakerId)
            if (narrator != null) return narrator
        }
        return fallbackSpeaker
    }

    /** 对白性别兜底发音人：段性别优先，缺省回查角色档案性别，再缺省返回 null 落到默认发音人。 */
    private fun resolveGenderSpeaker(segment: StoryboardSegment): BdSpeakerRecord? {
        val gender = segment.speakerGender.takeIf {
            it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
        } ?: run {
            val dao = appDb.bookRoleDao
            when {
                segment.characterId > 0L -> dao.getRole(segment.characterId)?.gender
                segment.castRoleId > 0L -> dao.getCastRole(segment.castRoleId)?.gender
                else -> null
            }?.takeIf { it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE }
        } ?: return null
        val speakerId = if (gender == BookRole.Gender.MALE) {
            AiMultiVoiceConfig.dialogueMaleSpeakerId
        } else {
            AiMultiVoiceConfig.dialogueFemaleSpeakerId
        }
        return speakerById(speakerId)
    }

    private fun speakerById(speakerId: String): BdSpeakerRecord? {
        if (speakerId.isBlank()) return null
        return cachedSpeakers().firstOrNull { it.id == speakerId }
    }
}
