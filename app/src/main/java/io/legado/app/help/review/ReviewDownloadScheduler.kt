package io.legado.app.help.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** 各阶段全局共享配额；调低并发不取消在途工作，调高立即唤醒排队工作。 */
object ReviewDownloadScheduler {
    class Gate(private val setting: ReviewDownloadConfig.Number) {
        private val lock = java.lang.Object()
        private var active = 0
        private val changes = MutableStateFlow(0L)

        fun signal() = synchronized(lock) {
            changes.value++
            lock.notifyAll()
        }

        fun acquire() = synchronized(lock) {
            while (active >= setting.value) lock.wait()
            active++
        }

        fun release() = synchronized(lock) {
            check(active > 0) { "评论下载配额重复释放：${setting.key}" }
            active--
            signal()
        }

        suspend fun <T> withPermit(block: suspend () -> T): T {
            while (true) {
                val version = changes.value
                val acquired = synchronized(lock) {
                    if (active < setting.value) { active++; true } else false
                }
                if (acquired) {
                    try { return block() } finally { release() }
                }
                changes.first { it != version }
            }
        }

        fun <T> blocking(block: () -> T): T {
            acquire()
            try { return block() } finally { release() }
        }
    }

    val data = Gate(ReviewDownloadConfig.Number.DATA)
    val replies = Gate(ReviewDownloadConfig.Number.REPLIES)
    val resources = Gate(ReviewDownloadConfig.Number.RESOURCES)
    val pages = Gate(ReviewDownloadConfig.Number.PAGES)
    val heavy = Gate(ReviewDownloadConfig.Number.HEAVY)

    fun settingsChanged() {
        listOf(data, replies, resources, pages, heavy).forEach { it.signal() }
    }
}
