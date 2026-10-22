package com.badmon.shuttleeye.analysis

import android.graphics.Bitmap
import com.badmon.shuttleeye.core.AnalysisOutput
import com.badmon.shuttleeye.core.AnalysisPipeline
import com.badmon.shuttleeye.core.ClassicalMotionDetector
import com.badmon.shuttleeye.core.FrameProcessor
import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.core.ShuttlecockDetector
import com.badmon.shuttleeye.core.TrackPoint
import com.badmon.shuttleeye.media.VideoFrameReader

/** 分析输出（含背景帧位图，对应 iOS AnalysisRunner.Output） */
class Output(
    val track: List<TrackPoint>,
    val analysis: AnalysisOutput,
    val fps: Double,
    val originalWidth: Int,
    val originalHeight: Int,
    val backgroundImage: Bitmap?
)

/** 分析调度器：串联 检测 → 跟踪 → 修复 → 测速 → 物理验证 */
object VideoAnalyzer {

    /** 平台检测器：Android 版使用经典 CV 帧差检测（TrackNet 模型可自行扩展接入） */
    fun makeDetector(): ShuttlecockDetector = ClassicalMotionDetector()

    /** 处理已抽取的帧序列（实时模式与视频模式共用） */
    fun analyze(
        frames: List<VideoFrameReader.Frame>,
        fps: Double,
        calibration: ScaleCalibration?,
        backgroundImage: Bitmap?
    ): Output? {
        val first = frames.firstOrNull() ?: return null
        val originalWidth = first.originalWidth
        val originalHeight = first.originalHeight

        val processor = FrameProcessor(makeDetector())
        for (f in frames) {
            processor.process(f.image, f.time, f.downscale, originalWidth, calibration)
        }
        val tracks = processor.finish()
        return finalize(tracks, fps, originalWidth, originalHeight, calibration, backgroundImage)
    }

    /** 多段轨迹择优并输出 */
    fun finalize(
        tracks: List<List<TrackPoint>>,
        fps: Double,
        originalWidth: Int,
        originalHeight: Int,
        calibration: ScaleCalibration?,
        backgroundImage: Bitmap?
    ): Output? {
        val best = AnalysisPipeline.bestAnalysis(tracks, originalWidth.toDouble(), calibration)
            ?: return null
        return Output(
            track = best.first,
            analysis = best.second,
            fps = fps,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            backgroundImage = backgroundImage
        )
    }
}
