package io.legado.app.help.tts

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * AI 多角色选角路由（对齐 legado_NG ReadAloudTtsRouter）。
 * 与 NG 的差异：
 * - 实体映射到本项目：BookRole / BookTtsCastRole / BookTtsVoiceBinding（绑定列 speakerId
 *   语义为"绑定引擎内的音色 id"，engineId 空串 = 内置语音包引擎外观）；
 * - 选角门控沿用 [AiMultiVoiceConfig.enabled]（NG 为 AppConfig.readAloudMultiRole）；
 * - 全局兜底（旁白/对白男/对白女）优先读 NG 键（AppConfig.multiRoleTtsEngineId 等），
 *   未配置时回退旧版 AiMultiVoiceConfig 偏好并归属内置语音包引擎外观；
 * - 本项目分镜为基础归因版（StoryboardScene 无场景音色指派），场景覆盖路由不做；
 * - 无 INHERIT 绑定模式（本项目仅 AUTO/MANUAL）。
 */
class ReadAloudTtsRouter private constructor(
    private val narratorBinding: RouteBinding?,
    private val characterBindings: Map<Long, RouteBinding>,
    private val castRoleBindings: Map<Long, RouteBinding>,
    private val dialogueMaleBinding: RouteBinding?,
    private val dialogueFemaleBinding: RouteBinding?,
    private val dialogueDefaultBinding: RouteBinding?,
    private val characterNameIndex: Map<String, Long>,
    private val characterGenderIndex: Map<Long, String>,
    private val castRoleNameIndex: Map<String, Long>,
    private val castRoleGenderIndex: Map<Long, String>,
    private val knownCharacterIds: Set<Long>,
    private val knownCastRoleIds: Set<Long>,
    private val unavailableCharacterBindings: Set<Long>,
    private val unavailableCastRoleBindings: Set<Long>
) {

    fun route(
        segment: StoryboardSegment?,
        fallbackEngine: TtsEngineSetting
    ): Route {
        val characterId = segment?.characterTargetId()
        val castRoleId = segment?.castRoleTargetId(characterId)
        val characterBinding = characterId?.let { characterBindings[it] }
        val castRoleBinding = castRoleId?.let { castRoleBindings[it] }
        val fallbackGender = segment?.dialogueFallbackGender(characterId, castRoleId)
        val dialogueFallbackBinding = fallbackGender?.let(::genderBinding)
        val isSpokenRole = segment?.type == StoryboardSegmentType.DIALOGUE ||
            segment?.type == StoryboardSegmentType.THOUGHT
        val defaultDialogueBinding = dialogueDefaultBinding.takeIf { isSpokenRole }
        val binding = characterBinding ?: castRoleBinding ?: dialogueFallbackBinding ?:
            defaultDialogueBinding ?:
            narratorBinding.takeUnless { isSpokenRole }
        val engine = binding?.engine?.takeIf { it.type == TtsEngineType.SCRIPT && it.enabled }
            ?: fallbackEngine
        val voiceId = binding?.voiceId
            ?.takeIf { binding.engine.id == engine.id }
            ?.takeIf { voiceId -> engine.enabledVoices().any { it.id == voiceId } }
            ?: engine.activeVoice()?.id
        return Route(
            engine = engine,
            voiceId = voiceId,
            kind = when {
                characterBinding != null -> RouteKind.CHARACTER
                castRoleBinding != null -> RouteKind.CAST_ROLE
                dialogueFallbackBinding != null -> RouteKind.DIALOGUE_FALLBACK
                isSpokenRole -> RouteKind.DIALOGUE_FALLBACK
                narratorBinding != null -> RouteKind.NARRATOR
                else -> RouteKind.ENGINE_DEFAULT
            },
            fallbackUsed = isSpokenRole && characterBinding == null && castRoleBinding == null,
            bindingUnavailable = characterId in unavailableCharacterBindings ||
                castRoleId in unavailableCastRoleBindings,
            bindingMode = characterBinding?.bindingMode ?: castRoleBinding?.bindingMode,
            warnOnFailure = isSpokenRole && binding != null &&
                binding.engine.id == dialogueDefaultBinding?.engine?.id
        )
    }

    fun fallbackRoutes(
        segment: StoryboardSegment?,
        fallbackEngine: TtsEngineSetting,
        failedRoute: Route?
    ): List<Route> {
        val characterId = segment?.characterTargetId()
        val castRoleId = segment?.castRoleTargetId(characterId)
        val fallbackGender = segment?.dialogueFallbackGender(characterId, castRoleId)
        val isSpokenRole = segment?.type == StoryboardSegmentType.DIALOGUE ||
            segment?.type == StoryboardSegmentType.THOUGHT
        val candidates = buildList {
            fallbackGender?.let(::genderBinding)?.let { binding ->
                add(binding.toRoute(RouteKind.DIALOGUE_FALLBACK, fallbackUsed = true))
            }
            narratorBinding?.let { binding ->
                add(binding.toRoute(RouteKind.NARRATOR, fallbackUsed = true))
            }
            add(
                Route(
                    engine = fallbackEngine,
                    voiceId = fallbackEngine.activeVoice()?.id,
                    kind = if (isSpokenRole) RouteKind.DIALOGUE_FALLBACK else RouteKind.ENGINE_DEFAULT,
                    fallbackUsed = true
                )
            )
        }
        return candidates
            .distinctBy { it.engine.id to it.voiceId }
            .filterNot { route ->
                failedRoute != null && route.engine.id == failedRoute.engine.id &&
                    route.voiceId == failedRoute.voiceId
            }
    }

    private fun RouteBinding.toRoute(kind: RouteKind, fallbackUsed: Boolean): Route {
        return Route(
            engine = engine,
            voiceId = voiceId ?: engine.activeVoice()?.id,
            kind = kind,
            fallbackUsed = fallbackUsed,
            bindingMode = bindingMode
        )
    }

    data class Route(
        val engine: TtsEngineSetting,
        val voiceId: String?,
        val kind: RouteKind = RouteKind.ENGINE_DEFAULT,
        val fallbackUsed: Boolean = false,
        val bindingUnavailable: Boolean = false,
        val bindingMode: String? = null,
        val warnOnFailure: Boolean = false
    ) {
        val isAssignedRole: Boolean
            get() = !fallbackUsed && (kind == RouteKind.CHARACTER || kind == RouteKind.CAST_ROLE)
    }

    enum class RouteKind {
        CHARACTER,
        CAST_ROLE,
        DIALOGUE_FALLBACK,
        NARRATOR,
        ENGINE_DEFAULT
    }

    private fun StoryboardSegment.characterTargetId(): Long? {
        if (type != StoryboardSegmentType.DIALOGUE && type != StoryboardSegmentType.THOUGHT) {
            return null
        }
        characterId.takeIf { it > 0L && it in knownCharacterIds }?.let { return it }
        return speakerName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(BookTtsCastingCoordinator::normalizeIdentityName)
            ?.let { characterNameIndex[it] }
    }

    private fun StoryboardSegment.castRoleTargetId(characterId: Long?): Long? {
        if (characterId != null || (type != StoryboardSegmentType.DIALOGUE && type != StoryboardSegmentType.THOUGHT)) {
            return null
        }
        castRoleId.takeIf { it > 0L && it in knownCastRoleIds }?.let { return it }
        return speakerName?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(BookTtsCastingCoordinator::normalizeIdentityName)
            ?.let { castRoleNameIndex[it] }
    }

    private fun StoryboardSegment.dialogueFallbackGender(characterId: Long?, castRoleId: Long?): String? {
        if (type != StoryboardSegmentType.DIALOGUE && type != StoryboardSegmentType.THOUGHT) {
            return null
        }
        return speakerGender.takeIf {
            it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
        } ?: characterId?.let { characterGenderIndex[it] }
            ?: castRoleId?.let { castRoleGenderIndex[it] }
    }

    private fun genderBinding(gender: String): RouteBinding? {
        return when (gender) {
            BookRole.Gender.MALE -> dialogueMaleBinding
            BookRole.Gender.FEMALE -> dialogueFemaleBinding
            else -> null
        }
    }

    internal data class RouteBinding(
        val engine: TtsEngineSetting,
        val voiceId: String?,
        val bindingMode: String? = null
    )

    internal data class GlobalBindings(
        val narrator: RouteBinding?,
        val dialogueMale: RouteBinding?,
        val dialogueFemale: RouteBinding?,
        val dialogueDefault: RouteBinding? = null
    )

    companion object {
        fun createForCurrentBook(): ReadAloudTtsRouter? {
            if (!AiMultiVoiceConfig.enabled) {
                return null
            }
            val book = ReadBook.book ?: return null
            return create(book)
        }

        internal fun globalScriptNarratorEngine(): TtsEngineSetting? {
            return resolveGlobalBindings(
                multiRoleEngineId = AppConfig.multiRoleTtsEngineId,
                narratorEngineId = AppConfig.defaultNarratorTtsEngineId,
                narratorVoiceId = AppConfig.defaultNarratorTtsVoiceId,
                dialogueMaleVoiceId = AppConfig.defaultDialogueMaleTtsVoiceId,
                dialogueFemaleVoiceId = AppConfig.defaultDialogueFemaleTtsVoiceId,
                engineResolver = TtsEngineStore::engineOrVoiceDirectory
            ).narrator?.engine
        }

        fun create(book: Book): ReadAloudTtsRouter? {
            val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
            val dao = appDb.bookRoleDao
            val characters = dao.getRoles(workKey).filter { it.name.isNotBlank() }
            val castRoles = dao.getCastRoles(workKey)
                .filter { !it.ignored && it.identityState == BookTtsCastRole.IdentityState.STABLE }
            val bindings = dao.getBindings(workKey)
            val multiRoleEngineId = AppConfig.multiRoleTtsEngineId
            val engineResolver: (String?) -> TtsEngineSetting? =
                TtsEngineStore::engineOrVoiceDirectory
            val currentEngineBindings = bindings
                .filter { it.targetType != BookTtsVoiceBinding.TargetType.NARRATOR }
                .filter { isBookBindingCompatible(it, multiRoleEngineId) }
            val bindingMap = currentEngineBindings
                .mapNotNull { binding ->
                    binding.toRouteBinding(engineResolver)
                        ?.let { (binding.targetType to binding.targetId) to it }
                }.toMap()
            val unavailableBindingKeys = currentEngineBindings
                .filter { isBindingUnavailable(it, engineResolver) }
                .map { it.targetType to it.targetId }
                .toSet()
            val narratorBinding = bindings.asSequence()
                .filter { it.targetType == BookTtsVoiceBinding.TargetType.NARRATOR }
                .sortedByDescending { it.updatedAt }
                .mapNotNull { it.toRouteBinding(engineResolver) }
                .firstOrNull()
            val globalBindings = resolveGlobalBindings(
                multiRoleEngineId = multiRoleEngineId,
                narratorEngineId = AppConfig.defaultNarratorTtsEngineId,
                narratorVoiceId = AppConfig.defaultNarratorTtsVoiceId,
                dialogueMaleVoiceId = AppConfig.defaultDialogueMaleTtsVoiceId,
                dialogueFemaleVoiceId = AppConfig.defaultDialogueFemaleTtsVoiceId,
                engineResolver = engineResolver
            )
            val characterIds = characters.map { it.roleId }.toSet()
            return createResolved(
                narratorBinding = narratorBinding,
                characterBindings = bindingMap
                    .filterKeys {
                        it.first == BookTtsVoiceBinding.TargetType.CHARACTER && it.second in characterIds
                    }
                    .mapKeys { it.key.second },
                castRoleBindings = bindingMap
                    .filterKeys { key ->
                        key.first == BookTtsVoiceBinding.TargetType.CAST_ROLE &&
                            castRoles.any { it.castRoleId == key.second }
                    }
                    .mapKeys { it.key.second },
                dialogueMaleBinding = bindingMap[BookTtsVoiceBinding.TargetType.DIALOGUE_MALE to 0L],
                dialogueFemaleBinding = bindingMap[BookTtsVoiceBinding.TargetType.DIALOGUE_FEMALE to 0L],
                characterNameIndex = characters.flatMap { character ->
                    buildList {
                        add(character.name)
                        GSON.fromJsonObject<List<String>>(character.aliasesJson)
                            .getOrNull().orEmpty()
                            .forEach { add(it) }
                    }
                        .filter { it.isNotBlank() }
                        .map {
                            BookTtsCastingCoordinator.normalizeIdentityName(it) to character.roleId
                        }
                }.plus(
                    castRoles.flatMap { role ->
                        val linkedRoleId = role.linkedRoleId.takeIf { it > 0L }
                            ?: return@flatMap emptyList()
                        buildList {
                            add(role.name)
                            GSON.fromJsonObject<List<String>>(role.aliasesJson)
                                .getOrNull().orEmpty().forEach(::add)
                        }.filter { it.isNotBlank() }.map {
                            BookTtsCastingCoordinator.normalizeIdentityName(it) to linkedRoleId
                        }
                    }
                ).toMap(),
                characterGenderIndex = characters.mapNotNull { character ->
                    character.gender
                        .takeIf {
                            it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
                        }
                        ?.let { character.roleId to it }
                }.toMap(),
                castRoleNameIndex = castRoles
                    .filter { it.linkedRoleId <= 0L }
                    .flatMap { role ->
                        buildList {
                            add(role.name)
                            GSON.fromJsonObject<List<String>>(role.aliasesJson)
                                .getOrNull().orEmpty().forEach(::add)
                        }.filter { it.isNotBlank() }.map {
                            BookTtsCastingCoordinator.normalizeIdentityName(it) to role.castRoleId
                        }
                    }.toMap(),
                castRoleGenderIndex = castRoles.mapNotNull { role ->
                    role.gender
                        .takeIf {
                            it == BookRole.Gender.MALE || it == BookRole.Gender.FEMALE
                        }
                        ?.let { role.castRoleId to it }
                }.toMap(),
                knownCharacterIds = characterIds,
                knownCastRoleIds = castRoles.mapTo(mutableSetOf()) { it.castRoleId },
                unavailableCharacterBindings = unavailableBindingKeys
                    .filter { it.first == BookTtsVoiceBinding.TargetType.CHARACTER }
                    .mapTo(mutableSetOf()) { it.second },
                unavailableCastRoleBindings = unavailableBindingKeys
                    .filter { it.first == BookTtsVoiceBinding.TargetType.CAST_ROLE }
                    .mapTo(mutableSetOf()) { it.second },
                globalBindings = globalBindings
            )
        }

        internal fun resolveGlobalBindings(
            multiRoleEngineId: String?,
            narratorEngineId: String?,
            narratorVoiceId: String?,
            dialogueMaleVoiceId: String?,
            dialogueFemaleVoiceId: String?,
            engineResolver: (String?) -> TtsEngineSetting?
        ): GlobalBindings {
            val facadeEngine = engineResolver(TtsEngineStore.VOICE_DIRECTORY_ID)
            val narratorBinding = resolveGlobalBinding(
                engineId = narratorEngineId,
                voiceId = narratorVoiceId,
                legacyVoiceId = AiMultiVoiceConfig.narratorSpeakerId.takeIf { it.isNotBlank() },
                legacyEngine = facadeEngine,
                engineResolver = engineResolver
            )
            val hasLegacyDialogue = AiMultiVoiceConfig.dialogueMaleSpeakerId.isNotBlank() ||
                AiMultiVoiceConfig.dialogueFemaleSpeakerId.isNotBlank()
            val dialogueEngine = engineResolver(multiRoleEngineId)
                ?.takeIf { it.enabled && it.type == TtsEngineType.SCRIPT }
                ?: facadeEngine?.takeIf { hasLegacyDialogue }
            return GlobalBindings(
                narrator = narratorBinding,
                dialogueMale = dialogueEngine?.toGlobalRouteBinding(
                    dialogueMaleVoiceId?.takeIf { it.isNotBlank() }
                        ?: AiMultiVoiceConfig.dialogueMaleSpeakerId.takeIf { it.isNotBlank() }
                ),
                dialogueFemale = dialogueEngine?.toGlobalRouteBinding(
                    dialogueFemaleVoiceId?.takeIf { it.isNotBlank() }
                        ?: AiMultiVoiceConfig.dialogueFemaleSpeakerId.takeIf { it.isNotBlank() }
                ),
                dialogueDefault = dialogueEngine?.toDialogueDefaultRouteBinding()
            )
        }

        private fun resolveGlobalBinding(
            engineId: String?,
            voiceId: String?,
            legacyVoiceId: String?,
            legacyEngine: TtsEngineSetting?,
            engineResolver: (String?) -> TtsEngineSetting?
        ): RouteBinding? {
            if (!engineId.isNullOrBlank()) {
                return engineResolver(engineId)
                    ?.takeIf { it.enabled && it.type == TtsEngineType.SCRIPT }
                    ?.toGlobalRouteBinding(voiceId)
            }
            return legacyEngine?.toGlobalRouteBinding(legacyVoiceId)
        }

        internal fun isBookBindingCompatible(
            binding: BookTtsVoiceBinding,
            multiRoleEngineId: String?
        ): Boolean {
            // multiRoleEngineId 未配置时沿用旧版行为：全部书级绑定生效。
            return binding.targetType == BookTtsVoiceBinding.TargetType.NARRATOR ||
                multiRoleEngineId.isNullOrBlank() ||
                binding.engineId == multiRoleEngineId
        }

        internal fun createResolved(
            narratorBinding: RouteBinding?,
            characterBindings: Map<Long, RouteBinding>,
            castRoleBindings: Map<Long, RouteBinding> = emptyMap(),
            dialogueMaleBinding: RouteBinding?,
            dialogueFemaleBinding: RouteBinding?,
            dialogueDefaultBinding: RouteBinding? = null,
            characterNameIndex: Map<String, Long>,
            characterGenderIndex: Map<Long, String>,
            castRoleNameIndex: Map<String, Long> = emptyMap(),
            castRoleGenderIndex: Map<Long, String> = emptyMap(),
            knownCharacterIds: Set<Long> = characterNameIndex.values.toSet() +
                characterGenderIndex.keys + characterBindings.keys,
            knownCastRoleIds: Set<Long> = castRoleNameIndex.values.toSet() +
                castRoleGenderIndex.keys + castRoleBindings.keys,
            unavailableCharacterBindings: Set<Long> = emptySet(),
            unavailableCastRoleBindings: Set<Long> = emptySet(),
            globalBindings: GlobalBindings = GlobalBindings(null, null, null)
        ): ReadAloudTtsRouter? {
            val effectiveNarratorBinding = narratorBinding ?: globalBindings.narrator
            val effectiveDialogueMaleBinding = dialogueMaleBinding ?: globalBindings.dialogueMale
            val effectiveDialogueFemaleBinding = dialogueFemaleBinding ?: globalBindings.dialogueFemale
            val effectiveDialogueDefaultBinding = dialogueDefaultBinding ?: globalBindings.dialogueDefault
            if (
                effectiveNarratorBinding == null &&
                characterBindings.isEmpty() &&
                castRoleBindings.isEmpty() &&
                effectiveDialogueMaleBinding == null &&
                effectiveDialogueFemaleBinding == null &&
                effectiveDialogueDefaultBinding == null &&
                characterNameIndex.isEmpty()
            ) {
                return null
            }
            return ReadAloudTtsRouter(
                narratorBinding = effectiveNarratorBinding,
                characterBindings = characterBindings,
                castRoleBindings = castRoleBindings,
                dialogueMaleBinding = effectiveDialogueMaleBinding,
                dialogueFemaleBinding = effectiveDialogueFemaleBinding,
                dialogueDefaultBinding = effectiveDialogueDefaultBinding,
                characterNameIndex = characterNameIndex,
                characterGenderIndex = characterGenderIndex,
                castRoleNameIndex = castRoleNameIndex,
                castRoleGenderIndex = castRoleGenderIndex,
                knownCharacterIds = knownCharacterIds,
                knownCastRoleIds = knownCastRoleIds,
                unavailableCharacterBindings = unavailableCharacterBindings,
                unavailableCastRoleBindings = unavailableCastRoleBindings
            )
        }

        internal fun isBindingUnavailable(binding: BookTtsVoiceBinding): Boolean {
            return isBindingUnavailable(binding, TtsEngineStore::engineOrVoiceDirectory)
        }

        private fun isBindingUnavailable(
            binding: BookTtsVoiceBinding,
            engineResolver: (String?) -> TtsEngineSetting?
        ): Boolean {
            val engine = engineResolver(binding.engineId)
                ?.takeIf { it.enabled && it.type == TtsEngineType.SCRIPT }
                ?: return true
            val voiceId = binding.speakerId.takeIf { it.isNotBlank() }
                ?: return binding.bindingMode == BookTtsVoiceBinding.BindingMode.AUTO
            return engine.enabledVoices().none { it.id == voiceId }
        }

        private fun BookTtsVoiceBinding.toRouteBinding(
            engineResolver: (String?) -> TtsEngineSetting?
        ): RouteBinding? {
            val engine = engineResolver(engineId)?.takeIf { it.enabled } ?: return null
            val safeVoiceId = speakerId
                .takeIf { it.isNotBlank() }
                ?.takeIf { id -> engine.enabledVoices().any { it.id == id } }
            if (bindingMode == BookTtsVoiceBinding.BindingMode.AUTO && safeVoiceId == null) {
                return null
            }
            return RouteBinding(engine, safeVoiceId, bindingMode)
        }

        private fun TtsEngineSetting.toGlobalRouteBinding(voiceId: String?): RouteBinding? {
            val safeVoiceId = voiceId
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { id -> enabledVoices().any { it.id == id } }
                ?: return null
            return RouteBinding(this, safeVoiceId)
        }

        private fun TtsEngineSetting.toDialogueDefaultRouteBinding(): RouteBinding {
            return RouteBinding(this, activeVoice()?.id)
        }

    }
}
