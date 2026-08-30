package io.legado.app.help.tts

internal data class TtsCapabilitySpec(
    val id: String,
    val version: Int,
    val dependencies: Set<String> = emptySet()
) {
    val versionedId: String get() = "$id@$version"
}

/**
 * App 侧能力契约。脚本头部仍以 Set<String> 作为导入边界，
 * 运行时使用带显式版本与依赖检查的规范 id。
 * （对齐 legado_NG TtsCapabilityRegistry）
 */
object TtsCapabilityRegistry {
    private val specs = listOf(
        TtsCapabilitySpec(TtsEngineCapability.PERSONA, 1),
        TtsCapabilitySpec(TtsEngineCapability.SCENE_CONTEXT, 1),
        TtsCapabilitySpec(
            TtsEngineCapability.PERFORMANCE_INSTRUCTION,
            1,
            setOf(TtsEngineCapability.SCENE_CONTEXT)
        ),
        TtsCapabilitySpec(TtsEngineCapability.STYLE_TAGS, 1),
        TtsCapabilitySpec(TtsEngineCapability.EMOTION, 1),
        TtsCapabilitySpec(
            TtsEngineCapability.EMOTION_INTENSITY,
            1,
            setOf(TtsEngineCapability.EMOTION)
        ),
        TtsCapabilitySpec(TtsEngineCapability.CASTING_METADATA, 1)
    ).associateBy { it.id }

    fun canonicalId(raw: String): String = raw
        .trim()
        .lowercase()
        .substringBefore('@')

    fun normalize(declared: Iterable<String>): Set<String> {
        val resolved = declared
            .map(::canonicalId)
            .filter(specs::containsKey)
            .toMutableSet()
        var changed: Boolean
        do {
            changed = false
            resolved.toList().forEach { id ->
                specs.getValue(id).dependencies.forEach { dependency ->
                    changed = resolved.add(dependency) || changed
                }
            }
        } while (changed)
        return specs.keys.filterTo(linkedSetOf()) { it in resolved }
    }

    fun supports(declared: Iterable<String>, capability: String): Boolean =
        canonicalId(capability) in normalize(declared)

    fun versioned(declared: Iterable<String>): List<String> = normalize(declared)
        .map { specs.getValue(it).versionedId }
        .sorted()
}
