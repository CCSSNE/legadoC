package io.legado.app.help.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheTaskStoreTest {

    @Test
    fun bodyAndReviewTasksAggregateWithinOneSession() {
        val store = testStore()
        val session = store.createSession("book")
        val body = store.addTask(session.sessionId, request(CachePhase.BODY))
        val bodyLease = requireNotNull(store.acquireWorker(session.sessionId, body.taskId))

        assertTrue(store.updateUnit(bodyLease, key(1), CacheUnitStatus.RUNNING))
        assertTrue(store.updateUnit(bodyLease, key(1), CacheUnitStatus.SUCCEEDED))
        assertTrue(store.finishTask(bodyLease, CacheResult.SUCCEEDED))
        assertEquals(CacheLifecycle.COMPLETED, store.snapshot.value.sessions.single().status)

        val review = store.addTask(session.sessionId, request(CachePhase.REVIEW, reviewEnabled = true))
        assertEquals(CacheLifecycle.QUEUED, store.snapshot.value.sessions.single().status)
        val reviewLease = requireNotNull(store.acquireWorker(session.sessionId, review.taskId))
        assertEquals(CacheLifecycle.RUNNING, store.snapshot.value.sessions.single().status)

        assertTrue(store.updateUnit(reviewLease, key(1), CacheUnitStatus.RUNNING))
        assertTrue(store.updateUnit(reviewLease, key(1), CacheUnitStatus.FAILED, "snapshot failed"))
        assertTrue(store.finishTask(reviewLease, CacheResult.FAILED))

        val result = store.snapshot.value.sessions.single()
        assertEquals(CacheLifecycle.COMPLETED, result.status)
        assertEquals(CacheResult.PARTIAL, result.result)
        assertEquals(2, result.tasks.size)
    }

    @Test
    fun pauseInvalidatesOldLeaseBeforeResume() {
        val store = testStore()
        val session = store.createSession("book")
        val task = store.addTask(session.sessionId, request(CachePhase.BODY))
        val oldLease = requireNotNull(store.acquireWorker(session.sessionId, task.taskId))

        assertTrue(store.pauseTask(session.sessionId, task.taskId))
        assertFalse(store.updateUnit(oldLease, key(1), CacheUnitStatus.RUNNING))
        assertFalse(store.finishTask(oldLease, CacheResult.SUCCEEDED))
        assertEquals(CacheLifecycle.PAUSING, store.snapshot.value.sessions.single().status)
        assertTrue(store.confirmPaused(session.sessionId, task.taskId))
        assertEquals(CacheLifecycle.PAUSED, store.snapshot.value.sessions.single().status)

        val newLease = requireNotNull(store.resumeTask(session.sessionId, task.taskId))
        assertTrue(newLease.generation > oldLease.generation)
        assertTrue(store.updateUnit(newLease, key(1), CacheUnitStatus.RUNNING))
    }

    @Test
    fun recoveredRunningTaskIsInterruptedAndCanReclaim() {
        val persistence = InMemoryCacheTaskPersistence()
        val first = CacheTaskStore(RecordingCacheLogSink(), persistence)
        val session = first.createSession("book")
        val task = first.addTask(session.sessionId, request(CachePhase.BODY))
        val lease = requireNotNull(first.acquireWorker(session.sessionId, task.taskId))
        assertNotNull(lease)

        val recovered = CacheTaskStore(RecordingCacheLogSink(), persistence)
        val recoveredTask = recovered.snapshot.value.sessions.single().tasks.single()
        assertEquals(CacheLifecycle.INTERRUPTED, recoveredTask.status)

        val reclaimed = requireNotNull(recovered.reclaimWorker(session.sessionId, task.taskId))
        assertTrue(reclaimed.generation > lease.generation)
        assertEquals(CacheLifecycle.RUNNING, recovered.snapshot.value.sessions.single().status)
    }

    @Test
    fun cancelledReviewDoesNotCancelCompletedBodySession() {
        val store = testStore()
        val session = store.createSession("book")
        val body = store.addTask(session.sessionId, request(CachePhase.BODY))
        val review = store.addTask(session.sessionId, request(CachePhase.REVIEW, reviewEnabled = true))
        val bodyLease = requireNotNull(store.acquireWorker(session.sessionId, body.taskId))
        val reviewLease = requireNotNull(store.acquireWorker(session.sessionId, review.taskId))

        store.updateUnit(bodyLease, key(1), CacheUnitStatus.RUNNING)
        store.updateUnit(bodyLease, key(1), CacheUnitStatus.SUCCEEDED)
        store.finishTask(bodyLease, CacheResult.SUCCEEDED)
        assertTrue(store.beginCancel(session.sessionId, review.taskId))
        assertTrue(store.confirmCancelled(session.sessionId, review.taskId))

        val result = store.snapshot.value.sessions.single()
        assertEquals(CacheLifecycle.COMPLETED, result.status)
        assertEquals(CacheResult.PARTIAL, result.result)
    }

    @Test
    fun terminalTaskCannotBeReopened() {
        val store = testStore()
        val session = store.createSession("book")
        val task = store.addTask(session.sessionId, request(CachePhase.BODY))
        val lease = requireNotNull(store.acquireWorker(session.sessionId, task.taskId))

        store.updateUnit(lease, key(1), CacheUnitStatus.RUNNING)
        store.updateUnit(lease, key(1), CacheUnitStatus.SUCCEEDED)
        assertTrue(store.finishTask(lease, CacheResult.SUCCEEDED))

        assertFalse(store.pauseTask(session.sessionId, task.taskId))
        assertFalse(store.resumeTask(session.sessionId, task.taskId) != null)
        assertFalse(store.reclaimWorker(session.sessionId, task.taskId) != null)
        assertFalse(CacheLifecycleRules.canTransition(CacheLifecycle.COMPLETED, CacheLifecycle.RUNNING))
    }

    @Test
    fun reviewEligibilityUsesOnlySuccessfulBodyUnits() {
        val store = testStore()
        val session = store.createSession("book")
        val task = store.addTask(session.sessionId, request(CachePhase.BODY, indexes = listOf(1, 2)))
        val lease = requireNotNull(store.acquireWorker(session.sessionId, task.taskId))

        store.updateUnit(lease, key(1), CacheUnitStatus.RUNNING)
        store.updateUnit(lease, key(1), CacheUnitStatus.SUCCEEDED)
        store.updateUnit(lease, key(2), CacheUnitStatus.RUNNING)
        store.updateUnit(lease, key(2), CacheUnitStatus.FAILED, "body failed")
        store.finishTask(lease, CacheResult.PARTIAL)

        assertEquals(listOf(key(1)), store.reviewEligibleUnits(session.sessionId, task.taskId))
    }

    @Test
    fun allUnitsFailedAfterNormalExecutionIsCompletedWithFailedResult() {
        val store = testStore()
        val session = store.createSession("book")
        val task = store.addTask(session.sessionId, request(CachePhase.BODY))
        val lease = requireNotNull(store.acquireWorker(session.sessionId, task.taskId))

        assertTrue(store.updateUnit(lease, key(1), CacheUnitStatus.RUNNING))
        assertTrue(store.updateUnit(lease, key(1), CacheUnitStatus.FAILED, "body failed"))
        assertTrue(store.finishTask(lease, CacheResult.FAILED, "body failed"))

        val finished = store.snapshot.value.sessions.single().tasks.single()
        assertEquals(CacheLifecycle.COMPLETED, finished.status)
        assertEquals(CacheResult.FAILED, finished.result)
    }

    private fun request(
        phase: CachePhase,
        reviewEnabled: Boolean = false,
        indexes: List<Int> = listOf(1),
    ) = CacheRequest(
        source = CacheRequestSource.SYSTEM,
        kind = CacheKind.TEXT,
        phase = phase,
        bookUrl = "book-url",
        bookName = "book",
        units = indexes.map(::key),
        reviewEnabled = reviewEnabled,
    )

    private fun key(index: Int) = CacheUnitKey("book-url", index)

    private fun testStore() = CacheTaskStore(
        logSink = RecordingCacheLogSink(),
        persistence = InMemoryCacheTaskPersistence(),
    )

    private class RecordingCacheLogSink : CacheLogSink {
        override fun record(event: CacheLogEvent) = Unit
    }
}
