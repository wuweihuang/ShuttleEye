package com.badmon.shuttleeye.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** 恒速（Constant Velocity）模型的二维卡尔曼滤波器
 * 状态向量: [x, y, vx, vy]
 *
 * 参考 arXiv:2509.05334（移动端杀球测速）与 SORT 的 tracking-by-detection 范式。
 * 过程噪声取较大的加速度标准差，以适应羽毛球被击打后的剧烈减速（峰值可达 ~200g）。
 */
class KalmanFilter2D(
    initial: Point2,
    private val accelStd: Double = 4.0e5,   // 过程噪声：加速度标准差 (px/s²)
    private val measStd: Double = 1.8       // 量测噪声 (px)
) {

    private val x = doubleArrayOf(initial.x, initial.y, 0.0, 0.0) // 状态 [x, y, vx, vy]
    private val p = identity4()                                    // 4x4 协方差

    init {
        p[0][0] = 64.0
        p[1][1] = 64.0
        p[2][2] = accelStd * accelStd * 0.01
        p[3][3] = accelStd * accelStd * 0.01
    }

    val position: Point2 get() = Point2(x[0], x[1])
    val velocity: Point2 get() = Point2(x[2], x[3])
    val speedPxPerSec: Double get() = hypot(x[2], x[3])
    val positionStd: Double get() = sqrt(max(0.0, (p[0][0] + p[1][1]) / 2.0))

    /** 用前两个实测点直接种入速度（避免滤波器从零速度缓慢收敛，提升击球瞬间精度） */
    fun seedVelocity(previous: Point2, dt: Double) {
        if (dt <= 0) return
        x[2] = (previous.x - x[0]) / dt
        x[3] = (previous.y - x[1]) / dt
        p[2][2] = accelStd * accelStd * 0.01
        p[3][3] = accelStd * accelStd * 0.01
    }

    fun predict(dt: Double) {
        // F = I + [[0,0,dt,0],[0,0,0,dt],[0,0,0,0],[0,0,0,0]]
        x[0] += x[2] * dt
        x[1] += x[3] * dt

        val f = identity4()
        f[0][2] = dt
        f[1][3] = dt
        val fpt = mul(mul(f, p), transpose4(f))
        for (i in 0..3) System.arraycopy(fpt[i], 0, p[i], 0, 4)

        // Q：恒加速白噪声模型
        val q2 = accelStd * accelStd
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        p[0][0] += q2 * dt4 / 4; p[0][2] += q2 * dt3 / 2
        p[1][1] += q2 * dt4 / 4; p[1][3] += q2 * dt3 / 2
        p[2][0] += q2 * dt3 / 2; p[2][2] += q2 * dt2
        p[3][1] += q2 * dt3 / 2; p[3][3] += q2 * dt2
    }

    fun update(measured: Point2) {
        // S = H P Hᵀ + R（2x2），H 取前两行单位阵
        val r2 = measStd * measStd
        val s00 = p[0][0] + r2
        val s01 = p[0][1]
        val s10 = p[1][0]
        val s11 = p[1][1] + r2
        val det = s00 * s11 - s01 * s10
        if (abs(det) <= 1e-12) return
        val i00 = s11 / det; val i01 = -s01 / det
        val i10 = -s10 / det; val i11 = s00 / det

        // K = P Hᵀ S⁻¹（4x2），P Hᵀ 即 P 的前两列
        val k = Array(4) { DoubleArray(2) }
        for (i in 0..3) {
            k[i][0] = p[i][0] * i00 + p[i][1] * i10
            k[i][1] = p[i][0] * i01 + p[i][1] * i11
        }

        val y0 = measured.x - x[0]
        val y1 = measured.y - x[1]
        for (i in 0..3) x[i] += k[i][0] * y0 + k[i][1] * y1

        // P = (I - K H) P
        val ikh = identity4()
        for (i in 0..3) {
            ikh[i][0] -= k[i][0]
            ikh[i][1] -= k[i][1]
        }
        val newP = mul(ikh, p)
        for (i in 0..3) System.arraycopy(newP[i], 0, p[i], 0, 4)
    }

    /** 预测 + 更新一步完成 */
    fun step(dt: Double, measured: Point2) {
        predict(dt)
        update(measured)
    }

    // ---- 4x4 小矩阵工具 ----

    private fun identity4(): Array<DoubleArray> {
        val m = Array(4) { DoubleArray(4) }
        for (i in 0..3) m[i][i] = 1.0
        return m
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val kk = a[0].size
        val m = b[0].size
        val out = Array(n) { DoubleArray(m) }
        for (i in 0 until n) {
            for (pi in 0 until kk) {
                val aip = a[i][pi]
                if (aip == 0.0) continue
                for (j in 0 until m) out[i][j] += aip * b[pi][j]
            }
        }
        return out
    }

    private fun transpose4(a: Array<DoubleArray>): Array<DoubleArray> {
        val out = Array(a.size) { DoubleArray(a.size) }
        for (i in a.indices) for (j in a.indices) out[j][i] = a[i][j]
        return out
    }
}
