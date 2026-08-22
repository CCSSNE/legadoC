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
    PAUSED,
    CANCELLING,
    SUCCEEDED,
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

data class CacheRequest(
    val source: CacheRequestSource,
    val kind: CacheKind,
    val phase: CachePhase,
    val bookUrl: String,
    val bookName: String,
    val units: List<CacheUnitKey>,
    val reviewEnabled: Boolean = false,
)

data class CacheWorkerLease(
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

object CacheLifecycleRules {

    fun canTransition(from: CacheLifecycle, to: CacheLifecycle): Boolean {
        if (from == to) return true
        return when (from) {
            CacheLifecycle.QUEUED -> to == CacheLifecycle.RUNNING ||
                to == CacheLifecycle.CANCELLING ||
                to == CacheLifecycle.CANCELLED
            CacheLifecycle.RUNNING -> to == CacheLifecycle.PAUSED ||
                to == CacheLifecycle.CANCELLING ||
                to == CacheLifecycle.SUCCEEDED ||
                to == CacheLifecycle.FAILED
            CacheLifecycle.PAUSED -> to == CacheLifecycle.RUNNING ||
                to == CacheLifecycle.CANCELLING ||
                to == CacheLifecycle.CANCELLED
            CacheLifecycle.CANCELLING -> to == CacheLifecycle.CANCELLED
            CacheLifecycle.SUCCEEDED,
            CacheLifecycle.FAILED,
            CacheLifecycle.CANCELLED -> false
        }
    }

    fun isTerminal(status: CacheLifecycle): Boolean {
        return status == CacheLifecycle.SUCCEEDED ||
            status == CacheLifecycle.FAILED ||
            status == CacheLifecycle.CANCELLED
    }
}
