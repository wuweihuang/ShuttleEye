package com.badmon.shuttleeye.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/** 物理一致性验证报告 —— 本项目的核心「速度验证」能力
 *
 * 羽毛球受空气阻力主导，阻力量级远超重力，满足：
 *     dv/dt = -k·v²   （k 为气动系数，单位 1/m）
 * 其闭式解为：
 *     v(t+Δt) = v(t) / (1 + k·v(t)·Δt)      （以时间为自变量）
 *     v(x)    = v₀·e^(-k·x)                 （以飞行距离为自变量，dv/dt=-k·v² → dv/dx=-k·v）
 *
 * 验证方法：
 * 1. 由相邻段速度反解每段 k_i = (v_i/v_{i+1} − 1)/(v_i·Δt)，取中值得 k̂
 * 2. 用 k̂ 做前向预测，与实测速度比较 → R² 与最大相对偏差
 * 3. R² 高且 k̂ 落在羽毛球合理区间（≈0.05~0.5 /m）→ 测速通过物理自洽校验
 * 4. 雷达等效速度：v(距击球点 d 米处) —— 与专业雷达枪读数可比
 *    （雷达枪需锁定距离，测得的是球已减速后的速度，arXiv:2509.05334 §4.3）
 */
data class PhysicsReport(
    /** 拟合的气动系数（1/m） */
    val dragCoeffK: Double,
    /** 拟合优度 */
    val r2: Double,
    val maxRelDeviation: Double,
    /** 物理自洽校验是否通过 */
    val isConsistent: Boolean,
    /** 与阻力模型偏差过大的可疑段索引 */
    val suspiciousSegments: List<Int>,
    /** 雷达枪等效速度（默认距击球点 4m 处，即近网站位读数） */
    val radarEquivalentKmh: Double?,
    val segmentCount: Int
)

object PhysicsValidator {

    data class Config(
        /** 羽毛球气动系数合理范围（1/m）：k = g/v_term²，
         *  球速终端速度约 6.7~10 m/s → k ≈ 0.10~0.22，放宽到 [0.02, 0.6] */
        val kMin: Double = 0.02,
        val kMax: Double = 0.6,
        val r2Threshold: Double = 0.85,
        val maxDeviation: Double = 0.35,
        val minSegments: Int = 4,
        /** 雷达枪等效距离（米） */
        val radarDistanceMeters: Double = 4.0
    )

    fun validate(
        points: List<TrackPoint>,
        calibration: ScaleCalibration,
        config: Config = Config()
    ): PhysicsReport? {
        val scale = calibration.scale
        if (scale <= 0) return null

        // ---- 段速度（仅用实测点，插值点不参与验证）----
        val v = ArrayList<Double>()   // m/s
        val dt = ArrayList<Double>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (a.isInterpolated || b.isInterpolated) continue
            val d = hypot(b.center.x - a.center.x, b.center.y - a.center.y)
            val dtp = b.time - a.time
            if (dtp <= 0 || d <= 0) continue
            v.add(d / dtp * scale)
            dt.add(dtp)
        }
        if (v.size < config.minSegments) return null

        // ---- 第一次拟合 ----
        var fit = fitDragModel(v, dt, config)

        // ---- 鲁棒二次拟合：剔除可疑段相邻的转移后重估 ----
        if (fit.suspicious.isNotEmpty()) {
            val susp = fit.suspicious.toHashSet()
            val v2 = ArrayList<Double>()
            val dt2 = ArrayList<Double>()
            for (i in 0 until v.size - 1) {
                if (!susp.contains(i) && !susp.contains(i + 1)) {
                    v2.add(v[i])
                    dt2.add(dt[i])
                }
            }
            if (v2.size >= config.minSegments) {
                val refit = fitDragModel(v2, dt2, config)
                if (refit.r2 > fit.r2) fit = refit
            }
        }

        // ---- 雷达等效速度 ----
        var radar: Double? = null
        if (fit.k > 0) {
            val peak = v.maxOrNull() ?: 0.0
            if (peak > 0) {
                // dv/dx = -k·v 的闭式解：v(x) = v₀·e^(-k·x)（随距离衰减）
                radar = speedAfter(config.radarDistanceMeters, peak, fit.k) * 3.6
            }
        }

        val consistent = fit.k >= config.kMin && fit.k <= config.kMax &&
                fit.r2 >= config.r2Threshold &&
                fit.maxDev <= config.maxDeviation &&
                fit.estimateCount >= 3

        return PhysicsReport(
            dragCoeffK = fit.k,
            r2 = fit.r2,
            maxRelDeviation = fit.maxDev,
            isConsistent = consistent,
            suspiciousSegments = fit.suspicious,
            radarEquivalentKmh = radar,
            segmentCount = v.size
        )
    }

    /** 闭式解：飞行 x 米后的速度（v0 单位 m/s）
     * dv/dt = -k·v² → dv/dx = -k·v → v(x) = v0·e^(-k·x)
     * 注意：距离域为指数衰减；随时间的解 v(t)=v0/(1+k·v0·t) 仅用于前向预测拟合，二者不可混用。
     */
    fun speedAfter(distanceMeters: Double, initialSpeed: Double, dragCoeffK: Double): Double =
        initialSpeed * exp(-dragCoeffK * distanceMeters)

    // ---- 内部 ----

    private data class FitResult(
        val k: Double,
        val r2: Double,
        val maxDev: Double,
        val suspicious: List<Int>,
        val estimateCount: Int
    )

    private fun fitDragModel(v: List<Double>, dts: List<Double>, config: Config): FitResult {
        // 反解每段 k：k_i = (v_i/v_{i+1} − 1)/(v_i·Δt_i)（仅减速段有效）
        val ks = ArrayList<Double>()
        for (i in 0 until v.size - 1) {
            if (v[i] > v[i + 1] && v[i] > 1) {
                val k = (v[i] / v[i + 1] - 1) / (v[i] * dts[i])
                if (k > config.kMin && k < config.kMax) ks.add(k)
            }
        }
        if (ks.isEmpty()) {
            return FitResult(k = 0.0, r2 = 0.0, maxDev = 1.0, suspicious = emptyList(), estimateCount = 0)
        }
        val k = ks.sorted()[ks.size / 2]

        // 用 k̂ 前向预测（时间域闭式解 v(t)=v0/(1+k·v0·t)，与距离域 v(x)=v0·e^(-k·x) 各自独立）
        val pred = arrayOfNulls<Double>(v.size)
        for (i in 0 until v.size - 1) {
            pred[i + 1] = v[i] / (1 + k * v[i] * dts[i])
        }

        var ssRes = 0.0
        var ssTot = 0.0
        var maxDev = 0.0
        val suspicious = ArrayList<Int>()
        val mean = v.sum() / v.size
        for (i in v.indices) {
            val p = pred[i] ?: continue
            ssRes += (v[i] - p) * (v[i] - p)
            ssTot += (v[i] - mean) * (v[i] - mean)
            val dev = abs(v[i] - p) / max(v[i], 1e-6)
            if (dev > maxDev) maxDev = dev
            if (dev > config.maxDeviation) suspicious.add(i)
        }
        val r2 = if (ssTot > 0) 1 - ssRes / ssTot else 0.0
        return FitResult(k = k, r2 = r2, maxDev = maxDev, suspicious = suspicious, estimateCount = ks.size)
    }
}
