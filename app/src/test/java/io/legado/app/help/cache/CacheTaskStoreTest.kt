package io.legado.app.help.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun pauseWaitsForTaskOwnedArtifactCommitAndResumeGetsNewLease() {
        val store = testStore()
        val session = store.createSession("book")
        val task = store.addTask(session.sessionId, request(CachePhase.BODY))
        val oldLease = requireNotNull(store.acquireWorker(session.sessionId, task.taskId))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val commitResult = AtomicBoolean(false)
        val commitThread = Thread {
            commitResult.set(store.commitIfLeaseActive(oldLease) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            })
        }
        commitThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val progressStartedAt = System.nanoTime()
        assertTrue(
            store.updateProgress(
                oldLease,
                key(1),
                CacheProgressMode.BYTES,
                current = 1L,
                total = 2L,
            )
        )
        val progressElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - progressStartedAt)
        assertTrue("artifact IO held the Store lock for ${progressElapsedMs}ms", progressElapsedMs < 500L)

        assertTrue(store.pauseTask(session.sessionId, task.taskId))
        assertFalse(store.commitIfLeaseActive(oldLease) { error("closed gate admitted stale artifact") })

        val pauseComplete = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        val pauseThread = Thread {
            paused.set(store.confirmPaused(session.sessionId, task.taskId))
            pauseComplete.countDown()
        }
        pauseThread.start()
        assertFalse(pauseComplete.await(100, TimeUnit.MILLISECONDS))

        release.countDown()
        assertTrue(pauseComplete.await(1, TimeUnit.SECONDS))
        commitThread.join(1_000)
        pauseThread.join(1_000)
        assertTrue(commitResult.get())
        assertTrue(paused.get())
        assertEquals(CacheLifecycle.PAUSED, store.currentTask(session.sessionId, task.taskId)?.status)

        val resumedLease = requireNotNull(store.resumeTask(session.sessionId, task.taskId))
        assertTrue(resumedLease.generation > oldLease.generation)
    }

    @Test
    fun cancelWaitsForTaskOwnedArtifactCommitAndClosesEveryUnfinishedUnit() {
        val store = testStore()
        val session = store.createSession("book")
        val task = store.addTask(session.sessionId, request(CachePhase.BODY, indexes = listOf(1, 2)))
        val lease = requireNotNull(store.acquireWorker(session.sessionId, task.taskId))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val commitThread = Thread {
            store.commitIfLeaseActive(lease) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        commitThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        assertTrue(store.beginCancel(session.sessionId, task.taskId))
        assertFalse(store.commitIfLeaseActive(lease) { error("cancelled task admitted artifact") })
        val cancelComplete = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val cancelThread = Thread {
            cancelled.set(store.confirmCancelled(session.sessionId, task.taskId))
            cancelComplete.countDown()
        }
        cancelThread.start()
        assertFalse(cancelComplete.await(100, TimeUnit.MILLISECONDS))

        release.countDown()
        assertTrue(cancelComplete.await(1, TimeUnit.SECONDS))
        commitThread.join(1_000)
        cancelThread.join(1_000)
        assertTrue(cancelled.get())
        val cancelledTask = requireNotNull(store.currentTask(session.sessionId, task.taskId))
        assertEquals(CacheLifecycle.CANCELLED, cancelledTask.status)
        assertTrue(cancelledTask.units.all { it.status == CacheUnitStatus.CANCELLED })
    }

    @Test
    fun terminalEffectsRemainPendingAcrossProcessRecoveryUntilAcknowledged() {
        val persistence = InMemoryCacheTaskPersistence()
        val first = CacheTaskStore(RecordingCacheLogSink(), persistence)
        val session = first.createSession("book")
        val task = first.addTask(session.sessionId, request(CachePhase.BODY))
        val lease = requireNotNull(first.acquireWorker(session.sessionId, task.taskId))
        assertTrue(first.updateUnit(lease, key(1), CacheUnitStatus.RUNNING))
        assertTrue(first.updateUnit(lease, key(1), CacheUnitStatus.SUCCEEDED))
        assertTrue(first.finishTask(lease, CacheResult.SUCCEEDED))

        val recovered = CacheTaskStore(RecordingCacheLogSink(), persistence)
        val recoveredTask = requireNotNull(recovered.currentTask(session.sessionId, task.taskId))
        assertEquals(CacheLifecycle.COMPLETED, recoveredTask.status)
        assertTrue(recoveredTask.terminalEffectsPending)
        assertEquals(listOf(task.taskId), recovered.pendingTerminalEffects().map { it.taskId })

        assertTrue(recovered.completeTerminalEffects(session.sessionId, task.taskId))
        val afterAck = CacheTaskStore(RecordingCacheLogSink(), persistence)
        assertFalse(requireNotNull(afterAck.currentTask(session.sessionId, task.taskId)).terminalEffectsPending)
    }

    @Test
    fun recoveredTaskDispatchesOnlyUnfinishedUnits() {
        val sessionId = "session"
        val taskId = "task"
        val initial = CacheSnapshot(
            sessions = listOf(
                CacheSessionState(
                    sessionId = sessionId,
                    title = "book",
                    status = CacheLifecycle.INTERRUPTED,
                    tasks = listOf(
                        CacheTaskState(
                            taskId = taskId,
                            sessionId = sessionId,
                            source = CacheRequestSource.SYSTEM,
                            kind = CacheKind.TEXT,
                            phase = CachePhase.BODY,
                            bookUrl = "book-url",
                            bookName = "book",
                            generation = 4L,
                            status = CacheLifecycle.INTERRUPTED,
                            units = listOf(
                                CacheUnitState(key(1), CacheUnitStatus.SUCCEEDED),
                                CacheUnitState(key(2), CacheUnitStatus.FAILED, "network"),
                                CacheUnitState(key(3), CacheUnitStatus.PENDING),
                                CacheUnitState(key(4), CacheUnitStatus.REVIEW_ELIGIBLE),
                            ),
                        )
                    )
                )
            )
        )
        val store = CacheTaskStore(RecordingCacheLogSink(), InMemoryCacheTaskPersistence(initial))
        val lease = requireNotNull(store.reclaimWorker(sessionId, taskId))
        val recovered = requireNotNull(store.currentTask(sessionId, taskId))

        assertTrue(lease.generation > 4L)
        assertEquals(
            listOf(CacheUnitStatus.SUCCEEDED, CacheUnitStatus.FAILED),
            recovered.units.take(2).map { it.status },
        )
        assertEquals(listOf(key(3), key(4)), recovered.runnableUnits().map { it.key })
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
