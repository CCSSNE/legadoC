package io.legado.app.help.tts

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsEngineRuntimeEntity
import io.legado.app.data.entities.TtsVoiceEntity
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.plugin.TtsVoiceDirectories
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据驱动的 TTS 引擎存储层（对齐 legado_NG TtsEngineStore）。
 * 相对 NG 的裁剪与适配：
 * - 移除 NG 专属的默认脚本升级机制（OLD_* URL、withUpdatedDefaultScript、
 *   shouldReplaceDefaultScriptWith、updateDefaultScriptForTest）；
 * - 移除 NG 捆绑的厂商引擎常量（next_edge_proxy / mimo / stepaudio / mossland），
 *   内置脚本资产仅保留通用三项（本机转发器 + 两个模板示例）；
 * - 移除 NG 首次使用角色默认绑定逻辑（TtsRoleDefaultPreferences、
 *   resolveFirstUseTtsRoleDefaults、applyFirstUseRoleDefaults）；
 * - 移除 NG 专属首选发音人分支（preferredVoiceId）；
 * - 多角色选角门控沿用本项目 AiMultiVoiceConfig.enabled（NG 为 AppConfig.readAloudMultiRole）；
 * - ReadAloud 集成面（updatePreparedTtsEngine / httpTtsEngineV2 / refreshReadAloudClass /
 *   refreshTtsRoute / upTtsSpeechRate）与 NG 一致。
 */
enum class TtsEngineImportConflictAction {
    ASK,
    OVERWRITE,
    KEEP_BOTH
}

data class TtsEngineImportConflict(
    val id: String,
    val importedName: String,
    val existingName: String,
    val canOverwrite: Boolean
)

class TtsEngineImportConflictException(
    val conflicts: List<TtsEngineImportConflict>
) : IllegalStateException("${conflicts.size} 个朗读引擎已存在")

internal object TtsEngineImportResolver {

    fun conflicts(
        imported: List<TtsEngineSetting>,
        existing: List<TtsEngineSetting>,
        defaultIds: Set<String>
    ): List<TtsEngineImportConflict> {
        val existingById = existing.associateBy { it.id }
        return imported.mapNotNull { candidate ->
            existingById[candidate.id]
                ?.takeIf { candidate.id !in defaultIds }
                ?.let { current ->
                    TtsEngineImportConflict(
                        id = candidate.id,
                        importedName = candidate.name,
                        existingName = current.name,
                        canOverwrite = current.type == TtsEngineType.SCRIPT
                    )
                }
        }.distinctBy { it.id }
    }

    fun resolve(
        imported: List<TtsEngineSetting>,
        existing: List<TtsEngineSetting>,
        defaultIds: Set<String>,
        action: TtsEngineImportConflictAction,
        copyIdSeed: Long = System.currentTimeMillis()
    ): List<TtsEngineSetting> {
        val conflicts = conflicts(imported, existing, defaultIds)
        if (conflicts.isNotEmpty() && action == TtsEngineImportConflictAction.ASK) {
            throw TtsEngineImportConflictException(conflicts)
        }
        if (
            action == TtsEngineImportConflictAction.OVERWRITE &&
            conflicts.any { !it.canOverwrite }
        ) {
            throw IllegalArgumentException("系统朗读引擎不能被导入脚本覆盖")
        }
        if (action != TtsEngineImportConflictAction.KEEP_BOTH || conflicts.isEmpty()) {
            return imported
        }
        val conflictIds = conflicts.mapTo(hashSetOf()) { it.id }
        val usedIds = (existing.map { it.id } + imported.map { it.id }).toMutableSet()
        var nextSuffix = copyIdSeed
        return imported.map { candidate ->
            if (candidate.id !in conflictIds) {
                candidate
            } else {
                var newId: String
                do {
                    newId = "${candidate.id}_${nextSuffix++}"
                } while (!usedIds.add(newId))
                candidate.asImportCopy(newId)
            }
        }
    }

    fun mergeForOverwrite(
        existing: TtsEngineSetting,
        imported: TtsEngineSetting
    ): TtsEngineSetting {
        return imported.copy(
            enabled = existing.enabled,
            builtIn = existing.builtIn,
            optionValues = existing.optionValues,
            activeVoiceId = existing.activeVoiceId,
            disabledVoiceIds = existing.disabledVoiceIds
        )
    }

    private fun TtsEngineSetting.asImportCopy(newId: String): TtsEngineSetting {
        val newName = "$name 副本"
        return copy(
            id = newId,
            name = newName,
            script = script.replaceFirst(
                Regex("""(?m)^(\s*//\s*@uuid\s+).*$"""),
                "$1$newId"
            ).replaceFirst(
                Regex("""(?m)^(\s*//\s*@name\s+).*$"""),
                "$1$newName"
            )
        )
    }
}

internal object TtsEngineOrderResolver {

    /**
     * 只把调用方确认过的 ID 顺序合并到最新快照，保留其余条目及最新字段值。
     * 当条目已被删除、ID 重复或顺序集合失配时拒绝提交，避免旧 UI 快照复活数据。
     */
    fun mergeLatest(
        latest: List<TtsEngineSetting>,
        orderedIds: List<String>
    ): List<TtsEngineSetting>? {
        if (orderedIds.isEmpty() || orderedIds.toSet().size != orderedIds.size) {
            return null
        }
        val orderedIdSet = orderedIds.toSet()
        val latestById = latest.associateBy(TtsEngineSetting::id)
        if (!latestById.keys.containsAll(orderedIdSet)) {
            return null
        }
        val affectedSlots = latest.count { it.id in orderedIdSet }
        if (affectedSlots != orderedIds.size) {
            return null
        }
        val reordered = orderedIds.map { latestById.getValue(it) }.iterator()
        return latest.map { engine ->
            if (engine.id in orderedIdSet) reordered.next() else engine
        }
    }
}

object TtsEngineStore {

    const val SYSTEM_DEFAULT_ID = "system_default"

    /**
     * 内置语音包引擎外观 id：把插件注册的发音人目录（百度等）映射为 V2 引擎条目，
     * 使选角绑定以（引擎, 音色）二元组统一表达。无 script、不参与全局引擎选择列表
     * （[engines] 不注入），仅由选角路由与绑定界面经 [voiceDirectoryEngine] 使用。
     */
    const val VOICE_DIRECTORY_ID = "voice_directory"
    private const val SYSTEM_ENGINE_PREFIX = "system_engine_"
    private const val DEFAULT_TTS_ASSET_DIR = "defaultData/tts"
    private val DEFAULT_SCRIPT_ASSETS = listOf(
        "multitts_forwarder.js",
        "script_options_example.js",
        "static_voices_example.js"
    )
    private val defaultScriptEngineSnapshots by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadDefaultScriptEngines()
    }
    private val defaultScriptIdSet by lazy(LazyThreadSafetyMode.PUBLICATION) {
        defaultScriptEngineSnapshots.mapTo(hashSetOf()) { it.id }
    }
    private val voiceCatalogMutexes = ConcurrentHashMap<String, Mutex>()

    private fun voiceCatalogMutex(engineId: String): Mutex =
        voiceCatalogMutexes.getOrPut(engineId) { Mutex() }

    @Synchronized
    fun engines(): List<TtsEngineSetting> {
        val savedEngines = savedEnginesWithSystemDefaultDisabled()
        val saved = savedEngines.associateBy { it.id }
        val deletedIds = deletedEngineIds()
        val builtInEngines = builtInEngines().filterNot { it.id in deletedIds }
        val builtInIds = builtInEngines.mapTo(hashSetOf()) { it.id }
        val merged = builtInEngines.map { builtIn ->
            saved[builtIn.id]?.let { savedEngine ->
                savedEngine.copy(
                    type = builtIn.type,
                    builtIn = builtIn.builtIn,
                    enginePackage = builtIn.enginePackage,
                    defaultSpeed = if (builtIn.type == TtsEngineType.SYSTEM) {
                        builtIn.defaultSpeed
                    } else {
                        savedEngine.defaultSpeed
                    },
                    defaultVolume = if (builtIn.type == TtsEngineType.SYSTEM) {
                        builtIn.defaultVolume
                    } else {
                        savedEngine.defaultVolume
                    },
                    defaultPitch = if (builtIn.type == TtsEngineType.SYSTEM) {
                        builtIn.defaultPitch
                    } else {
                        savedEngine.defaultPitch
                    }
                )
            } ?: builtIn
        }
        val custom = saved.values
            .filterNot { savedEngine ->
                savedEngine.id in builtInIds ||
                        savedEngine.id in deletedIds ||
                        savedEngine.id.startsWith(SYSTEM_ENGINE_PREFIX)
            }
        val allById = (merged + custom).associateBy { it.id }
        val savedOrder = savedEngines.map { it.id }.filter { it in allById }
        val remainingOrder = allById.keys.filterNot { it in savedOrder }
        val ordered = (savedOrder + remainingOrder).mapNotNull { allById[it] }
        return ordered.map { it.withRuntimeState() }
    }

    fun activeEngineId(): String {
        return resolveActiveEngine(engines())?.id.orEmpty()
    }

    fun activeEngine(): TtsEngineSetting {
        return resolveActiveEngine(engines())
            ?: builtInEngines().first()
    }

    private fun resolveActiveEngine(engines: List<TtsEngineSetting>): TtsEngineSetting? {
        val saved = appCtx.getPrefString(PreferKey.ttsEngineV2ActiveId)
        return saved?.let { id -> engines.firstOrNull { it.id == id && it.enabled } }
            ?: engines.firstOrNull { it.enabled }
    }

    fun hasEnabledEngine(): Boolean {
        return engines().any { it.enabled }
    }

    fun isDeletableEngine(engine: TtsEngineSetting): Boolean {
        return engine.type != TtsEngineType.SYSTEM
    }

    fun engine(id: String?): TtsEngineSetting? {
        if (id.isNullOrBlank()) return null
        return engines().firstOrNull { it.id == id }
    }

    /**
     * 内置语音包引擎外观：无插件注册（开源构建）时返回 null。
     * activeVoiceId 跟随插件目录当前选中发音人，作为选角路由的默认兜底音色。
     */
    fun voiceDirectoryEngine(): TtsEngineSetting? {
        val directory = TtsVoiceDirectories.active ?: return null
        val voices = directory.listVoices().map { info ->
            TtsVoice(
                id = info.id,
                name = info.name,
                language = info.locale,
                gender = info.gender
            )
        }
        return TtsEngineSetting(
            id = VOICE_DIRECTORY_ID,
            name = "内置语音包引擎",
            type = TtsEngineType.SCRIPT,
            enabled = true,
            builtIn = true,
            activeVoiceId = appCtx.getPrefString(PreferKey.bdSelectedSpeaker),
            voices = voices
        )
    }

    /** 引擎解析统一入口：空 id / 外观 id → 内置语音包引擎外观；其余走 V2 引擎存储。 */
    fun engineOrVoiceDirectory(id: String?): TtsEngineSetting? {
        if (id.isNullOrBlank() || id == VOICE_DIRECTORY_ID) {
            return voiceDirectoryEngine()
        }
        return engine(id)
    }

    @Synchronized
    fun saveEngine(engine: TtsEngineSetting, restartReadAloud: Boolean = true) {
        val wasActive = activeEngineId() == engine.id
        val previous = engine(engine.id)
        val currentEngines = engines()
        val exists = currentEngines.any { it.id == engine.id }
        val updatedEngines = if (exists) {
            currentEngines.map { if (it.id == engine.id) engine else it }
        } else {
            currentEngines + engine
        }
        saveEngines(updatedEngines)
        if (previous?.shouldClearVoiceCacheFor(engine) == true) {
            appDb.ttsVoiceDao.deleteByEngine(engine.id)
        }
        val effectiveEngine = TtsEngineStore.engine(engine.id) ?: engine
        if (wasActive) {
            ReadAloud.updatePreparedTtsEngine(effectiveEngine)
        }
        if (wasActive && restartReadAloud) {
            if (effectiveEngine.enabled) {
                ReadAloud.httpTtsEngineV2 = effectiveEngine.takeIf {
                    it.type == TtsEngineType.SCRIPT
                }
                ReadAloud.upReadAloudClass()
            } else {
                selectFirstEnabledEngine()
            }
        }
    }

    fun createCustomScriptEngine(): TtsEngineSetting {
        val now = System.currentTimeMillis()
        val script = """
            // @name 新建朗读引擎
            // @schema 1
            // @version 1.0.0
            // @uuid custom_tts_$now
            // @author User
            // @enabled true
            // @cookieJar false
            // @audioType audio/x-wav
            // @description 自定义 JS 朗读引擎。

            function options() {
                return [];
            }

            function voices(options, ctx) {
                return [];
            }

            function synthesize(text, voice, params, options, ctx) {
                return {};
            }
        """.trimIndent()
        return scriptEngineFromScript(script)
            ?: error("自定义朗读引擎模板解析失败")
    }

    @Synchronized
    fun importEngineText(
        text: String,
        conflictAction: TtsEngineImportConflictAction = TtsEngineImportConflictAction.ASK
    ): Result<List<TtsEngineSetting>> {
        val source = text.trim()
        if (source.isBlank()) {
            return Result.failure(IllegalArgumentException("导入内容为空"))
        }
        return runCatching {
            val parsedEngines = parseImportEngineText(source)
            val savedEngines = TtsEngineImportResolver.resolve(
                imported = parsedEngines,
                existing = engines(),
                defaultIds = defaultScriptIds(),
                action = conflictAction
            ).map { importEngine(it) }
            savedEngines
        }
    }

    @Synchronized
    fun saveEngines(engines: List<TtsEngineSetting>) {
        appCtx.putPrefString(
            PreferKey.ttsEngineV2SettingsJson,
            GSON.toJson(engines.map { it.forConfigSave() })
        )
    }

    @Synchronized
    fun saveVisibleEngineOrder(orderedIds: List<String>): Boolean {
        val reordered = TtsEngineOrderResolver.mergeLatest(
            latest = engines(),
            orderedIds = orderedIds
        ) ?: return false
        saveEngines(reordered)
        return true
    }

    @Synchronized
    fun deleteEngine(id: String): Boolean {
        val engine = engine(id) ?: return false
        if (!isDeletableEngine(engine)) {
            return false
        }
        val wasActive = activeEngineId() == id
        if (wasActive) {
            appCtx.putPrefString(PreferKey.ttsEngineV2ActiveId, "")
        }
        if (engine.builtIn || id in defaultScriptIds()) {
            saveDeletedEngineIds(deletedEngineIds() + id)
        }
        saveEngines(engines().filterNot { it.id == id })
        appDb.ttsVoiceDao.deleteByEngine(id)
        appDb.ttsEngineRuntimeDao.deleteByEngine(id)
        voiceCatalogMutexes.remove(id)
        if (wasActive) {
            selectFirstEnabledEngine()
        }
        return true
    }

    fun selectEngine(id: String) {
        val engine = engine(id)
        if (engine?.enabled != true) {
            return
        }
        appCtx.putPrefString(PreferKey.ttsEngineV2ActiveId, id)
        ReadAloud.upReadAloudClass()
    }

    @Synchronized
    fun selectVoice(engineId: String, voiceId: String?): TtsEngineSetting? {
        val engine = engine(engineId)?.takeIf { it.enabled } ?: return null
        val selectedVoiceId = voiceId?.takeIf { id ->
            engine.enabledVoices().any { it.id == id }
        }
        if (voiceId != null && selectedVoiceId == null) {
            return null
        }
        appCtx.putPrefString(PreferKey.ttsEngineV2ActiveId, engineId)
        val updated = engine.copy(activeVoiceId = selectedVoiceId)
        saveEngines(engines().map { if (it.id == engineId) updated else it })
        ReadAloud.refreshReadAloudClass()
        return engine(engineId)
    }

    @Synchronized
    fun upsertVoiceList(
        engineId: String,
        voices: List<TtsVoice>,
        restartReadAloud: Boolean = true
    ): TtsEngineSetting? {
        val engine = engine(engineId) ?: return null
        val now = System.currentTimeMillis()
        appDb.ttsVoiceDao.replaceForEngine(
            engineId = engineId,
            voices = voices.map { it.toEntity(engineId, now) }
        )
        val activeVoiceId = resolveActiveVoiceId(engine, voices)
        val updated = engine.copy(activeVoiceId = activeVoiceId)
        saveEngine(updated, restartReadAloud)
        return updated.copy(runtimeVoices = voices, lastVoiceUpdateTime = now)
    }

    /**
     * 确保指定引擎的发音人目录已经写入统一的 ttsVoices 缓存。
     * 同一引擎的并发首次获取会合并为一次；等待方在锁内重新读取缓存，
     * 避免听书抽屉、多人选角和引擎设置各自重复获取或维护独立状态。
     */
    suspend fun ensureVoiceCatalog(
        engineId: String,
        forceRefresh: Boolean = false,
        restartReadAloud: Boolean = false
    ): TtsEngineSetting {
        val initial = engine(engineId) ?: error("朗读引擎不存在")
        if (!forceRefresh && initial.effectiveVoices().isNotEmpty()) {
            return initial
        }
        return voiceCatalogMutex(engineId).withLock {
            val latest = engine(engineId) ?: error("朗读引擎不存在")
            if (!forceRefresh && latest.effectiveVoices().isNotEmpty()) {
                return@withLock latest
            }
            check(latest.supportsVoiceFetch()) { "当前朗读引擎不支持获取发音人" }
            val voices = TtsScriptEngineClient.fetchVoices(latest)
            check(voices.isNotEmpty()) { "未获取到发音人" }
            upsertVoiceList(
                engineId = latest.id,
                voices = voices,
                restartReadAloud = restartReadAloud
            ) ?: error("保存发音人目录失败")
        }
    }

    @Synchronized
    fun setVoiceEnabled(
        engineId: String,
        voiceId: String,
        enabled: Boolean
    ): TtsEngineSetting? {
        val engine = engine(engineId) ?: return null
        val disabledIds = engine.disabledVoiceIds.toMutableSet()
        if (enabled) {
            disabledIds.remove(voiceId)
        } else {
            disabledIds.add(voiceId)
        }
        val updated = engine.copy(disabledVoiceIds = disabledIds.toList().sorted())
        saveEngine(updated)
        return engine(updated.id)
    }

    @Synchronized
    fun setAllVoicesEnabled(
        engineId: String,
        voiceIds: List<String>,
        enabled: Boolean
    ): TtsEngineSetting? {
        val engine = engine(engineId) ?: return null
        val updated = engine.copy(
            disabledVoiceIds = if (enabled) {
                emptyList()
            } else {
                voiceIds.filter { it.isNotBlank() }.distinct().sorted()
            }
        )
        saveEngine(updated)
        return engine(updated.id)
    }

    private fun importEngine(engine: TtsEngineSetting): TtsEngineSetting {
        if (engine.id in defaultScriptIds()) {
            saveDeletedEngineIds(deletedEngineIds() - engine.id)
        }
        val currentEngines = engines()
        val existing = currentEngines.firstOrNull { it.id == engine.id }
        val imported = existing?.let {
            TtsEngineImportResolver.mergeForOverwrite(it, engine)
        } ?: engine
        val merged = if (existing != null) {
            currentEngines.map { if (it.id == imported.id) imported else it }
        } else {
            currentEngines + imported
        }
        saveEngines(merged)
        if (existing?.shouldClearVoiceCacheFor(imported) == true) {
            appDb.ttsVoiceDao.deleteByEngine(imported.id)
        }
        return engine(imported.id) ?: imported
    }

    private fun parseImportEngineText(text: String): List<TtsEngineSetting> {
        scriptEngineFromScript(text)?.let { return listOf(it) }
        val element = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?: throw IllegalArgumentException("不支持的朗读引擎格式")
        if (element.isJsonObject) {
            parseEngineFromJsonObject(element.asJsonObject)?.let { return listOf(it) }
        }
        if (element.isJsonArray) {
            val engines = element.asJsonArray.mapNotNull { item ->
                item.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { parseEngineFromJsonObject(it) }
            }
            if (engines.isNotEmpty()) {
                return engines
            }
        }
        throw IllegalArgumentException("不支持的朗读引擎格式")
    }

    private fun parseEngineFromJsonObject(jsonObject: JsonObject): TtsEngineSetting? {
        val parsed = runCatching {
            GSON.fromJson(jsonObject, TtsEngineSetting::class.java)
        }.getOrNull() ?: return null
        return parsed.normalizedOrNull()?.takeIf { engine ->
            engine.type == TtsEngineType.SCRIPT && engine.script.isNotBlank()
        }
    }

    fun saveRuntimeParams(
        engineId: String,
        speed: Int,
        volume: Int,
        pitch: Int
    ): TtsEngineSetting? {
        appDb.ttsEngineRuntimeDao.upsert(
            TtsEngineRuntimeEntity(
                engineId = engineId,
                speed = speed.coerceIn(0, 100),
                volume = volume.coerceIn(0, 100),
                pitch = pitch.coerceIn(0, 100),
                updatedAt = System.currentTimeMillis()
            )
        )
        val updated = engine(engineId)
        val isActiveEngine = activeEngineId() == engineId
        if (isActiveEngine) {
            updated?.let { engine ->
                ReadAloud.updatePreparedTtsEngine(engine)
                ReadAloud.httpTtsEngineV2 = engine.takeIf {
                    it.type == TtsEngineType.SCRIPT
                }
            }
        }
        when {
            updated?.type == TtsEngineType.SYSTEM && isActiveEngine -> {
                ReadAloud.upTtsSpeechRate(appCtx)
            }
            updated?.type == TtsEngineType.SCRIPT && (
                isActiveEngine ||
                    AiMultiVoiceConfig.enabled && AppConfig.multiRoleTtsEngineId == engineId
                ) -> {
                ReadAloud.refreshTtsRoute(appCtx)
            }
        }
        return updated
    }

    fun voices(engineId: String): List<TtsVoice> {
        return appDb.ttsVoiceDao.getByEngine(engineId).map { it.toVoice() }
    }

    fun voice(engineId: String, voiceId: String?): TtsVoice? {
        if (voiceId.isNullOrBlank()) {
            return null
        }
        return appDb.ttsVoiceDao.get(engineId, voiceId)?.toVoice()
            ?: engine(engineId)?.voices?.firstOrNull { it.id == voiceId }
    }

    fun voiceCounts(): Map<String, Int> {
        return appDb.ttsVoiceDao.countByEngine().associate { it.engineId to it.count }
    }

    internal fun resolveActiveVoiceId(
        engine: TtsEngineSetting,
        voices: List<TtsVoice>
    ): String? {
        return engine.activeVoiceId
            ?.takeIf { voiceId -> voices.any { it.id == voiceId } }
            ?: voices.firstOrNull()?.id
    }

    private fun savedEngines(): List<TtsEngineSetting> {
        val json = appCtx.getPrefString(PreferKey.ttsEngineV2SettingsJson)
        val normalized = GSON.fromJsonArray<TtsEngineSetting>(json)
            .getOrDefault(emptyList())
            .mapNotNull { it.normalizedOrNull() }
        if (json?.contains("\"last_voice_update_time\"") == true) {
            appCtx.putPrefString(PreferKey.ttsEngineV2SettingsJson, GSON.toJson(normalized))
        }
        return normalized
    }

    private fun TtsEngineSetting.normalizedOrNull(): TtsEngineSetting? {
        val safeId = safeString { id }.takeIf { it.isNotBlank() } ?: return null
        val safeType = runCatching { type }.getOrNull() ?: TtsEngineType.SCRIPT
        val safeScript = safeString { script }
        val safeCapabilities = when (safeType) {
            TtsEngineType.SCRIPT -> parseScriptCapabilities(safeScript)
            else -> runCatching { capabilities }.getOrNull().orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        }
        return TtsEngineSetting(
            id = safeId,
            name = safeString { name }.ifBlank { "未命名朗读引擎" },
            type = safeType,
            enabled = runCatching { enabled }.getOrDefault(true),
            builtIn = runCatching { builtIn }.getOrDefault(false),
            enginePackage = safeNullableString { enginePackage },
            url = safeString { url },
            script = safeScript,
            optionValues = safeOptionValues(),
            contentType = safeString { contentType }.ifBlank { "audio/x-wav" },
            concurrentRate = safeNullableString { concurrentRate },
            maxConcurrency = safeInt(0) { maxConcurrency }.coerceIn(0, 16),
            loginUrl = safeNullableString { loginUrl },
            loginUi = safeNullableString { loginUi },
            loginCheckJs = safeNullableString { loginCheckJs },
            header = safeNullableString { header },
            jsLib = safeNullableString { jsLib },
            enabledCookieJar = runCatching { enabledCookieJar }.getOrNull(),
            voicesUrl = safeNullableString { voicesUrl },
            voicesParser = safeString { voicesParser }.ifBlank { "auto" },
            baseUrl = safeString { baseUrl },
            activeVoiceId = safeNullableString { activeVoiceId },
            defaultSpeed = safeInt(50) { defaultSpeed }.coerceIn(0, 100),
            defaultVolume = safeInt(50) { defaultVolume }.coerceIn(0, 100),
            defaultPitch = safeInt(50) { defaultPitch }.coerceIn(0, 100),
            voicesPath = safeString { voicesPath }.ifBlank { "/voices" },
            synthesisPath = safeString { synthesisPath }.ifBlank { "/forward" },
            textParam = safeString { textParam }.ifBlank { "text" },
            voiceParam = safeString { voiceParam }.ifBlank { "voice" },
            speedParam = safeString { speedParam }.ifBlank { "speed" },
            volumeParam = safeString { volumeParam }.ifBlank { "volume" },
            pitchParam = safeString { pitchParam }.ifBlank { "pitch" },
            voices = safeVoices(),
            disabledVoiceIds = safeStringList { disabledVoiceIds },
            capabilities = safeCapabilities,
        )
    }

    private fun TtsEngineSetting.withRuntimeState(): TtsEngineSetting {
        val storedVoices = voices(id)
        val runtime = appDb.ttsEngineRuntimeDao.get(id)
        return copy(
            runtimeSpeed = runtime?.speed,
            runtimeVolume = runtime?.volume,
            runtimePitch = runtime?.pitch,
            runtimeVoices = storedVoices.takeIf { it.isNotEmpty() },
            lastVoiceUpdateTime = appDb.ttsVoiceDao.lastUpdatedAt(id) ?: 0L
        )
    }

    private fun TtsEngineSetting.shouldClearVoiceCacheFor(updated: TtsEngineSetting): Boolean {
        return type == TtsEngineType.SCRIPT &&
                updated.type == TtsEngineType.SCRIPT &&
                (script != updated.script || optionValues != updated.optionValues)
    }

    private fun TtsEngineSetting.forConfigSave(): TtsEngineSetting {
        return copy(
            runtimeSpeed = null,
            runtimeVolume = null,
            runtimePitch = null,
            runtimeVoices = null,
            lastVoiceUpdateTime = 0L
        )
    }

    fun normalizeEditedEngine(
        parsed: TtsEngineSetting,
        source: TtsEngineSetting
    ): Result<TtsEngineSetting> {
        val normalized = parsed.normalizedOrNull()
            ?: return Result.failure(IllegalArgumentException("缺少引擎 id"))
        if (normalized.name.isBlank()) {
            return Result.failure(IllegalArgumentException("名称不能为空"))
        }
        if (source.type == TtsEngineType.SCRIPT &&
            normalized.script.isBlank()
        ) {
            return Result.failure(IllegalArgumentException("script 不能为空"))
        }
        return Result.success(
            normalized.copy(
                id = source.id,
                type = source.type,
                builtIn = source.builtIn,
                enginePackage = source.enginePackage,
                runtimeSpeed = source.runtimeSpeed,
                runtimeVolume = source.runtimeVolume,
                runtimePitch = source.runtimePitch,
                runtimeVoices = source.runtimeVoices,
                lastVoiceUpdateTime = source.lastVoiceUpdateTime
            )
        )
    }

    fun normalizeEditedEngineJson(
        json: String,
        source: TtsEngineSetting
    ): Result<TtsEngineSetting> {
        val jsonObject = runCatching {
            JsonParser.parseString(json).asJsonObject
        }.getOrElse {
            return Result.failure(IllegalArgumentException("源码不是 JSON 对象"))
        }
        val validationError = validateEditedEngineJson(jsonObject)
        if (validationError != null) {
            return Result.failure(IllegalArgumentException(validationError))
        }
        val parsed = runCatching {
            GSON.fromJson(jsonObject, TtsEngineSetting::class.java)
        }.getOrElse {
            return Result.failure(IllegalArgumentException(it.localizedMessage ?: "源码解析失败"))
        }
        val normalized = normalizeEditedEngine(parsed, source).getOrElse {
            return Result.failure(it)
        }
        return Result.success(
            normalized.copy(
                enabled = normalized.enabled.takeIf { jsonObject.has("enabled") }
                    ?: source.enabled,
                builtIn = normalized.builtIn.takeIf { jsonObject.has("built_in") }
                    ?: source.builtIn,
                defaultSpeed = normalized.defaultSpeed.takeIf { jsonObject.has("default_speed") }
                    ?: source.defaultSpeed,
                defaultVolume = normalized.defaultVolume.takeIf { jsonObject.has("default_volume") }
                    ?: source.defaultVolume,
                defaultPitch = normalized.defaultPitch.takeIf { jsonObject.has("default_pitch") }
                    ?: source.defaultPitch
            )
        )
    }

    private fun validateEditedEngineJson(jsonObject: JsonObject): String? {
        val textKeys = listOf("id", "name", "type", "url", "script")
        textKeys.forEach { key ->
            jsonObject[key]?.let { element ->
                if (element.isJsonNull || !element.isJsonPrimitive) {
                    return "$key 必须是文本"
                }
                if (key in listOf("id", "name") && element.asString.isBlank()) {
                    return "$key 不能为空"
                }
            }
        }
        val booleanKeys = listOf("enabled", "built_in", "enabledCookieJar", "enabled_cookie_jar")
        booleanKeys.forEach { key ->
            jsonObject[key]?.let { element ->
                if (element.isJsonNull || !element.isBooleanPrimitive()) {
                    return "$key 必须是 true 或 false"
                }
            }
        }
        val numberKeys = listOf("default_speed", "default_volume", "default_pitch")
        numberKeys.forEach { key ->
            jsonObject[key]?.let { element ->
                if (element.isJsonNull || !element.isNumberPrimitive()) {
                    return "$key 必须是数字"
                }
            }
        }
        jsonObject["voices"]?.let { element ->
            if (!element.isJsonNull && !element.isJsonArray) {
                return "voices 必须是数组"
            }
        }
        jsonObject["disabled_voice_ids"]?.let { element ->
            if (!element.isJsonNull && !element.isJsonArray) {
                return "disabled_voice_ids 必须是数组"
            }
        }
        jsonObject["option_values"]?.let { element ->
            if (!element.isJsonNull && !element.isJsonObject) {
                return "option_values 必须是对象"
            }
        }
        return null
    }

    private fun JsonElement.isBooleanPrimitive(): Boolean {
        return runCatching { asJsonPrimitive.isBoolean }.getOrDefault(false)
    }

    private fun JsonElement.isNumberPrimitive(): Boolean {
        return runCatching { asJsonPrimitive.isNumber }.getOrDefault(false)
    }

    private fun TtsEngineSetting.safeVoices(): List<TtsVoice> {
        return runCatching { voices }.getOrNull()
            .orEmpty()
            .mapNotNull { voice ->
                val id = runCatching { voice.id }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val name = runCatching { voice.name }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: id
                TtsVoice(
                    id = id,
                    name = name,
                    language = runCatching { voice.language }.getOrNull(),
                    gender = runCatching { voice.gender }.getOrNull(),
                    style = runCatching { voice.style }.getOrNull(),
                    tags = runCatching { voice.tags }.getOrNull().orEmpty(),
                    sampleText = runCatching { voice.sampleText }.getOrNull()
                )
            }.distinctBy { it.id }
    }

    private fun TtsEngineSetting.safeOptionValues(): Map<String, String> {
        return runCatching { optionValues }.getOrNull()
            .orEmpty()
            .mapValues { it.value }
            .filterKeys { it.isNotBlank() }
    }

    private inline fun safeStringList(block: () -> List<String>?): List<String> {
        return runCatching { block() }.getOrNull()
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private inline fun safeString(block: () -> String?): String {
        return runCatching { block() }.getOrNull().orEmpty()
    }

    private inline fun safeNullableString(block: () -> String?): String? {
        return safeString(block).takeIf { it.isNotBlank() }
    }

    private inline fun safeInt(defaultValue: Int, block: () -> Int): Int {
        return runCatching { block() }.getOrDefault(defaultValue)
    }

    private fun TtsVoice.toEntity(engineId: String, updatedAt: Long): TtsVoiceEntity {
        return TtsVoiceEntity(
            engineId = engineId,
            id = id,
            name = name,
            language = language,
            gender = gender,
            style = style,
            tagsJson = GSON.toJson(tags),
            sampleText = sampleText,
            extraJson = extra?.let { GSON.toJson(it) } ?: "{}",
            updatedAt = updatedAt
        )
    }

    private fun TtsVoiceEntity.toVoice(): TtsVoice {
        return TtsVoice(
            id = id,
            name = name,
            language = language,
            gender = gender,
            style = style,
            tags = GSON.fromJsonArray<String>(tagsJson).getOrDefault(emptyList()),
            sampleText = sampleText,
            extra = GSON.fromJsonObject<JsonObject>(extraJson).getOrNull()
                ?.takeIf { it.size() > 0 }
        )
    }

    private fun savedEnginesWithSystemDefaultDisabled(): List<TtsEngineSetting> {
        val savedEngines = savedEngines()
        if (appCtx.getPrefBoolean(PreferKey.ttsEngineV2SystemDisabledApplied, false)) {
            return savedEngines
        }
        val updated = savedEngines.map { engine ->
            if (engine.type == TtsEngineType.SYSTEM && engine.enabled) {
                engine.copy(enabled = false)
            } else {
                engine
            }
        }
        if (updated != savedEngines) {
            saveEngines(updated)
        }
        appCtx.putPrefBoolean(PreferKey.ttsEngineV2SystemDisabledApplied, true)
        return updated
    }

    private fun builtInEngines(): List<TtsEngineSetting> {
        return buildList {
            add(
                TtsEngineSetting(
                    id = SYSTEM_DEFAULT_ID,
                    name = "系统默认 TTS",
                    type = TtsEngineType.SYSTEM,
                    enabled = false,
                    builtIn = true,
                    defaultSpeed = 100,
                    defaultVolume = 50,
                    defaultPitch = 50
                )
            )
            addAll(systemTtsEngines())
            addAll(defaultScriptEngines())
        }
    }

    private fun defaultScriptEngines(): List<TtsEngineSetting> = defaultScriptEngineSnapshots

    private fun loadDefaultScriptEngines(): List<TtsEngineSetting> {
        return appCtx.assets.list(DEFAULT_TTS_ASSET_DIR)
            .orEmpty()
            .filter { it.endsWith(".js", ignoreCase = true) }
            .sortedWith(
                compareBy<String> {
                    DEFAULT_SCRIPT_ASSETS.indexOf(it).takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                }.thenBy { it }
            )
            .mapNotNull { scriptEngineFromAsset(it) }
    }

    internal fun scriptEngineFromAsset(fileName: String): TtsEngineSetting? {
        val path = "$DEFAULT_TTS_ASSET_DIR/$fileName"
        val script = runCatching {
            appCtx.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return scriptEngineFromScript(script)
    }

    internal fun scriptEngineFromScript(script: String): TtsEngineSetting? {
        val metadata = parseScriptMetadata(script)
        val id = metadata["uuid"]?.takeIf { it.isNotBlank() } ?: return null
        val name = metadata["name"]?.takeIf { it.isNotBlank() } ?: return null
        val url = metadata["url"].orEmpty()
        return TtsEngineSetting(
            id = id,
            name = name,
            type = TtsEngineType.SCRIPT,
            enabled = metadata["enabled"].toScriptBoolean(defaultValue = true),
            builtIn = false,
            url = "",
            script = script,
            contentType = metadata["audiotype"]?.takeIf { it.isNotBlank() }
                ?: metadata["contenttype"]?.takeIf { it.isNotBlank() }
                ?: "audio/x-wav",
            concurrentRate = metadata["concurrentrate"]?.takeIf { it.isNotBlank() } ?: "0",
            maxConcurrency = metadata["maxconcurrency"]
                .toScriptInt(defaultValue = 0)
                .coerceIn(0, 16),
            enabledCookieJar = metadata["cookiejar"].toScriptBoolean(defaultValue = false),
            sampleText = metadata["sampletext"]?.takeIf { it.isNotBlank() },
            defaultSpeed = metadata["defaultspeed"].toScriptInt(defaultValue = 50),
            defaultVolume = metadata["defaultvolume"].toScriptInt(defaultValue = 50),
            defaultPitch = metadata["defaultpitch"].toScriptInt(defaultValue = 50),
            capabilities = parseScriptCapabilities(script),
            baseUrl = url.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty(),
            synthesisPath = "/forward"
        )
    }

    internal fun parseScriptMetadata(script: String): Map<String, String> {
        val metadata = linkedMapOf<String, String>()
        val regex = Regex("""^\s*//\s*@([A-Za-z][\w-]*)\s*(.*)$""")
        script.lineSequence().forEach { line ->
            val match = regex.find(line) ?: return@forEach
            metadata[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
        }
        return metadata
    }

    private fun parseScriptCapabilities(script: String): Set<String> {
        return parseScriptMetadata(script)["capabilities"]
            .orEmpty()
            .split(',', '，', '|')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun String?.toScriptBoolean(defaultValue: Boolean): Boolean {
        return when (this?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> defaultValue
        }
    }

    private fun String?.toScriptInt(defaultValue: Int): Int {
        return this?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: defaultValue
    }

    private fun systemTtsEngines(): List<TtsEngineSetting> {
        val packageManager = appCtx.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfos.mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            val packageName = serviceInfo.packageName?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(packageManager).toString()
                .takeIf { it.isNotBlank() }
                ?: serviceInfo.applicationInfo?.loadLabel(packageManager)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: packageName
            TtsEngineSetting(
                id = SYSTEM_ENGINE_PREFIX + packageName,
                name = label,
                type = TtsEngineType.SYSTEM,
                enabled = false,
                builtIn = true,
                enginePackage = packageName,
                defaultSpeed = 100,
                defaultVolume = 50,
                defaultPitch = 50
            )
        }.distinctBy { it.id }
    }

    private fun selectFirstEnabledEngine() {
        val nextId = engines().firstOrNull { it.enabled }?.id.orEmpty()
        appCtx.putPrefString(PreferKey.ttsEngineV2ActiveId, nextId)
        ReadAloud.upReadAloudClass()
    }

    private fun deletedEngineIds(): Set<String> {
        val json = appCtx.getPrefString(PreferKey.ttsEngineV2DeletedIds)
        return GSON.fromJsonArray<String>(json).getOrDefault(emptyList()).toSet()
    }

    private fun saveDeletedEngineIds(ids: Set<String>) {
        appCtx.putPrefString(PreferKey.ttsEngineV2DeletedIds, GSON.toJson(ids.toList()))
    }

    private fun defaultScriptIds(): Set<String> {
        return defaultScriptIdSet
    }

}
