package com.badmon.shuttleeye.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 单段速度（相邻两帧之间） */
data class SegmentSpeed(
    val index: Int,
    val t0: Double,
    val t1: Double,
    val speedKmh: Double,
    /** 像素量化带来的不确定度（±km/h）：σ_v = √2·σ_px·S_f/dt */
    val uncertaintyKmh: Double
)

/** 测速结果 */
data class SpeedResult(
    /** 峰值速度（击球瞬间，逐段最大值） */
    val peakKmh: Double,
    val peakUncertaintyKmh: Double,
    /** 3 段中值平滑后的峰值（降噪参考值，避免单帧噪声导致虚高） */
    val smoothedPeakKmh: Double,
    val averageKmh: Double,
    val peakSegmentIndex: Int,
    val segments: List<SegmentSpeed>
)

/** 运动学测速器
 *
 * v_{i→i+1} = (Δpixel / Δt) × S_f × 3.6  [km/h]（arXiv:2509.05334 §3.2.3）
 *
 * 本项目相对原论文的精度优化：
 * 1. 亚像素质心：检测端用亮度加权质心替代包围盒几何中心
 * 2. 运动模糊前缘补偿：把质心沿运动方向前移 blurLength/2，
 *    使测量点一致对应曝光末端时刻，抑制拖影帧的系统性偏差
 * 3. 不确定度量化：σ_v = √2·σ_px·S_f/dt，随帧率与标定精度自动给出误差棒
 */
object SpeedCalculator {

    data class Config(
        /** 单帧质心定位标准差（px）。亚像素质心通常 1~2 px */
        val sigmaPixel: Double = 1.5,
        val useLeadingEdge: Boolean = true
    )

    fun compute(
        points: List<TrackPoint>,
        calibration: ScaleCalibration,
        config: Config = Config()
    ): SpeedResult? {
        val scale = calibration.scale
        if (scale <= 0 || points.size < 2) return null

        // ---- 前缘补偿后的位置 ----
        val adjusted = ArrayList<Point2>(points.size)
        for (i in points.indices) {
            val p = points[i]
            if (!config.useLeadingEdge || p.blurLength <= 0) {
                adjusted.add(p.center)
                continue
            }
            // 运动方向由邻域位移估计
            val prev = points[max(i - 1, 0)].center
            val next = points[min(i + 1, points.size - 1)].center
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            val len = hypot(dx, dy)
            if (len > 1e-6) {
                dx /= len
                dy /= len
            } else {
                dx = 1.0
                dy = 0.0
            }
            adjusted.add(Point2(p.center.x + dx * p.blurLength / 2, p.center.y + dy * p.blurLength / 2))
        }

        // ---- 逐段速度 ----
        val segments = ArrayList<SegmentSpeed>(points.size - 1)
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dt = b.time - a.time
            if (dt <= 0) continue
            val dpx = hypot(adjusted[i + 1].x - adjusted[i].x, adjusted[i + 1].y - adjusted[i].y)
            val mps = dpx / dt * scale
            val unc = sqrt(2.0) * config.sigmaPixel * scale / dt * 3.6
            segments.add(SegmentSpeed(index = i, t0 = a.time, t1 = b.time, speedKmh = mps * 3.6, uncertaintyKmh = unc))
        }
        if (segments.isEmpty()) return null

        // ---- 峰值与平滑峰值 ----
        var peakIdx = 0
        for (i in segments.indices) {
            if (segments[i].speedKmh > segments[peakIdx].speedKmh) peakIdx = i
        }
        val smoothed = ArrayList<Double>(segments.size)
        for (i in segments.indices) {
            val lo = max(0, i - 1)
            val hi = min(segments.size - 1, i + 1)
            val win = (lo..hi).map { segments[it].speedKmh }.sorted()
            smoothed.add(win[win.size / 2])
        }
        val avg = segments.sumOf { it.speedKmh } / segments.size

        return SpeedResult(
            peakKmh = segments[peakIdx].speedKmh,
            peakUncertaintyKmh = segments[peakIdx].uncertaintyKmh,
            smoothedPeakKmh = smoothed.maxOrNull() ?: 0.0,
            averageKmh = avg,
            peakSegmentIndex = peakIdx,
            segments = segments
        )
    }
}
