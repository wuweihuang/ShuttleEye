package com.badmon.shuttleeye.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 轨迹修复（Trajectory Rectification）
 *
 * 参考 TrackNetV3 的轨迹修复思想（ACM MM 2023）：
 * 1. 中值离群点剔除：帧间位移突变的孤立误检被移除
 * 2. 间隙插值：短时丢失（≤ maxGapFrames）用 Catmull-Rom 样条补全，
 *    实测点不做平滑（避免抹平击球瞬间的速度峰值，保证峰值测速真实）
 */
object TrajectoryRectifier {

    /** 滑窗中值离群点剔除 */
    fun removeOutliers(
        points: List<TrackPoint>,
        frameWidth: Double,
        window: Int = 5,
        deviationFraction: Double = 0.08
    ): List<TrackPoint> {
        if (points.size < 5) return points
        val half = window / 2
        val kept = ArrayList<TrackPoint>(points.size)

        for ((i, p) in points.withIndex()) {
            val lo = max(0, i - half)
            val hi = min(points.size - 1, i + half)
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (j in lo..hi) {
                if (j == i) continue
                xs.add(points[j].center.x)
                ys.add(points[j].center.y)
            }
            if (xs.size < 2) {
                kept.add(p)
                continue
            }
            val mx = median(xs)
            val my = median(ys)
            val dev = hypot(p.center.x - mx, p.center.y - my)
            if (dev <= max(deviationFraction * frameWidth, 2.0)) {
                kept.add(p)
            }
        }
        return kept
    }

    /** Catmull-Rom 样条填补短时丢失帧 */
    fun fillGaps(points: List<TrackPoint>, maxGapFrames: Int = 8): List<TrackPoint> {
        if (points.size < 2) return points
        val out = ArrayList<TrackPoint>(points.size + 8)

        for (i in points.indices) {
            val p = points[i]
            out.add(p)
            if (i + 1 >= points.size) break
            val nxt = points[i + 1]
            val gap = nxt.frameIndex - p.frameIndex - 1
            if (gap <= 0 || gap > maxGapFrames) continue

            val p0 = points[max(i - 1, 0)].center
            val p1 = p.center
            val p2 = nxt.center
            val p3 = points[min(i + 2, points.size - 1)].center

            for (k in 1..gap) {
                val s = k.toDouble() / (gap + 1).toDouble()
                val c = catmullRom(p0, p1, p2, p3, s)
                val t = p.time + (nxt.time - p.time) * s
                out.add(
                    TrackPoint(
                        frameIndex = p.frameIndex + k, time = t, center = c,
                        confidence = 0.0, bboxSize = p.bboxSize,
                        blurLength = 0.0, isInterpolated = true
                    )
                )
            }
        }
        return out
    }

    /** Catmull-Rom 插值（tension = 0.5） */
    internal fun catmullRom(p0: Point2, p1: Point2, p2: Point2, p3: Point2, s: Double): Point2 {
        val s2 = s * s
        val s3 = s2 * s
        fun cr(a: Double, b: Double, c: Double, d: Double): Double =
            0.5 * ((2 * b) + (-a + c) * s + (2 * a - 5 * b + 4 * c - d) * s2 + (-a + 3 * b - 3 * c + d) * s3)
        return Point2(
            x = cr(p0.x, p1.x, p2.x, p3.x),
            y = cr(p0.y, p1.y, p2.y, p3.y)
        )
    }

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
