package io.legado.app.help.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AgentControl(val job: Job, private val emit: (String, JSONObject) -> Unit) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val cancellationActions = ConcurrentHashMap<Any, () -> Unit>()
    @Volatile var paused = false
        private set
    @Volatile var waitingInput = false
        private set
    @Volatile var cancelled = false
        private set
    @Volatile var snapshot = JSONObject()
        private set
    @Volatile var cancelReason: String? = null
        private set
    private var answer: Any? = null

    fun check() {
        if (cancelled || !job.isActive) throw CancellationException("Agent 任务已停止")
    }

    fun onCancel(key: Any, action: () -> Unit) {
        cancellationActions[key] = action
        if (cancelled || !job.isActive) action()
    }

    fun removeCancel(key: Any) { cancellationActions.remove(key) }

    fun cancel(reason: String = "用户停止") {
        if (cancelled) return
        cancelReason = reason
        cancelled = true
        cancellationActions.values.forEach { it() }
        lock.withLock { changed.signalAll() }
        job.cancel(CancellationException(reason))
    }

    fun requestPause() { paused = true }

    fun checkpoint(location: JSONObject = JSONObject(), force: Boolean = false) {
        check()
        if (force) paused = true
        if (!paused) return
        snapshot = location
        emit("paused", location)
        lock.withLock {
            while (paused) { check(); changed.await() }
        }
        check()
        emit("running", JSONObject())
    }

    fun resume(input: Any? = null) {
        lock.withLock {
            require(!waitingInput || input != null) { "任务正在等待输入，不能用空输入继续" }
            answer = input
            waitingInput = false
            paused = false
            changed.signalAll()
        }
    }

    fun input(prompt: JSONObject): Any {
        lock.withLock {
            answer = null
            waitingInput = true
            emit("waiting_input", prompt)
            while (waitingInput) { check(); changed.await() }
            check()
            emit("running", JSONObject())
            return answer ?: error("输入未提供")
        }
    }
}
