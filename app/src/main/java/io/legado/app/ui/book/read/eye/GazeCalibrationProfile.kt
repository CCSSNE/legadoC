// 源自 Sun-QAQ/legado (28f718fa8b0dce3df3ca7210211fadbb165383b7)，按本项目阅读器适配。
// SPDX-License-Identifier: GPL-3.0-only
package io.legado.app.ui.book.read.eye

import kotlin.math.abs

/** Version 2 requires a verified midpoint; no images or in-progress samples are stored. */
internal data class GazeCalibrationProfile(
    val top: Float,
    val bottom: Float,
    val noise: Float,
    val midpoint: Float,
    val midpointNoise: Float
) {
    fun encode(): String = "2,$top,$bottom,$noise,$midpoint,$midpointNoise"

    fun hasValidMidpoint(): Boolean {
        if (!midpoint.isFinite() || !midpointNoise.isFinite() || midpointNoise < 0f
            || !noise.isFinite() || noise < 0f || !(bottom - top).isFinite()
            || !GazeGestureDetector().calibrate(top, bottom, noise)) return false
        val position = (midpoint - top) / (bottom - top)
        // Reject compressed mappings and leave a two-noise margin below bottom entry.
        return position in 0.3f..0.7f && position + 2f * midpointNoise / abs(bottom - top) < 0.85f
    }

    companion object {
        fun decode(value: String?): GazeCalibrationProfile? {
            val fields = value?.split(',') ?: return null
            // Older two-point profiles must be calibrated again to verify the midpoint.
            if (fields.size != 6 || fields[0] != "2") return null
            val top = fields[1].toFloatOrNull() ?: return null
            val bottom = fields[2].toFloatOrNull() ?: return null
            val noise = fields[3].toFloatOrNull() ?: return null
            val midpoint = fields[4].toFloatOrNull() ?: return null
            val midpointNoise = fields[5].toFloatOrNull() ?: return null
            return GazeCalibrationProfile(top, bottom, noise, midpoint, midpointNoise)
                .takeIf { it.hasValidMidpoint() }
        }
    }
}
