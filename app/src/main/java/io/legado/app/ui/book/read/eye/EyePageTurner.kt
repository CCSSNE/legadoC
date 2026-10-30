// 源自 Sun-QAQ/legado (28f718fa8b0dce3df3ca7210211fadbb165383b7)，按本项目阅读器适配。
// SPDX-License-Identifier: GPL-3.0-only
package io.legado.app.ui.book.read.eye

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setTextIfNotEqual
import kotlin.math.abs
import java.util.Locale

/** Main-thread coordinator. Calibration covers the same area as the reading page. */
class EyePageTurner(
    private val activity: Activity,
    private val root: FrameLayout,
    private val readerContent: View,
    private val readingBlockReason: () -> String?,
    private val canCalibrate: () -> Boolean,
    private val onReadyChanged: (Boolean) -> Unit,
    private val onNextPage: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onCancel: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val detector = GazeGestureDetector()
    private val calibration = GazeCalibration()
    private var camera: EyeCameraTracker? = null
    private var generation = 0
    private var overlay: FrameLayout? = null
    private var target: View? = null
    private var gazePoint: View? = null
    private var gazePointEnabled = false
    private var message: TextView? = null
    private var status: TextView? = null
    private enum class CalibrationStage { TOP, BOTTOM, MIDPOINT }
    private var calibrationStage = CalibrationStage.TOP
    private var instructions: ScrollView? = null
    private var topPoint: GazeCalibrationPoint? = null
    private var bottomPoint: GazeCalibrationPoint? = null
    private var retryCount = 0
    private var retryHint: String? = null
    private var calibrationPreparing = false
    private var calibrationPaused = false
    private var calibrationReadyAt = 0L
    private val calibrationCountdown = object : Runnable {
        override fun run() {
            if (overlay == null || !calibrationPreparing) return
            if (!canCalibrate()) {
                pauseCalibration()
                return
            }
            if (calibrationPaused) {
                beginCalibrationStage()
                return
            }
            val remaining = calibrationReadyAt - SystemClock.uptimeMillis()
            if (remaining > 0L) {
                status?.text = activity.getString(R.string.eye_page_calibration_countdown,
                    ((remaining + 999L) / 1000L).toInt())
                handler.postDelayed(this, minOf(remaining, 1000L))
            } else {
                calibrationPreparing = false
                updateTarget()
                lastIssue = R.string.eye_page_sampling
                status?.text = activity.getString(lastIssue, 0)
                handler.postDelayed(timeout, CALIBRATION_TIMEOUT_MS)
            }
        }
    }
    private var lastIssue = R.string.eye_page_camera_starting
    private var staleFrames = 0
    private var receivedFrames = 0
    private var lastReadingLog = 0L
    private val readingStates = linkedMapOf<String, Int>()
    private var minPosition = Float.POSITIVE_INFINITY
    private var maxPosition = Float.NEGATIVE_INFINITY
    private var readyHintVisible = false
    private val hintTimeout = Runnable {
        detector.expireReadyWindow(SystemClock.uptimeMillis())
        updateReadyHint(detector.armed)
    }
    val isCalibrating: Boolean get() = overlay != null

    fun setGazePointEnabled(enabled: Boolean) {
        gazePointEnabled = enabled
        if (!enabled) hideGazePoint()
    }

    fun refreshGazePointStyle() {
        val marker = gazePoint ?: return
        val color = ReadBookConfig.eyePageGazePointColor
        val size = dp(ReadBookConfig.eyePageGazePointSize.coerceIn(MIN_GAZE_POINT_SIZE_DP, MAX_GAZE_POINT_SIZE_DP))
        // 单一均匀圆点：颜色强制不透明，整体透明度只由 eyePageGazePointAlpha 控制，
        // 不再有“圈内/圈外”两层不同透明度的结构。
        marker.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color or 0xFF000000.toInt())
        }
        marker.alpha = ReadBookConfig.eyePageGazePointAlpha.coerceIn(0, 100) / 100f
        marker.layoutParams = (marker.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL)).apply {
            width = size
            height = size
        }
    }

    fun recalibrate() {
        if (camera == null) {
            start()
            return
        }
        detector.calibrate(0f, 0f)
        updateReadyHint(false)
        hideGazePoint()
        if (overlay != null) hideCalibration()
        showCalibration()
    }

    private val timeout = Runnable {
        if (overlay != null) {
            AppLog.put("眼动校准超时：stage=${stage()} frames=$receivedFrames stale=$staleFrames progress=${calibration.progress} issue=${issueMessage()}")
            onError(IllegalStateException(activity.getString(R.string.eye_page_calibration_detail,
                issueMessage())))
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (camera != null) return
        val current = ++generation
        val degrees = when (activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        detector.reset(SystemClock.uptimeMillis(), cooldown = true)
        updateReadyHint(false)
        if (!detector.calibrated) {
            val saved = GazeCalibrationProfile.decode(ReadBookConfig.eyePageCalibration)
            if (saved != null && detector.calibrate(saved.top, saved.bottom, saved.noise)) {
                detector.reset(SystemClock.uptimeMillis(), cooldown = true)
                AppLog.putDebug("眼动翻页已复用首次校准：top=${saved.top} bottom=${saved.bottom} noise=${saved.noise}")
            } else {
                showCalibration()
            }
        }
        camera = EyeCameraTracker(activity.applicationContext, degrees, { observation, time ->
            if (current == generation) accept(observation, time)
        }, { error ->
            if (current == generation) onError(error)
        })
    }

    private fun accept(observation: GazeObservation, time: Long) {
        receivedFrames++
        val sample = observation.sample
        if (overlay != null) {
            if (!canCalibrate()) {
                pauseCalibration()
                return
            }
            if (calibrationPaused) {
                beginCalibrationStage()
                return
            }
            // Preparation frames must never enter the calibration, even if delivered late.
            if (calibrationPreparing || time < calibrationReadyAt) return
        }
        // Discard results queued while the UI thread was busy.
        if (SystemClock.uptimeMillis() - time > 350L) {
            staleFrames++
            lastIssue = R.string.eye_page_processing_slow
            status?.setTextIfNotEqual(activity.getString(lastIssue))
            detector.interruptTracking("stale_frame")
            detector.expireReadyWindow(SystemClock.uptimeMillis())
            updateReadyHint(detector.armed)
            calibration.reset()
            hideGazePoint()
            if (overlay == null) recordReading(sample, time, "stale_frame")
            return
        }
        if (overlay != null) {
            val point = calibration.add(sample, time)
            lastIssue = when {
                calibration.unstable -> R.string.eye_page_gaze_unstable
                observation.state == GazeTrackingState.NO_FACE -> R.string.eye_page_no_face
                observation.state == GazeTrackingState.MULTIPLE_FACES -> R.string.eye_page_multiple_faces
                observation.state == GazeTrackingState.EYES_CLOSED -> R.string.eye_page_eyes_closed
                observation.state == GazeTrackingState.TOO_FAR -> R.string.eye_page_too_far
                observation.state != GazeTrackingState.TRACKING -> R.string.eye_page_eyes_unclear
                else -> R.string.eye_page_sampling
            }
            val feedback = if (lastIssue == R.string.eye_page_sampling) {
                activity.getString(lastIssue, calibration.progress)
            } else activity.getString(lastIssue)
            status?.setTextIfNotEqual(feedback)
            if (point == null) return
            AppLog.put("眼动校准采样完成：stage=${stage()} ${describe(point)}")
            calibration.reset()
            when (calibrationStage) {
                CalibrationStage.TOP -> {
                    topPoint = point
                    calibrationStage = CalibrationStage.BOTTOM
                    beginCalibrationStage()
                }
                CalibrationStage.BOTTOM -> {
                    val top = topPoint ?: return
                    val noise = maxOf(top.noise, point.noise)
                    val samePose = top.hasSimilarPose(point)
                    // The active detector stays uncalibrated until the midpoint passes.
                    val separated = GazeGestureDetector().calibrate(top.sample.vertical, point.sample.vertical, noise)
                    AppLog.put("眼动校准比较：top=${describe(top)} bottom=${describe(point)} span=${abs(point.sample.vertical - top.sample.vertical)} requiredSpan=${maxOf(0.008f, noise * 3f)} samePose=$samePose separated=$separated")
                    if (samePose && separated) {
                        bottomPoint = point
                        calibrationStage = CalibrationStage.MIDPOINT
                        beginCalibrationStage()
                    } else {
                        retryCalibration(if (!samePose) R.string.eye_page_pose_changed else R.string.eye_page_gaze_too_close)
                    }
                }
                CalibrationStage.MIDPOINT -> {
                    val top = topPoint ?: return
                    val bottom = bottomPoint ?: return
                    val profile = GazeCalibrationProfile(top.sample.vertical, bottom.sample.vertical,
                        maxOf(top.noise, bottom.noise), point.sample.vertical, point.noise)
                    val samePose = top.hasSimilarPose(point) && bottom.hasSimilarPose(point)
                    val valid = profile.hasValidMidpoint()
                    AppLog.put("眼动中点校验：position=${(profile.midpoint - profile.top) / (profile.bottom - profile.top)} noise=${point.noise} samePose=$samePose valid=$valid")
                    if (samePose && valid) {
                        detector.calibrate(profile.top, profile.bottom, profile.noise)
                        ReadBookConfig.eyePageCalibration = profile.encode()
                        hideCalibration()
                        detector.reset(time, cooldown = true)
                        android.widget.Toast.makeText(activity, R.string.eye_page_ready, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        retryCalibration(if (!samePose) R.string.eye_page_pose_changed else R.string.eye_page_midpoint_failed)
                    }
                }
            }
            return
        }
        val blocked = readingBlockReason()
        if (blocked != null) {
            hideGazePoint()
            detector.reset(reason = "paused:$blocked")
            updateReadyHint(false)
            recordReading(sample, time, detector.state)
            return
        }
        updateGazePoint(sample.takeIf { observation.state == GazeTrackingState.TRACKING })
        val wasArmed = detector.armed
        val turn = detector.update(sample, time, observation.state == GazeTrackingState.EYES_CLOSED)
        updateReadyHint(detector.armed)
        if (!wasArmed && detector.armed) {
            AppLog.putDebug("眼动翻页已确认底部注视：dwell=${detector.bottomDwellMs}ms position=${sample?.let(detector::position)}")
        }
        recordReading(sample, time, detector.state)
        if (turn) {
            AppLog.put("眼动翻页动作已识别：position=${sample?.let(detector::position)}")
            onNextPage()
        }
    }

    private fun updateReadyHint(visible: Boolean) {
        handler.removeCallbacks(hintTimeout)
        if (visible) handler.postAtTime(hintTimeout, detector.armedUntil)
        if (readyHintVisible == visible) return
        readyHintVisible = visible
        onReadyChanged(visible)
    }

    private fun recordReading(sample: GazeSample?, time: Long, state: String) {
        readingStates[state] = (readingStates[state] ?: 0) + 1
        sample?.let {
            val position = detector.position(it)
            if (position.isFinite()) {
                minPosition = minOf(minPosition, position)
                maxPosition = maxOf(maxPosition, position)
            }
        }
        if (time - lastReadingLog < 5000L) return
        lastReadingLog = time
        AppLog.putDebug("眼动阅读统计：states=$readingStates positionRange=$minPosition..$maxPosition armed=${detector.armed} bottomDwell=${detector.bottomDwellMs} stale=$staleFrames pose=${sample?.let { "pitch=${it.headPitch} yaw=${it.headYaw} scale=${it.faceScale}" }}")
        readingStates.clear()
        minPosition = Float.POSITIVE_INFINITY
        maxPosition = Float.NEGATIVE_INFINITY
    }

    private fun updateGazePoint(sample: GazeSample?) {
        if (!gazePointEnabled || sample == null || !detector.calibrated || root.height <= 0) {
            hideGazePoint()
            return
        }
        val position = detector.position(sample)
        if (!position.isFinite()) {
            hideGazePoint()
            return
        }
        val marker = gazePoint ?: View(activity).apply {
            elevation = dp(4).toFloat()
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also {
            gazePoint = it
            root.addView(it, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            refreshGazePointStyle()
        }
        val markerSize = dp(ReadBookConfig.eyePageGazePointSize.coerceIn(MIN_GAZE_POINT_SIZE_DP, MAX_GAZE_POINT_SIZE_DP))
        val top = dp(56)
        val bottom = (root.height - dp(40)).coerceAtLeast(dp(56))
        val centerY = top + ((bottom - top) * position.coerceIn(0f, 1f)).toInt()
        marker.translationX = 0f
        marker.translationY = (centerY - markerSize / 2).toFloat()
        marker.visibility = View.VISIBLE
    }

    private fun hideGazePoint() {
        gazePoint?.visibility = View.GONE
    }

    private fun clearGazePoint() {
        gazePoint?.let(root::removeView)
        gazePoint = null
    }

    private fun showCalibration() {
        hideGazePoint()
        calibration.reset()
        calibrationStage = CalibrationStage.TOP
        topPoint = null
        bottomPoint = null
        retryCount = 0
        retryHint = null
        staleFrames = 0
        receivedFrames = 0
        lastIssue = R.string.eye_page_camera_starting
        val backgroundColor = activity.bottomBackground
        val textColor = activity.getPrimaryTextColor(ColorUtils.isColorLight(backgroundColor))
        val accent = activity.accentColor
        val panel = FrameLayout(activity).apply {
            setBackgroundColor(backgroundColor)
            isClickable = true
            // Block manual page turns throughout calibration and midpoint validation.
            isFocusable = true
        }
        target = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            contentDescription = activity.getString(R.string.eye_page_target)
        }
        panel.addView(target, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(56) })
        val center = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        message = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(textColor)
        }
        center.addView(message, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(textColor)
            setText(R.string.eye_page_camera_starting)
        }
        center.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        center.addView(TextView(activity).apply {
            setText(R.string.cancel)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { onCancel() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        instructions = ScrollView(activity).apply {
            isFillViewport = true
            addView(center)
        }
        panel.addView(instructions, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInstructionsLayout() }
        // Calibration is a full-screen reading step, not a floating surface.
        // Keep the reader laid out but hidden until calibration ends.
        readerContent.visibility = View.INVISIBLE
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = panel
        beginCalibrationStage()
    }

    private fun beginCalibrationStage() {
        handler.removeCallbacks(calibrationCountdown)
        handler.removeCallbacks(timeout)
        calibration.reset()
        calibrationPaused = false
        calibrationPreparing = true
        calibrationReadyAt = SystemClock.uptimeMillis() + CALIBRATION_PREPARATION_MS
        updateTarget()
        calibrationCountdown.run()
    }

    private fun pauseCalibration() {
        if (!calibrationPaused) {
            calibration.reset()
            calibrationPaused = true
            calibrationPreparing = true
            handler.removeCallbacks(timeout)
            lastIssue = R.string.eye_page_calibration_paused
            updateTarget()
            status?.setText(lastIssue)
        }
        handler.removeCallbacks(calibrationCountdown)
        handler.postDelayed(calibrationCountdown, 200L)
    }

    private fun updateTarget() {
        target?.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24),
            when (calibrationStage) {
                CalibrationStage.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                CalibrationStage.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                CalibrationStage.MIDPOINT -> Gravity.CENTER
            }).apply {
            if (calibrationStage != CalibrationStage.MIDPOINT) {
                topMargin = dp(56)
                bottomMargin = dp(40)
            }
        }
        updateInstructionsLayout()
        val instruction = activity.getString(when {
            calibrationPreparing && calibrationStage == CalibrationStage.MIDPOINT -> R.string.eye_page_prepare_midpoint
            calibrationPreparing && calibrationStage == CalibrationStage.BOTTOM -> R.string.eye_page_prepare_bottom
            calibrationPreparing -> R.string.eye_page_prepare_top
            calibrationStage == CalibrationStage.MIDPOINT -> R.string.eye_page_calibrate_midpoint
            calibrationStage == CalibrationStage.BOTTOM -> R.string.eye_page_calibrate_bottom
            else -> R.string.eye_page_calibrate_top
        })
        message?.text = instruction + (retryHint?.let { "\n\n$it" } ?: "")
    }

    private fun updateInstructionsLayout() {
        val view = instructions ?: return
        val midpoint = calibrationStage == CalibrationStage.MIDPOINT
        // Keep the screen-center target clear; short windows can scroll the instructions.
        val height = if (midpoint) ((overlay?.height ?: 0) / 2 - dp(40)).coerceAtLeast(1) else WRAP_CONTENT
        val gravity = if (midpoint) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        val margin = if (midpoint) dp(16) else 0
        val current = view.layoutParams as FrameLayout.LayoutParams
        if (current.height != height || current.gravity != gravity || current.bottomMargin != margin) {
            view.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, height, gravity).apply { bottomMargin = margin }
        }
    }

    private fun retryCalibration(reason: Int) {
        lastIssue = reason
        if (retryCount >= 1) {
            onError(IllegalStateException(activity.getString(R.string.eye_page_calibration_detail, activity.getString(reason))))
            return
        }
        retryCount++
        calibration.reset()
        calibrationStage = CalibrationStage.TOP
        topPoint = null
        bottomPoint = null
        // Rejected calibration must never leave a valid detector active.
        detector.calibrate(0f, 0f)
        retryHint = activity.getString(reason)
        beginCalibrationStage()
    }

    private fun stage() = calibrationStage.name.lowercase(Locale.ROOT)

    private fun issueMessage(): String = activity.getString(
        if (lastIssue == R.string.eye_page_sampling) R.string.eye_page_gaze_unstable else lastIssue)

    private fun describe(point: GazeCalibrationPoint): String = String.format(Locale.ROOT,
        "gaze=%.5f noise=%.5f pitch=%.4f yaw=%.4f scale=%.4f count=%d",
        point.sample.vertical, point.noise, point.sample.headPitch, point.sample.headYaw,
        point.sample.faceScale, point.sampleCount)

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()

    private fun hideCalibration() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(calibrationCountdown)
        calibrationPreparing = false
        calibrationPaused = false
        calibrationReadyAt = 0L
        overlay?.let(root::removeView)
        readerContent.visibility = View.VISIBLE
        overlay = null
        message = null
        status = null
        target = null
        instructions = null
        topPoint = null
        bottomPoint = null
        calibration.reset()
    }

    fun resetGesture() {
        detector.reset(SystemClock.uptimeMillis(), cooldown = true)
        updateReadyHint(false)
    }

    fun stop() {
        generation++
        camera?.stop()
        camera = null
        hideCalibration()
        clearGazePoint()
        resetGesture()
    }

    private companion object {
        const val CALIBRATION_PREPARATION_MS = 3000L
        const val CALIBRATION_TIMEOUT_MS = 20000L
        const val MIN_GAZE_POINT_SIZE_DP = 8
        const val MAX_GAZE_POINT_SIZE_DP = 64
    }
}
