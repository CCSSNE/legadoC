// 源自 Sun-QAQ/legado (28f718fa8b0dce3df3ca7210211fadbb165383b7)，按本项目阅读器适配。
// SPDX-License-Identifier: GPL-3.0-only
package io.legado.app.ui.book.read.eye

import kotlin.math.abs
import kotlin.math.max

/** Eye-relative coordinates; head pose is used only to reject movement, never to turn pages. */
internal data class GazeSample(
    val vertical: Float,
    val headPitch: Float,
    val headYaw: Float,
    val faceScale: Float
)

/** All times are monotonic milliseconds. Kept independent of Android for gesture tests. */
internal class GazeGestureDetector {
    private var top = 0f
    private var bottom = 0f
    var calibrated = false
        private set
    var bottomDwellMs = 0L
        private set
    private var previousBottom = false
    private var upperSince: Long? = null
    private var bottomPose: GazeSample? = null
    var armed = false
        private set
    var armedUntil = 0L
        private set
    var state = "waiting_bottom"
        private set
    private var blockedUntil = 0L
    private var lastFrame: Long? = null
    private var lastValid: Long? = null
    private var blinking = false

    fun calibrate(top: Float, bottom: Float, noise: Float = 0f): Boolean {
        calibrated = top.isFinite() && bottom.isFinite() && noise.isFinite()
                && abs(bottom - top) >= max(0.008f, 3f * noise)
        this.top = top
        this.bottom = bottom
        reset()
        return calibrated
    }

    fun reset(now: Long = 0L, cooldown: Boolean = false, reason: String = "external") {
        bottomDwellMs = 0L
        previousBottom = false
        upperSince = null
        bottomPose = null
        armed = false
        armedUntil = 0L
        lastFrame = null
        lastValid = null
        blinking = false
        state = reason
        if (cooldown) blockedUntil = now + 3000L
    }

    fun position(sample: GazeSample): Float = (sample.vertical - top) / (bottom - top)

    fun expireReadyWindow(now: Long): Boolean {
        if (!armed || now < armedUntil) return false
        reset(reason = "transition_timeout")
        return true
    }

    /** Keep the ready window, but require fresh upward evidence after a bad frame. */
    fun interruptTracking(reason: String) {
        if (!armed) {
            reset(reason = reason)
            return
        }
        previousBottom = false
        upperSince = null
        lastFrame = null
        lastValid = null
        blinking = false
        state = reason
    }

    fun update(sample: GazeSample?, now: Long, eyesClosed: Boolean = false): Boolean {
        if (!calibrated) return false
        if (expireReadyWindow(now)) return false
        // A missing camera frame is different from a reported blink.
        lastFrame?.let { if (now - it > 350L || now <= it) interruptTracking("camera_gap") }
        lastFrame = now
        if (sample == null) {
            if (eyesClosed && lastValid?.let { now - it <= 500L } == true) {
                blinking = true
                previousBottom = false
                upperSince = null // Reopening must provide fresh, sustained upward evidence.
                state = "blink"
            } else {
                interruptTracking(if (eyesClosed) "long_blink" else "tracking_lost")
            }
            return false
        }
        if (!sample.vertical.isFinite() || !sample.headPitch.isFinite()
            || !sample.headYaw.isFinite() || !sample.faceScale.isFinite() || sample.faceScale <= 0f) {
            interruptTracking("invalid_sample")
            return false
        }
        if (blinking && lastValid?.let { now - it > 500L } == true) interruptTracking("long_blink")
        val elapsed = lastValid?.let { (now - it).coerceIn(0L, 250L) } ?: 0L
        lastValid = now
        blinking = false
        if (now < blockedUntil) {
            state = "cooldown"
            return false
        }
        val position = position(sample)
        val pose = bottomPose
        if (pose != null && (abs(sample.headPitch - pose.headPitch) > 0.12f
                    || abs(sample.headYaw - pose.headYaw) > 0.12f
                    || sample.faceScale / pose.faceScale !in 0.8f..1.2f)) {
            interruptTracking("head_moved")
            return false
        }
        val bottomThreshold = if (bottomPose == null) 0.85f else 0.78f
        if (armed) {
            if (position >= bottomThreshold) {
                armedUntil = now + READY_WINDOW_MS
                upperSince = null
                state = "armed"
            } else if (position in -0.5f..0.45f) {
                state = "upward_dwell"
                if (upperSince == null) upperSince = now
                if (now - upperSince!! >= 180L) {
                    reset(now, cooldown = true, reason = "turned")
                    return true
                }
            } else {
                upperSince = null
                state = "waiting_upward"
            }
            return false
        }
        // Enter at the lower part of the page; retain the dwell across small gaze jitter.
        // Looking below the calibration target is still a bottom gaze.
        if (position >= bottomThreshold) {
            if (bottomPose == null) {
                bottomPose = sample
            }
            if (previousBottom) bottomDwellMs += elapsed
            previousBottom = true
            upperSince = null
            if (bottomDwellMs >= 100L) {
                armed = true
                armedUntil = now + READY_WINDOW_MS
            }
            state = if (armed) "armed" else "bottom_dwell"
        } else {
            reset(reason = "waiting_bottom")
        }
        return false
    }

    private companion object {
        const val READY_WINDOW_MS = 3000L
    }
}

internal data class GazeCalibrationPoint(val sample: GazeSample, val noise: Float, val sampleCount: Int) {
    fun hasSimilarPose(other: GazeCalibrationPoint): Boolean =
        abs(sample.headPitch - other.sample.headPitch) <= 0.12f
                && abs(sample.headYaw - other.sample.headYaw) <= 0.12f
                && sample.faceScale / other.sample.faceScale in 0.8f..1.2f
}

/** Accumulate valid observation time; a natural blink pauses rather than erases progress. */
internal class GazeCalibration {
    private val samples = ArrayList<Pair<Long, GazeSample>>()
    private var startedAt: Long? = null
    private var lastValidAt: Long? = null
    private var previousWasValid = false
    private var validTime = 0L
    private var pose: GazeSample? = null
    var unstable = false
        private set
    val progress: Int get() = minOf(99, (validTime * 100 / 1500L).toInt(), samples.size * 100 / 12)

    fun reset() {
        samples.clear()
        startedAt = null
        lastValidAt = null
        previousWasValid = false
        validTime = 0L
        pose = null
        unstable = false
    }

    fun add(sample: GazeSample?, now: Long): GazeCalibrationPoint? {
        val last = lastValidAt
        if (last != null && now - last > 700L) reset()
        if (sample == null || !sample.vertical.isFinite()) {
            previousWasValid = false
            return null
        }
        val reference = pose
        if (reference != null && (abs(sample.headPitch - reference.headPitch) > 0.12f
                    || abs(sample.headYaw - reference.headYaw) > 0.12f
                    || sample.faceScale / reference.faceScale !in 0.8f..1.2f)) reset()
        if (pose == null) pose = sample
        val start = startedAt ?: now.also { startedAt = it }
        if (now - start >= 700L) {
            // Don't count blink time or a camera stall as time spent looking at a target.
            lastValidAt?.let {
                if (previousWasValid && it >= start + 700L) validTime += (now - it).coerceIn(0L, 250L)
            }
            samples.add(now to sample)
            samples.removeAll { now - it.first > 5000L }
        }
        lastValidAt = now
        previousWasValid = true
        if (validTime < 1500L || samples.size < 12) return null
        fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        }
        val values = samples.map { it.second.vertical }.sorted()
        val vertical = median(values)
        val noise = max(median(values.map { abs(it - vertical) }) * 1.4826f,
            (values[values.size * 9 / 10] - values[values.size / 10]) / 2.56f)
        unstable = noise > 0.025f
        if (unstable) return null
        return GazeCalibrationPoint(GazeSample(vertical,
            median(samples.map { it.second.headPitch }), median(samples.map { it.second.headYaw }),
            median(samples.map { it.second.faceScale })), noise, samples.size)
    }
}
