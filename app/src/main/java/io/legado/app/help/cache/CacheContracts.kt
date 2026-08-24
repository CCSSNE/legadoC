package io.legado.app.help.cache

/** A cache request source. The source is diagnostic metadata, not a scheduler. */
enum class CacheRequestSource {
    CACHE_ACTIVITY,
    CACHE_MANAGE,
    READER,
    AUTO_PRECACHE,
    SYSTEM,
}

/** The worker domain. Review is a phase of a text task, not a separate session. */
enum class CacheKind {
    TEXT,
    AUDIO,
    VIDEO,
}

enum class CachePhase {
    BODY,
    REVIEW,
    MEDIA,
}

/** Operational lifecycle. PARTIAL deliberately does not belong here. */
enum class CacheLifecycle {
    QUEUED,
    RUNNING,
    PAUSING,
    PAUSED,
    INTERRUPTED,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** Aggregated unit/session result. A task may finish normally with a PARTIAL result. */
enum class CacheResult {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
}

enum class CacheUnitStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REVIEW_ELIGIBLE,
    REVIEW_BLOCKED,
    NOT_APPLICABLE,
    CANCELLED,
}

data class CacheUnitKey(
    val bookUrl: String,
    val chapterIndex: Int,
)

data class CacheUnitState(
    val key: CacheUnitKey,
    val status: CacheUnitStatus = CacheUnitStatus.PENDING,
    val error: String? = null,
    val updatedAt: Long = 0L,
)

/** Exact failed review buttons selected for a cache-management retry. */
data class CacheReviewRetryTarget(
    val unitKey: CacheUnitKey,
    val buttonSources: List<String>,
)

data class CacheRequest(
    val source: CacheRequestSource,
    val kind: CacheKind,
    val phase: CachePhase,
    val bookUrl: String,
    val bookName: String,
    val units: List<CacheUnitKey>,
    val reviewEnabled: Boolean = false,
    /** Empty for normal review caching; non-empty targets only recorded failed buttons. */
    val reviewRetryTargets: List<CacheReviewRetryTarget> = emptyList(),
)

internal data class CacheWorkerLease(
    val sessionId: String,
    val taskId: String,
    val generation: Long,
)

data class CacheTaskState(
    val taskId: String,
    val sessionId: String,
    val source: CacheRequestSource,
    val kind: CacheKind,
    val phase: CachePhase,
    val bookUrl: String,
    val bookName: String,
    val units: List<CacheUnitState>,
    val reviewEnabled: Boolean = false,
    /** Persisted with a REVIEW task so a resumed retry cannot widen back to a chapter refresh. */
    val reviewRetryTargets: List<CacheReviewRetryTarget> = emptyList(),
    val status: CacheLifecycle = CacheLifecycle.QUEUED,
    val result: CacheResult? = null,
    val generation: Long = 0L,
    val error: String? = null,
    val updatedAt: Long = 0L,
)

data class CacheSessionState(
    val sessionId: String,
    val title: String,
    val status: CacheLifecycle = CacheLifecycle.QUEUED,
    val result: CacheResult? = null,
    val tasks: List<CacheTaskState> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class CacheSnapshot(
    val sessions: List<CacheSessionState> = emptyList(),
)

/**
 * Non-persistent runtime progress owned by [CacheTaskStore].
 *
 * Lifecycle and unit state remain in [CacheSnapshot]; transport and per-chapter work progress
 * is deliberately reset when a new generation acquires the task.
 */
enum class CacheProgressMode {
    CHAPTERS,
    BYTES,
    SNAPSHOTS,
    INDETERMINATE,
}

data class CacheProgressState(
    val sessionId: String,
    val taskId: String,
    val generation: Long,
    val unitKey: CacheUnitKey? = null,
    val mode: CacheProgressMode,
    val current: Long = 0L,
    val total: Long? = null,
    /** Unit-local failures observed while this progress state is active. */
    val failed: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * Store-owned runtime progress projection. It is not part of [CacheSnapshot] persistence.
 * [displaySessionId] is selected by the Store and remains stable across ordinary progress ticks.
 * [display] is the current task/unit progress within that session.
 */
data class CacheProgressSnapshot(
    val states: List<CacheProgressState> = emptyList(),
    val displaySessionId: String? = null,
    val display: CacheProgressState? = null,
)

object CacheLifecycleRules {

    fun canTransition(from: CacheLifecycle, to: CacheLifecycle): Boolean {
        if (from == to) return true
        return when (from) {
            CacheLifecycle.QUEUED -> to == CacheLifecycle.RUNNING ||
                to == CacheLifecycle.CANCELLING ||
                to == CacheLifecycle.CANCELLED ||
                to == CacheLifecycle.FAILED
            CacheLifecycle.RUNNING -> to == CacheLifecycle.PAUSING ||
                to == CacheLifecycle.CANCELLING ||
                to == CacheLifecycle.COMPLETED ||
                to == CacheLifecycle.FAILED
            CacheLifecycle.PAUSING -> to == CacheLifecycle.PAUSED ||
                to == CacheLifecycle.CANCELLING
            CacheLifecycle.PAUSED -> to == CacheLifecycle.RUNNING ||
                to == CacheLifecycle.CANCELLING ||
                to == CacheLifecycle.CANCELLED
            CacheLifecycle.INTERRUPTED -> to == CacheLifecycle.RUNNING ||
                to == CacheLifecycle.CANCELLING ||
                to == CacheLifecycle.CANCELLED
            CacheLifecycle.CANCELLING -> to == CacheLifecycle.CANCELLED
            CacheLifecycle.COMPLETED,
            CacheLifecycle.FAILED,
            CacheLifecycle.CANCELLED -> false
        }
    }

    fun isTerminal(status: CacheLifecycle): Boolean {
        return status == CacheLifecycle.COMPLETED ||
            status == CacheLifecycle.FAILED ||
            status == CacheLifecycle.CANCELLED
    }
}
