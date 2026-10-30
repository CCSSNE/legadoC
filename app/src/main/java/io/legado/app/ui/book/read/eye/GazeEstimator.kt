// 源自 Sun-QAQ/legado (28f718fa8b0dce3df3ca7210211fadbb165383b7)，按本项目阅读器适配。
// SPDX-License-Identifier: GPL-3.0-only
package io.legado.app.ui.book.read.eye

import kotlin.math.abs
import kotlin.math.hypot

internal data class GazeLandmark(val x: Float, val y: Float)

internal enum class GazeTrackingState {
    TRACKING, NO_FACE, MULTIPLE_FACES, EYES_CLOSED, TOO_FAR, INCONSISTENT_EYES, INVALID_LANDMARKS
}

internal data class GazeObservation(val sample: GazeSample?, val state: GazeTrackingState)

/** Pixel coordinates in the upright, unmirrored camera image. No Android dependency. */
internal object GazeEstimator {
    fun estimate(points: List<GazeLandmark>, imageWidth: Int): GazeObservation {
        fun rejected(state: GazeTrackingState) = GazeObservation(null, state)
        if (points.size < 478 || imageWidth <= 0 || points.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return rejected(GazeTrackingState.INVALID_LANDMARKS)
        }
        var issue = GazeTrackingState.EYES_CLOSED
        fun eye(outer: Int, inner: Int, upper: Int, lower: Int, iris: Int): Float? {
            val dx = points[inner].x - points[outer].x
            val dy = points[inner].y - points[outer].y
            val width = hypot(dx, dy)
            if (width < 8f) {
                issue = GazeTrackingState.TOO_FAR
                return null
            }
            val sign = if (dx >= 0) 1f else -1f
            val nx = -dy / width * sign
            val ny = dx / width * sign
            val aperture = ((points[lower].x - points[upper].x) * nx
                    + (points[lower].y - points[upper].y) * ny) / width
            if (aperture < 0.12f) return null
            // Eyelids follow the iris during a vertical glance. Their midpoint would
            // cancel much of that motion. Eye corners provide a stable reference.
            val centerX = (points[outer].x + points[inner].x) / 2
            val centerY = (points[outer].y + points[inner].y) / 2
            return ((points[iris].x - centerX) * nx + (points[iris].y - centerY) * ny) / width
        }
        val right = eye(33, 133, 159, 145, 468) ?: return rejected(issue)
        val left = eye(362, 263, 386, 374, 473) ?: return rejected(issue)
        if (abs(right - left) > 0.08f) return rejected(GazeTrackingState.INCONSISTENT_EYES)
        val dx = points[263].x - points[33].x
        val dy = points[263].y - points[33].y
        val width = hypot(dx, dy)
        if (width < 30f) return rejected(GazeTrackingState.TOO_FAR)
        val noseX = points[1].x - (points[33].x + points[263].x) / 2
        val noseY = points[1].y - (points[33].y + points[263].y) / 2
        return GazeObservation(GazeSample((left + right) / 2,
            (-noseX * dy + noseY * dx) / (width * width),
            (noseX * dx + noseY * dy) / (width * width), width / imageWidth), GazeTrackingState.TRACKING)
    }
}
