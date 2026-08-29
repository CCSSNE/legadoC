package io.legado.app.help.bdtts

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import splitties.init.appCtx
import java.io.File

/**
 * bdetts 发音人列表存储（JSON 文件；config.yaml 才是数据源头，此处为解析缓存）。
 */
object BdSpeakerStore {

    private const val FILE_NAME = "bdetts_speakers.json"
    private val gson = Gson()

    private fun file(): File {
        val dir = File(appCtx.getExternalFilesDir("voice"), "bdetts")
        dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun load(): MutableList<BdSpeakerRecord> {
        val f = file()
        if (!f.isFile) {
            return mutableListOf()
        }
        return try {
            val type = object : TypeToken<MutableList<BdSpeakerRecord>>() {}.type
            gson.fromJson(f.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun save(list: List<BdSpeakerRecord>) {
        file().writeText(gson.toJson(list))
    }

    @Synchronized
    fun upsert(record: BdSpeakerRecord) {
        val list = load()
        val idx = list.indexOfFirst { it.id == record.id }
        if (idx >= 0) {
            list[idx] = record
        } else {
            list.add(record)
        }
        save(list)
    }

    @Synchronized
    fun delete(id: String) {
        val list = load()
        list.removeAll { it.id == id }
        save(list)
    }
}
