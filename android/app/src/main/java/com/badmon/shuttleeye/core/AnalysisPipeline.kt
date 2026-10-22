package com.badmon.shuttleeye.core

/** 分析管线输出 */
data class AnalysisOutput(
    val rawPoints: List<TrackPoint>,
    val rectified: List<TrackPoint>,
    val speed: SpeedResult?,
    val physics: PhysicsReport?,
    val quality: Quality
) {
    enum class Quality {
        /** 点数充足，可直接信任 */
        GOOD,
        /** 点数偏少，结果仅供参考 */
        FAIR,
        /** 点数不足，无法给出可靠结论 */
        INSUFFICIENT
    }
}

/** 端到端分析管线：原始轨迹 → 修复 → 测速 → 物理验证 */
object AnalysisPipeline {

    fun analyze(
        track: List<TrackPoint>,
        frameWidth: Double,
        calibration: ScaleCalibration?,
        speedConfig: SpeedCalculator.Config = SpeedCalculator.Config(),
        physicsConfig: PhysicsValidator.Config = PhysicsValidator.Config()
    ): AnalysisOutput {
        // 1. 轨迹修复
        val rectified = TrajectoryRectifier.fillGaps(
            TrajectoryRectifier.removeOutliers(track, frameWidth)
        )

        // 2. 测速（需标定）
        var speed: SpeedResult? = null
        var physics: PhysicsReport? = null
        if (calibration != null) {
            speed = SpeedCalculator.compute(rectified, calibration, speedConfig)
            if (speed != null) {
                physics = PhysicsValidator.validate(rectified, calibration, physicsConfig)
            }
        }

        // 3. 质量分级
        val meanConf = if (track.isEmpty()) 0.0 else track.sumOf { it.confidence } / track.size
        val quality = when {
            rectified.size < 6 -> AnalysisOutput.Quality.INSUFFICIENT
            rectified.size >= 12 && meanConf >= 0.15 -> AnalysisOutput.Quality.GOOD
            else -> AnalysisOutput.Quality.FAIR
        }

        return AnalysisOutput(
            rawPoints = track,
            rectified = rectified,
            speed = speed,
            physics = physics,
            quality = quality
        )
    }

    /** 多段轨迹择优：点数 ≥6 且质量达标中，取平滑峰值最高的一段 */
    fun bestAnalysis(
        tracks: List<List<TrackPoint>>,
        frameWidth: Double,
        calibration: ScaleCalibration?
    ): Pair<List<TrackPoint>, AnalysisOutput>? {
        var best: Triple<List<TrackPoint>, AnalysisOutput, Double>? = null
        for (t in tracks) {
            if (t.size < 6) continue
            val a = analyze(t, frameWidth, calibration)
            if (a.quality == AnalysisOutput.Quality.INSUFFICIENT) continue
            val score = a.speed?.smoothedPeakKmh ?: 0.0
            if (best == null || score > best.third) {
                best = Triple(t, a, score)
            }
        }
        return best?.let { it.first to it.second }
    }
}

/** 帧处理器：流式消费，内存中只保留检测所需的前一帧（实时模式与视频模式共用） */
class FrameProcessor(private val detector: ShuttlecockDetector) {

    private var tracker = TrajectoryTracker()
    private val tracks = ArrayList<List<TrackPoint>>()
    private var prev: GrayscaleImage? = null
    private var idx = 0

    fun process(
        frame: GrayscaleImage,
        time: Double,
        downscale: Double,
        originalWidth: Int,
        calibration: ScaleCalibration?
    ) {
        // 检测在降采样图上进行，坐标映射回原始帧像素坐标系
        val dets = detector.detect(previous = prev, current = frame).map { d ->
            Detection(
                center = Point2(d.center.x * downscale, d.center.y * downscale),
                confidence = d.confidence,
                bboxSize = Size2(d.bboxSize.width * downscale, d.bboxSize.height * downscale),
                blurLength = d.blurLength * downscale
            )
        }

        if (tracker.isFinished) {
            if (tracker.points.size >= 2) tracks.add(tracker.points)
            tracker = TrajectoryTracker()
        }

        tracker.process(
            frameIndex = idx,
            time = time,
            detections = dets,
            frameWidth = originalWidth.toDouble(),
            scaleMetersPerPixel = calibration?.scale
        )
        prev = frame
        idx += 1
    }

    fun finish(): List<List<TrackPoint>> {
        if (tracker.points.size >= 2) tracks.add(tracker.points)
        return tracks
    }
}
