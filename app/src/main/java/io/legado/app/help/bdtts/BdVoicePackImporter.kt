package io.legado.app.help.bdtts

import android.content.Context
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * bdetts 语音包导入器。
 * zip 结构：config.yaml（发音人列表）+ engines.yaml + <引擎code>/<授权ID>/数据文件 + avatar/
 * 解压落位：externalFilesDir/voice/；config.yaml 解析结果存 BdSpeakerStore。
 */
object BdVoicePackImporter {

    private const val SPEAKER_TAG = "!!org.nobody.multitts.tts.speaker.Speaker"
    private const val ENGINE_GROUP = "bdetts"

    /**
     * 从输入流导入语音包。
     * @return 导入的发音人数量
     * @throws BdImportException 结构不合法 / zip 损坏
     */
    @Synchronized
    @Throws(BdImportException::class)
    fun import(context: Context, input: InputStream): Int {
        val voiceDir = File(context.getExternalFilesDir("voice"), "").apply { mkdirs() }
        unzip(input, voiceDir)

        val config = File(voiceDir, "config.yaml")
        if (!config.isFile) {
            throw BdImportException("config.yaml not found in package")
        }
        return parseAndStore(config.readText(Charsets.UTF_8))
    }

    /**
     * 解析 config.yaml 并写入存储。顶层 key 为引擎 code（如 bdetts），值是 Speaker 列表。
     */
    @Synchronized
    fun parseAndStore(yamlText: String): Int {
        // 去掉 SnakeYAML 类型标签，直接解析为 Map 结构
        val cleaned = yamlText.replace(SPEAKER_TAG, "")
        val root: Map<*, *> = Yaml().load(cleaned) ?: return 0
        val speakers = (root[ENGINE_GROUP] as? List<*>) ?: return 0
        val records = mutableListOf<BdSpeakerRecord>()
        for (item in speakers) {
            val map = item as? Map<*, *> ?: continue
            val record = BdSpeakerRecord()
            record.group = ENGINE_GROUP
            record.code = map["code"]?.toString().orEmpty()
            if (record.code.isEmpty()) {
                continue
            }
            record.name = map["name"]?.toString().orEmpty()
            record.desc = map["desc"]?.toString()
            record.avatar = map["avatar"]?.toString()
            record.gender = (map["gender"] as? Number)?.toInt() ?: 0
            record.type = (map["type"] as? Number)?.toInt() ?: 0
            record.param = map["param"]?.toString().orEmpty()
            record.sampleRate = (map["sampleRate"] as? Number)?.toInt() ?: 16000
            record.speed = (map["speed"] as? Number)?.toFloat() ?: 1.0f
            record.volume = (map["volume"] as? Number)?.toFloat() ?: 1.0f
            record.pitch = (map["pitch"] as? Number)?.toFloat() ?: 1.0f
            record.locale = map["locale"]?.toString() ?: "zh-CN"
            record.id = ENGINE_GROUP + "_" + record.code
            records.add(record)
        }
        // 同 group 重建（与 MultiTTS 的重载数据语义一致）
        val rest = BdSpeakerStore.load().filter { it.group != ENGINE_GROUP }
        BdSpeakerStore.save(rest + records)
        return records.size
    }

    private fun unzip(input: InputStream, destDir: File) {
        val buffer = ByteArray(8192)
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}

class BdImportException(message: String) : Exception(message)
