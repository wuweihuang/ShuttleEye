package com.badmon.shuttleeye.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.badmon.shuttleeye.core.GrayscaleImage
import com.badmon.shuttleeye.media.GrayscaleConverter
import java.util.concurrent.Executors

/** 逐帧样本（时间戳为纳秒转秒） */
class FrameSample(
    val gray: GrayscaleImage,
    val timeSec: Double,
    val downscale: Double,
    val width: Int,
    val height: Int
)

/**
 * CameraX 相机服务（简化版，对应 iOS CameraService）：
 * - Preview + ImageAnalysis（背压 KEEP_ONLY_LATEST）
 * - recordClip：开始后收集 duration 秒的灰度帧回调
 * - capturePreviewFrame：取最新一帧位图（用于标定）
 *
 * 说明：CameraX 常规流为 30/60fps；如需 120/240fps 高帧率录制，
 * 建议改用系统相机拍摄慢动作视频后走「视频分析」流程，精度更高。
 */
class CameraService(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFpsChanged: (Int) -> Unit
) {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var running = false

    // 帧计数测 FPS
    private var fpsWindowStart = 0.0
    private var fpsWindowCount = 0

    // 录制采集状态
    private var collecting = false
    private var collectStartNanos = 0L
    private var collectDurationNanos = 0L
    private var collected = ArrayList<FrameSample>()
    private var onCollectDone: ((List<FrameSample>) -> Unit)? = null

    // 最新帧位图（标定用）
    private var latestBitmap: Bitmap? = null
    @Volatile private var captureNext = false
    private var onCapture: ((Bitmap?) -> Unit)? = null

    private val analyzer = ImageAnalysis.Analyzer { proxy -> onFrame(proxy) }

    fun start(previewView: PreviewView) {
        if (running) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                running = true
            } catch (_: Exception) {
            }
        }, mainHandler)
    }

    fun stop() {
        running = false
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
        }
        latestBitmap?.recycle()
        latestBitmap = null
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }

    /** 取最新一帧（异步回调，位图归调用方所有） */
    fun capturePreviewFrame(callback: (Bitmap?) -> Unit) {
        onCapture = callback
        captureNext = true
    }

    /** 收集 duration 秒帧流后回调（回调在主线程） */
    fun recordClip(durationSec: Double, callback: (List<FrameSample>) -> Unit) {
        collected = ArrayList()
        onCollectDone = callback
        collectStartNanos = System.nanoTime()
        collectDurationNanos = (durationSec * 1e9).toLong()
        collecting = true
    }

    private fun onFrame(proxy: ImageProxy) {
        val nowNanos = System.nanoTime()

        // FPS 统计（1s 窗口）
        fpsWindowCount++
        if (fpsWindowStart == 0.0) fpsWindowStart = nowNanos / 1e9
        val elapsed = nowNanos / 1e9 - fpsWindowStart
        if (elapsed >= 1.0) {
            val fps = (fpsWindowCount / elapsed).toInt()
            fpsWindowStart = nowNanos / 1e9
            fpsWindowCount = 0
            mainHandler.post { onFpsChanged(fps) }
        }

        // 标定截图
        if (captureNext) {
            captureNext = false
            val bmp = try {
                GrayscaleConverter.downscaleBitmap(proxy.toBitmap(), 1440)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                onCapture?.invoke(bmp)
                onCapture = null
            }
        }

        // 灰度转换 + 采集
        val conv = try {
            val bmp = proxy.toBitmap()
            val r = GrayscaleConverter.convert(bmp, MAX_DIMENSION)
            if (bmp.isRecyclable && !bmp.isRecycled) bmp.recycle()
            r
        } catch (_: Exception) {
            null
        }

        if (conv != null && collecting) {
            val tSec = nowNanos / 1e9
            collected.add(
                FrameSample(
                    gray = conv.image,
                    timeSec = tSec,
                    downscale = conv.downscale,
                    width = proxy.width,
                    height = proxy.height
                )
            )
            if (nowNanos - collectStartNanos >= collectDurationNanos) {
                collecting = false
                val result = ArrayList(collected)
                mainHandler.post {
                    val cb = onCollectDone
                    onCollectDone = null
                    cb?.invoke(result)
                }
            }
        }

        proxy.close()
    }

    companion object {
        private const val MAX_DIMENSION = 960
    }
}
