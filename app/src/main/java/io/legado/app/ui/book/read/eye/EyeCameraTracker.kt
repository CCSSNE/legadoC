// 源自 Sun-QAQ/legado (28f718fa8b0dce3df3ca7210211fadbb165383b7)，按本项目阅读器适配。
// SPDX-License-Identifier: GPL-3.0-only
package io.legado.app.ui.book.read.eye

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import io.legado.app.constant.AppLog
import java.nio.ByteBuffer

/** Owns camera and inference on one worker. Images never leave the device or get saved. */
internal class EyeCameraTracker(
    private val context: Context,
    private val displayDegrees: Int,
    private val onSample: (GazeObservation, Long) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val thread = HandlerThread("EyePageTracker").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = true
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var landmarker: FaceLandmarker? = null
    private var rotation = 0
    private val cadence = GazeProcessingCadence()
    private var reportAt = 0L
    private var frameCount = 0
    private var maxProcessingMs = 0L
    private val trackingCounts = mutableMapOf<GazeTrackingState, Int>()

    init {
        worker.post {
            try {
                if (active) open()
            } catch (e: Exception) {
                fail(e)
            } catch (e: LinkageError) {
                fail(e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
        } ?: error("未找到前置摄像头")
        val characteristics = manager.getCameraCharacteristics(id)
        rotation = ((characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                + displayDegrees) % 360
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val size = sizes.filter { it.width <= 640 && it.height <= 640 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: error("前置摄像头不支持图像采集")
        AppLog.put("眼动摄像头准备：camera=$id size=${size.width}x${size.height} rotation=$rotation display=$displayDegrees")
        landmarker = FaceLandmarker.createFromOptions(context,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetBuffer(modelBuffer(context)).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(2)
                .setMinFaceDetectionConfidence(0.6f)
                .setMinFacePresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .build())
        if (!active) return
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            .apply { setOnImageAvailableListener({ processImage(it) }, worker) }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!active) {
                    device.close()
                    return
                }
                camera = device
                createSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                fail(IllegalStateException("前置摄像头已断开"))
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                fail(IllegalStateException("无法打开前置摄像头 ($error)"))
            }
        }, worker)
    }

    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice) {
        try {
            val surface = reader?.surface ?: return
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!active) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            }.build()
                        configured.setRepeatingRequest(request, null, worker)
                    } catch (e: Exception) {
                        fail(e)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    fail(IllegalStateException("无法启动前置摄像头采集"))
                }
            }, worker)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun processImage(source: ImageReader) {
        if (!active) return
        try {
            val image = source.acquireLatestImage() ?: return
            image.use {
                val now = SystemClock.uptimeMillis()
                if (!cadence.shouldProcess(now)) return
                val bitmap = toBitmap(image)
                val rotated = if (rotation == 0) bitmap else Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true)
                if (rotated !== bitmap) bitmap.recycle()
                val input = BitmapImageBuilder(rotated).build()
                try {
                    val result = landmarker?.detectForVideo(input, now)
                    val faces = result?.faceLandmarks().orEmpty()
                    // Conditional branches avoid an R8 8.13.19 integer-switch
                    // optimization crash while preserving the face-count cases.
                    val observation = when {
                        faces.isEmpty() -> GazeObservation(null, GazeTrackingState.NO_FACE)
                        faces.size == 1 -> GazeEstimator.estimate(faces[0].map {
                            GazeLandmark(it.x() * rotated.width, it.y() * rotated.height)
                        }, rotated.width)
                        else -> GazeObservation(null, GazeTrackingState.MULTIPLE_FACES)
                    }
                    val previousInterval = cadence.intervalMs
                    cadence.onObservation(observation.sample != null, now)
                    if (cadence.intervalMs != previousInterval) {
                        AppLog.putDebug("眼动识别频率调整：intervalMs=${cadence.intervalMs} state=${observation.state}")
                    }
                    frameCount++
                    trackingCounts[observation.state] = (trackingCounts[observation.state] ?: 0) + 1
                    maxProcessingMs = maxOf(maxProcessingMs, SystemClock.uptimeMillis() - now)
                    if (now - reportAt >= 5000L) {
                        reportAt = now
                        AppLog.putDebug("眼动识别统计：frames=$frameCount states=$trackingCounts maxProcessingMs=$maxProcessingMs intervalMs=${cadence.intervalMs}")
                    }
                    main.post { if (active) onSample(observation, now) }
                } finally {
                    input.close()
                    rotated.recycle()
                }
            }
        } catch (e: Exception) {
            fail(e)
        } catch (e: LinkageError) {
            fail(e)
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val planes = image.planes
        val pixels = IntArray(image.width * image.height)
        // Read each plane once. Per-pixel access to the native direct ByteBuffer is
        // expensive on some devices and can make otherwise usable samples stale.
        val bytes = planes.map { plane ->
            val buffer = plane.buffer.duplicate()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        fun value(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            return bytes[plane][y * p.rowStride + x * p.pixelStride].toInt() and 255
        }
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val luma = (value(0, x, y) - 16).coerceAtLeast(0)
            val u = value(1, x / 2, y / 2) - 128
            val v = value(2, x / 2, y / 2) - 128
            val r = ((298 * luma + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luma - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luma + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[y * image.width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun fail(error: Throwable) {
        if (!active) return
        main.post { onError(error) }
        stop()
    }

    fun stop() {
        if (!active) return
        // Invalidate queued samples synchronously before closing worker-owned resources.
        active = false
        worker.post {
            runCatching { session?.close() }.onFailure { AppLog.put("关闭眼动摄像头会话失败", it) }
            runCatching { camera?.close() }.onFailure { AppLog.put("关闭眼动摄像头失败", it) }
            runCatching { reader?.close() }.onFailure { AppLog.put("关闭眼动图像采集失败", it) }
            runCatching { landmarker?.close() }.onFailure { AppLog.put("关闭眼动识别模型失败", it) }
            session = null
            camera = null
            reader = null
            landmarker = null
            thread.quitSafely()
        }
    }

    private companion object {
        private var model: ByteBuffer? = null

        @Synchronized
        fun modelBuffer(context: Context): ByteBuffer {
            // MediaPipe rewrites the cached file for every relative asset path; an
            // overlapping tracker could read a truncated mmap during restart.
            val bytes = model ?: context.assets.open("eye_page/face_landmarker.task").use { stream ->
                val data = stream.readBytes()
                ByteBuffer.allocateDirect(data.size).apply {
                    put(data)
                    rewind()
                }.also { model = it }
            }
            return bytes.asReadOnlyBuffer()
        }
    }
}
