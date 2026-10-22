package com.badmon.shuttleeye.core

import kotlin.math.hypot
import kotlin.math.max

/** 轨迹跟踪器（tracking-by-detection）
 *
 * 核心策略（源自 arXiv:2509.05334 并做增强）：
 * 1. 复合评分：S = 0.3·C_det + 0.7·P_kalman（时间平滑优先于检测置信度）
 * 2. 启发式速度边界：5 ~ 375 km/h 之外的检测直接剔除
 * 3. 自适应门限（本项目优化）：gate = max(W/4, 2.5·|v̂|·dt)，
 *    低帧率（30fps）下球单帧位移可达数百像素，固定 W/4 门限会跟丢
 * 4. 丢失帧进入 coast 模式（仅预测不更新），超过 maxCoastFrames 判定本次击球结束
 */
class TrajectoryTracker(val config: Config = Config()) {

    data class Config(
        val confidenceWeight: Double = 0.3,
        val proximityWeight: Double = 0.7,
        val minDetectionConfidence: Double = 0.1,
        /** 论文默认门限：帧宽的 1/4 */
        val gateFrameFraction: Double = 0.25,
        val minSpeedKmh: Double = 5.0,
        val maxSpeedKmh: Double = 375.0,
        val maxCoastFrames: Int = 5,
        val accelStd: Double = 4.0e5,
        val measurementStd: Double = 1.8
    )

    private val mutablePoints = ArrayList<TrackPoint>()
    val points: List<TrackPoint> get() = mutablePoints

    var isFinished = false
        private set

    private var kalman: KalmanFilter2D? = null
    private var lastPoint: TrackPoint? = null
    private var coastCount = 0

    fun reset() {
        mutablePoints.clear()
        kalman = null
        lastPoint = null
        coastCount = 0
        isFinished = false
    }

    /** 处理一帧。返回被采纳的轨迹点（无采纳则返回 null）。
     * - scaleMetersPerPixel: 时空标定比例（m/px）。null 时跳过绝对速度校验（仅用相对门限）。
     */
    fun process(
        frameIndex: Int,
        time: Double,
        detections: List<Detection>,
        frameWidth: Double,
        scaleMetersPerPixel: Double?
    ): TrackPoint? {
        if (isFinished) return null

        // ---- 未初始化：取置信度最高的检测作为轨迹起点 ----
        val kf = kalman
        val last = lastPoint
        if (kf == null || last == null) {
            val best = detections.maxByOrNull { it.confidence } ?: return null
            val p = TrackPoint(
                frameIndex = frameIndex, time = time, center = best.center,
                confidence = best.confidence, bboxSize = best.bboxSize,
                blurLength = best.blurLength
            )
            kalman = KalmanFilter2D(
                initial = best.center,
                accelStd = config.accelStd,
                measStd = config.measurementStd
            )
            mutablePoints.add(p)
            lastPoint = p
            coastCount = 0
            return p
        }

        val dt = time - last.time
        if (dt <= 0) return null
        kf.predict(dt)

        // ---- 自适应门限 ----
        val predicted = kf.position
        val predictedStep = kf.speedPxPerSec * dt
        val gate = max(config.gateFrameFraction * frameWidth, predictedStep * 2.5)

        // ---- 复合评分选优 ----
        var chosen: Detection? = null
        var bestScore = -1.0
        for (d in detections) {
            if (d.confidence < config.minDetectionConfidence) continue
            val dist = hypot(d.center.x - predicted.x, d.center.y - predicted.y)
            if (dist > gate) continue
            val proximity = max(0.0, 1.0 - dist / max(gate, 1.0))
            val score = config.confidenceWeight * d.confidence + config.proximityWeight * proximity
            if (chosen == null || score > bestScore) {
                chosen = d
                bestScore = score
            }
        }

        if (chosen == null) {
            coastCount++
            if (coastCount > config.maxCoastFrames) isFinished = true
            return null
        }

        // ---- 启发式速度边界（heuristic bounding）----
        if (scaleMetersPerPixel != null) {
            val d = hypot(chosen.center.x - last.center.x, chosen.center.y - last.center.y)
            val kmh = d / dt * scaleMetersPerPixel * 3.6
            if (kmh < config.minSpeedKmh || kmh > config.maxSpeedKmh) {
                coastCount++
                if (coastCount > config.maxCoastFrames) isFinished = true
                return null
            }
        }

        // ---- 更新滤波器（第二次测量时种入速度）----
        if (mutablePoints.size == 1) {
            kf.seedVelocity(last.center, dt)
        }
        kf.update(chosen.center)

        val p = TrackPoint(
            frameIndex = frameIndex, time = time, center = chosen.center,
            confidence = chosen.confidence, bboxSize = chosen.bboxSize,
            blurLength = chosen.blurLength
        )
        mutablePoints.add(p)
        lastPoint = p
        coastCount = 0
        return p
    }
}
