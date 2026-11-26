package io.legado.app.plugin.doubao

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.model.ReadBook
import io.legado.app.plugin.TtsVoiceDirectory
import io.legado.app.plugin.TtsVoiceInfo
import splitties.init.appCtx

/** 内置豆包音色条目（assets/doubao/doubao_voices.json）。 */
data class DoubaoVoice(
    val id: String,
    val name: String,
    val gender: String = "unknown",
    val group: String = "recommend"
)

/**
 * 豆包原生引擎的音色表与选角目录。
 * 音色表随 APK 内置（374 个），不依赖网络拉取；AI 选角与朗读路由都从这里取。
 */
object DoubaoVoices {

    const val ENGINE_ID = "doubao"
    private const val ASSET = "doubao/doubao_voices.json"
    private const val PREF_NAME = "doubao_tts_pref"
    private const val KEY_VOICE = "doubao_voice_id"

    private val gson = Gson()

    @Volatile
    private var cached: List<DoubaoVoice> = emptyList()

    fun all(): List<DoubaoVoice> {
        if (cached.isNotEmpty()) return cached
        synchronized(this) {
            if (cached.isNotEmpty()) return cached
            cached = runCatching {
                val text = appCtx.assets.open(ASSET).bufferedReader().use { it.readText() }
                val type = object : TypeToken<Map<String, Any?>>() {}.type
                val root = gson.fromJson<Map<String, Any?>>(text, type)
                @Suppress("UNCHECKED_CAST")
                val rawList = root["voices"] as? List<Map<String, Any?>>
                rawList?.mapNotNull { item ->
                    val id = item["id"] as? String ?: return@mapNotNull null
                    DoubaoVoice(
                        id = id,
                        name = item["name"] as? String ?: id,
                        gender = (item["gender"] as? String ?: "unknown").lowercase(),
                        group = item["group"] as? String ?: "recommend"
                    )
                }.orEmpty()
            }.onFailure {
                AppLog.put("豆包音色表读取失败：$ASSET\n${it.localizedMessage}", it)
            }.getOrDefault(emptyList())
            AppLog.put("豆包音色表已加载：${cached.size} 个")
            return cached
        }
    }

    fun byId(voiceId: String): DoubaoVoice? = all().firstOrNull { it.id == voiceId }

    private fun prefs() = appCtx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)

    var defaultVoiceId: String?
        get() = prefs().getString(KEY_VOICE, null)
        set(value) {
            prefs().edit().putString(KEY_VOICE, value).apply()
        }

    /**
     * 当前朗读音色：AI/手动绑定（book_tts_voice_bindings，engineId=doubao）优先，
     * 其次引擎默认音色，最后第一个内置音色。
     */
    fun resolveVoice(): String {
        // 1) AI 角色设置里的「旁白发音人」：内置目录分支写的是 PreferKey.aiNarratorSpeakerId，
        //    值是复合 id（doubao\nvoiceId）。这是用户显式选择，优先级最高。
        runCatching { appCtx.getPrefString(PreferKey.aiNarratorSpeakerId) }.getOrNull()
            ?.let { raw ->
                val voiceId = parseGlobal(raw) ?: raw
                if (byId(voiceId) != null) {
                    return voiceId
                }
            }
        // 2) 本书 AI 选角绑定（叙述者 → 任意绑定）
        resolveBoundVoice()?.let { voiceId ->
            if (byId(voiceId) != null) {
                return voiceId
            }
        }
        // 3) 引擎默认
        defaultVoiceId?.let { voiceId ->
            if (byId(voiceId) != null) {
                return voiceId
            }
        }
        val fallback = all().firstOrNull()?.id ?: "zh_female_wenroutaozi_uranus_bigtts"
        return fallback
    }

    fun resolveBoundVoice(): String? {
        val book = ReadBook.book ?: return null
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        val dao = appDb.bookRoleDao

        /**
         * 绑定有两种编码必须都认：
         * 1) 脚本宿主分支：engineId=引擎id，speakerId=音色 id；
         * 2) 内置目录分支（AI 自动选音走这里）：engineId 为空串，speakerId 是复合 id "doubao\nvoiceId"。
         * 之前只认第 1 种，导致 AI 自动选出的音色读不出来，一直回退默认音色。
         */
        fun decode(binding: BookTtsVoiceBinding?): String? {
            if (binding == null) return null
            val raw = binding.speakerId
            if (raw.isBlank()) return null
            if (binding.engineId == ENGINE_ID) {
                val voiceId = raw.substringAfter('\n', raw)
                return voiceId.takeIf { it.isNotBlank() && byId(it) != null }
            }
            parseGlobal(raw)?.let { voiceId -> return voiceId.takeIf { byId(it) != null } }
            return raw.takeIf { byId(it) != null }
        }

        val narrator = runCatching {
            dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.NARRATOR, 0L)
        }.getOrNull()
        decode(narrator)?.let { return it }
        return runCatching { dao.getBindings(workKey) }.getOrNull().orEmpty()
            .asSequence()
            .mapNotNull { decode(it) }
            .firstOrNull()
    }

    /**
     * 日志展示用：优先音色名，附 id 后 6 位便于对照；不在内置音色表里的明确标注。
     */
    fun describe(voiceId: String): String {
        if (voiceId.isBlank()) return "（空）"
        val voice = byId(voiceId)
        return if (voice != null) {
            voice.name + "〔" + voiceId.takeLast(6) + "〕"
        } else {
            voiceId + "（不在内置音色表）"
        }
    }

    /** 复合 id：engineId\nvoiceId，与 legadoC 现有绑定约定一致。 */
    fun globalVoiceId(voiceId: String): String = "$ENGINE_ID\n$voiceId"

    fun parseGlobal(globalId: String?): String? {
        val raw = globalId ?: return null
        val index = raw.indexOf('\n')
        if (index <= 0) return null
        return if (raw.substring(0, index) == ENGINE_ID) raw.substring(index + 1) else null
    }
}

/** AI 选角用的发音人目录：直接暴露内置豆包音色。 */
object DoubaoVoiceDirectory : TtsVoiceDirectory {

    override fun listVoices(): List<TtsVoiceInfo> = DoubaoVoices.all().map { voice ->
        TtsVoiceInfo(
            id = DoubaoVoices.globalVoiceId(voice.id),
            name = voice.name,
            desc = voice.group.takeIf { it != "recommend" },
            gender = when (voice.gender) {
                "male", "female" -> voice.gender
                else -> "unknown"
            },
            locale = "zh-CN"
        )
    }

    override fun voiceById(id: String): TtsVoiceInfo? =
        listVoices().firstOrNull { it.id == id }
}
