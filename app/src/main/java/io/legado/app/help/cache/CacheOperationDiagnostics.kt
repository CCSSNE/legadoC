package io.legado.app.help.cache

import io.legado.app.constant.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared, low-volume diagnostics for cache operations that can allocate or block heavily.
 *
 * Lifecycle logs describe the Coordinator state machine. This object deliberately covers the
 * work below that state machine: parsing, WebView serialization, persistence and media writes.
 * Successful fast operations are sampled; slow, large, high-heap and failed operations always
 * reach AppLog so diagnostics cannot turn into another I/O bottleneck.
 */
internal object CacheOperationDiagnostics {

    enum class Domain {
        STORE,
        BODY,
        REVIEW,
        MEDIA,
    }

    data class Context(
        val domain: Domain,
        val sessionId: String? = null,
        val taskId: String? = null,
        val generation: Long? = null,
        val chapterIndex: Int? = null,
        val unitCount: Int? = null,
    ) {
        fun forChapter(index: Int) = copy(chapterIndex = index)
    }

    data class Metrics(
        val inputChars: Int? = null,
        val outputChars: Int? = null,
        val inputBytes: Long? = null,
        val outputBytes: Long? = null,
        val resourceCount: Int? = null,
        val sessionCount: Int? = null,
        val taskCount: Int? = null,
        val unitCount: Int? = null,
        val persisted: Boolean? = null,
    )

    private const val SLOW_OPERATION_MS = 1_000L
    private const val LARGE_VALUE_BYTES = 1L * 1024L * 1024L
    private const val LARGE_VALUE_CHARS = 1 * 1024 * 1024
    private const val HIGH_HEAP_PERCENT = 75L
    private const val SAMPLE_FIRST = 3L
    private const val SAMPLE_EVERY = 32L

    private val activeOperations = ConcurrentHashMap<String, AtomicInteger>()
    private val operationSequences = ConcurrentHashMap<String, AtomicLong>()

    fun begin(
        context: Context,
        operation: String,
        metrics: Metrics = Metrics(),
    ): Operation {
        val key = "${context.domain}/$operation"
        val active = activeOperations.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        val sequence = operationSequences.getOrPut(key) { AtomicLong() }.incrementAndGet()
        return Operation(
            context = context,
            operation = operation,
            activeKey = key,
            activeAtStart = active,
            sampled = sequence <= SAMPLE_FIRST || sequence % SAMPLE_EVERY == 0L,
        ).also { it.mark("${operation}_START", metrics) }
    }

    class Operation internal constructor(
        private val context: Context,
        private val operation: String,
        private val activeKey: String,
        private val activeAtStart: Int,
        private val sampled: Boolean,
    ) {
        private val startedAtNanos = System.nanoTime()
        private val finished = AtomicBoolean(false)

        fun mark(event: String, metrics: Metrics = Metrics()) {
            emit(
                event,
                metrics,
                CacheOperationDiagnostics.activeOperations[activeKey]?.get() ?: activeAtStart,
            )
        }

        fun done(
            metrics: Metrics = Metrics(),
            event: String = "${operation}_DONE",
        ) {
            if (!finished.compareAndSet(false, true)) return
            emit(event, metrics, release())
        }

        fun cancelled(metrics: Metrics = Metrics()) {
            if (!finished.compareAndSet(false, true)) return
            emit("${operation}_CANCELLED", metrics, release(), force = true)
        }

        /** A non-fatal stage failure: the enclosing operation is still allowed to continue. */
        fun warn(event: String, error: Throwable, metrics: Metrics = Metrics()) {
            emit(
                event,
                metrics,
                CacheOperationDiagnostics.activeOperations[activeKey]?.get() ?: activeAtStart,
                error = error,
                force = true,
            )
        }

        fun fail(error: Throwable, metrics: Metrics = Metrics()) {
            if (!finished.compareAndSet(false, true)) return
            emit("${operation}_FAILED", metrics, release(), error = error, force = true)
        }

        private fun release(): Int = CacheOperationDiagnostics.activeOperations[activeKey]
            ?.decrementAndGet()
            ?.coerceAtLeast(0)
            ?: 0

        private fun emit(
            event: String,
            metrics: Metrics,
            active: Int,
            error: Throwable? = null,
            force: Boolean = false,
        ) {
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            val heap = CacheOperationDiagnostics.heap()
            val warn = error != null || elapsedMs >= CacheOperationDiagnostics.SLOW_OPERATION_MS ||
                CacheOperationDiagnostics.isLarge(metrics) ||
                heap.percent >= CacheOperationDiagnostics.HIGH_HEAP_PERCENT
            if (!force && !sampled && !warn) return

            val level = if (warn || error != null) "WARN" else "INFO"
            val message = buildString {
                append(level).append(" cache_perf")
                append(" domain=").append(context.domain)
                append(" event=").append(event)
                context.sessionId?.let { append(" session=").append(it) }
                context.taskId?.let { append(" task=").append(it) }
                context.generation?.let { append(" generation=").append(it) }
                context.chapterIndex?.let { append(" chapter=").append(it) }
                (metrics.unitCount ?: context.unitCount)?.let { append(" units=").append(it) }
                metrics.sessionCount?.let { append(" sessions=").append(it) }
                metrics.taskCount?.let { append(" tasks=").append(it) }
                metrics.resourceCount?.let { append(" resources=").append(it) }
                metrics.inputChars?.let { append(" inputChars=").append(it) }
                metrics.outputChars?.let { append(" outputChars=").append(it) }
                metrics.inputBytes?.let { append(" inputBytes=").append(it) }
                metrics.outputBytes?.let { append(" outputBytes=").append(it) }
                metrics.persisted?.let { append(" persisted=").append(it) }
                append(" elapsedMs=").append(elapsedMs)
                append(" active=").append(active)
                append(" heapUsedBytes=").append(heap.usedBytes)
                append(" heapMaxBytes=").append(heap.maxBytes)
                append(" heapPercent=").append(heap.percent)
                append(" sampled=").append(sampled)
                error?.let {
                    append(" error=").append(it.javaClass.simpleName)
                    it.localizedMessage?.replace(Regex("\\s+"), " ")?.let { detail ->
                        append(':').append(detail.take(240))
                    }
                }
            }
            if (error == null) AppLog.put(message) else AppLog.put(message, error)
        }
    }

    private data class Heap(val usedBytes: Long, val maxBytes: Long, val percent: Long)

    private fun heap(): Heap {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val max = runtime.maxMemory().coerceAtLeast(1L)
        return Heap(used, max, used * 100L / max)
    }

    private fun isLarge(metrics: Metrics): Boolean =
        (metrics.inputChars ?: 0) >= LARGE_VALUE_CHARS ||
            (metrics.outputChars ?: 0) >= LARGE_VALUE_CHARS ||
            (metrics.inputBytes ?: 0L) >= LARGE_VALUE_BYTES ||
            (metrics.outputBytes ?: 0L) >= LARGE_VALUE_BYTES
}
