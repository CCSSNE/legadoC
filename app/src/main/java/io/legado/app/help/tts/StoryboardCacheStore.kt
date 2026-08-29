package io.legado.app.help.tts

import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import splitties.init.appCtx
import java.io.File

/**
 * 章节分镜缓存：文件存储，key = 书 + 章 + 内容MD5 + 模型。
 * 内容或模型变化自动失效，重读同章零 AI 调用。
 */
object StoryboardCacheStore {

    private const val DIR = "ai_tts_storyboard"

    private fun cacheDirectory(): File = File(appCtx.cacheDir, DIR)

    fun cacheKey(
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        contentHash: String,
        providerId: String,
        modelId: String
    ): String {
        return MD5Utils.md5Encode(
            listOf(bookUrl, chapterIndex, chapterTitle, contentHash, providerId, modelId)
                .joinToString("\n")
        )
    }

    fun contentHash(content: String): String = MD5Utils.md5Encode(content)

    fun save(key: String, storyboard: ChapterStoryboard) {
        val dir = cacheDirectory()
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(GSON.toJson(storyboard), Charsets.UTF_8)
        val target = File(dir, "$key.json")
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    fun load(key: String): ChapterStoryboard? {
        val file = File(cacheDirectory(), "$key.json")
        if (!file.isFile) return null
        return try {
            val storyboard = GSON.fromJson(file.readText(Charsets.UTF_8), ChapterStoryboard::class.java)
            storyboard?.takeIf { it.cacheVersion == ChapterStoryboard.CACHE_VERSION }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(key: String) {
        File(cacheDirectory(), "$key.json").delete()
    }

    fun list(): List<Pair<String, ChapterStoryboard>> {
        val dir = cacheDirectory()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                val name = file.name.removeSuffix(".json")
                load(name)?.let { name to it }
            }.sortedWith(
                compareBy({ it.second.bookName }, { it.second.chapterIndex })
            )
    }

    fun clear() {
        cacheDirectory().deleteRecursively()
    }
}
