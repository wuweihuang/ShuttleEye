package com.badmon.shuttleeye.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.badmon.shuttleeye.core.GrayscaleImage

/** 从相册视频文件提取帧（MediaMetadataRetriever 实现，逐帧回调以控制内存） */
object VideoFrameReader {

    class ReaderException(message: String) : Exception(message)

    /** 逐帧回调（降采样灰度图坐标系 + downscale 倍率） */
    data class Frame(
        val image: GrayscaleImage,
        val time: Double,
        val downscale: Double,
        val originalWidth: Int,
        val originalHeight: Int
    )

    /**
     * 顺序抽帧（OPTION_CLOSEST，保证逐帧时间精度）。
     *
     * Android 适配：MediaMetadataRetriever 逐帧解码较慢且全量帧灰度缓存占用大，
     * 若估计总帧数超过 maxFrames，则均匀抽样（时间戳按采样帧率推算，速度计算不受影响）。
     *
     * @param onFrame 每帧回调（灰度图 + PTS 秒）
     * @param onFirstFrame 第一帧位图（已降采样，用于标定与轨迹背景）
     * @param onProgress 进度 [0,1]
     * @return (fps, 实际抽取帧数)
     */
    fun read(
        context: Context,
        uri: Uri,
        maxFrames: Int = 500,
        maxDimension: Int = 960,
        onFrame: (Frame) -> Unit,
        onFirstFrame: (Bitmap) -> Unit,
        onProgress: (Double) -> Unit
    ): Pair<Double, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0
            val durationUs = durationMs * 1000.0
            if (durationUs <= 0) throw ReaderException("视频时长信息缺失，无法读取")

            val declaredFrames = if (Build.VERSION.SDK_INT >= 28) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_FRAME_COUNT)
                    ?.toIntOrNull() ?: 0
            } else 0
            val captureFps = if (Build.VERSION.SDK_INT >= 28) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull() ?: 0.0
            } else 0.0

            val fps = when {
                captureFps > 1.0 -> captureFps
                declaredFrames > 0 -> declaredFrames / (durationMs / 1000.0)
                else -> 30.0
            }
            val estFrames = if (declaredFrames > 0) declaredFrames
            else (durationMs / 1000.0 * fps).toInt()

            // 内存保护：均匀抽样，实际采样帧率
            val sampledFps = if (estFrames > maxFrames && durationMs > 0) {
                maxFrames * 1000.0 / durationMs
            } else fps
            val stepUs = if (sampledFps > 0) 1_000_000.0 / sampledFps else durationUs / 2.0

            var count = 0
            var origW = 0
            var origH = 0
            var t = 0.0
            while (t < durationUs - 1.0 && count < maxFrames) {
                val bmp = retriever.getFrameAtTime(
                    t.toLong(), MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: break

                if (count == 0) {
                    origW = bmp.width
                    origH = bmp.height
                    onFirstFrame(GrayscaleConverter.downscaleBitmap(bmp, maxDimension))
                }
                val conv = GrayscaleConverter.convert(bmp, maxDimension)
                bmp.recycle()
                if (conv != null) {
                    onFrame(
                        Frame(
                            image = conv.image,
                            time = t / 1e6,
                            downscale = conv.downscale,
                            originalWidth = origW,
                            originalHeight = origH
                        )
                    )
                    count++
                    onProgress(minOf(1.0, t / durationUs))
                }
                t += stepUs
            }

            if (count == 0) throw ReaderException("未能从视频中读取到有效帧")
            onProgress(1.0)
            return sampledFps to count
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
