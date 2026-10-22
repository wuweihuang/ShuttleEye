import Foundation
import CoreGraphics

/// 物理一致性验证报告 —— 本项目的核心「速度验证」能力
///
/// 羽毛球受空气阻力主导，阻力量级远超重力，满足：
///     dv/dt = -k·v²   （k 为气动系数，单位 1/m）
/// 其闭式解为：
///     v(t+Δt) = v(t) / (1 + k·v(t)·Δt)
///     v(x)    = 1 / (1/v₀ + k·x)         （以飞行距离为自变量）
///
/// 验证方法：
/// 1. 由相邻段速度反解每段 k_i = (v_i/v_{i+1} − 1)/(v_i·Δt)，取中值得 k̂
/// 2. 用 k̂ 做前向预测，与实测速度比较 → R² 与最大相对偏差
/// 3. R² 高且 k̂ 落在羽毛球合理区间（≈0.05~0.5 /m）→ 测速通过物理自洽校验
/// 4. 雷达等效速度：v(距击球点 d 米处) —— 与专业雷达枪读数可比
///    （雷达枪需锁定距离，测得的是球已减速后的速度，arXiv:2509.05334 §4.3）
public struct PhysicsReport: Codable, Equatable {
    /// 拟合的气动系数（1/m）
    public let dragCoeffK: Double
    /// 拟合优度
    public let r2: Double
    public let maxRelDeviation: Double
    /// 物理自洽校验是否通过
    public let isConsistent: Bool
    /// 与阻力模型偏差过大的可疑段索引
    public let suspiciousSegments: [Int]
    /// 雷达枪等效速度（默认距击球点 4m 处，即近网站位读数）
    public let radarEquivalentKmh: Double?
    public let segmentCount: Int
}

public enum PhysicsValidator {

    public struct Config {
        /// 羽毛球气动系数合理范围（1/m）：k = g/v_term²，
        /// 球速终端速度约 6.7~10 m/s → k ≈ 0.10~0.22，放宽到 [0.02, 0.6]
        public var kMin: Double = 0.02
        public var kMax: Double = 0.6
        public var r2Threshold: Double = 0.85
        public var maxDeviation: Double = 0.35
        public var minSegments: Int = 4
        /// 雷达枪等效距离（米）
        public var radarDistanceMeters: Double = 4.0
        public init() {}
    }

    public static func validate(points: [TrackPoint],
                                calibration: ScaleCalibration,
                                config: Config = Config()) -> PhysicsReport? {
        let scale = calibration.scale
        guard scale > 0 else { return nil }

        // ---- 段速度（仅用实测点，插值点不参与验证）----
        var v: [Double] = []   // m/s
        var dt: [Double] = []
        for i in 0..<(points.count - 1) {
            let a = points[i], b = points[i + 1]
            guard !a.isInterpolated, !b.isInterpolated else { continue }
            let d = hypot(b.center.x - a.center.x, b.center.y - a.center.y)
            let dtp = b.time - a.time
            guard dtp > 0, d > 0 else { continue }
            v.append(d / dtp * scale)
            dt.append(dtp)
        }
        guard v.count >= config.minSegments else { return nil }

        // ---- 第一次拟合 ----
        var fit = fitDragModel(speeds: v, dts: dt, config: config)

        // ---- 鲁棒二次拟合：剔除可疑段相邻的转移后重估 ----
        if !fit.suspicious.isEmpty {
            var v2: [Double] = [], dt2: [Double] = []
            let susp = Set(fit.suspicious)
            for i in 0..<(v.count - 1) where !susp.contains(i) && !susp.contains(i + 1) {
                v2.append(v[i])
                dt2.append(dt[i])
            }
            if v2.count >= config.minSegments {
                let refit = fitDragModel(speeds: v2, dts: dt2, config: config)
                if refit.r2 > fit.r2 { fit = refit }
            }
        }

        // ---- 雷达等效速度 ----
        var radar: Double? = nil
        if fit.k > 0 {
            let peak = v.max() ?? 0
            if peak > 0 {
                // dv/dx = -k·v 的闭式解：v(x) = 1/(1/v₀ + k·x)
                let vAt = 1 / (1 / peak + fit.k * config.radarDistanceMeters)
                radar = vAt * 3.6
            }
        }

        let consistent = fit.k >= config.kMin && fit.k <= config.kMax
            && fit.r2 >= config.r2Threshold
            && fit.maxDev <= config.maxDeviation
            && fit.estimateCount >= 3

        return PhysicsReport(dragCoeffK: fit.k,
                             r2: fit.r2,
                             maxRelDeviation: fit.maxDev,
                             isConsistent: consistent,
                             suspiciousSegments: fit.suspicious,
                             radarEquivalentKmh: radar,
                             segmentCount: v.count)
    }

    /// 闭式解：飞行 x 米后的速度（v0 单位 m/s）
    public static func speedAfter(distanceMeters x: Double, initialSpeed v0: Double, dragCoeffK k: Double) -> Double {
        1 / (1 / v0 + k * x)
    }

    // MARK: - 内部

    private struct FitResult {
        var k: Double
        var r2: Double
        var maxDev: Double
        var suspicious: [Int]
        var estimateCount: Int
    }

    private static func fitDragModel(speeds v: [Double], dts: [Double], config: Config) -> FitResult {
        // 反解每段 k：k_i = (v_i/v_{i+1} − 1)/(v_i·Δt_i)（仅减速段有效）
        var ks: [Double] = []
        for i in 0..<(v.count - 1) where v[i] > v[i + 1] && v[i] > 1 {
            let k = (v[i] / v[i + 1] - 1) / (v[i] * dts[i])
            if k > config.kMin && k < config.kMax { ks.append(k) }
        }
        guard !ks.isEmpty else {
            return FitResult(k: 0, r2: 0, maxDev: 1, suspicious: [], estimateCount: 0)
        }
        let k = ks.sorted()[ks.count / 2]

        // 用 k̂ 前向预测
        var pred = [Double?](repeating: nil, count: v.count)
        for i in 0..<(v.count - 1) {
            pred[i + 1] = v[i] / (1 + k * v[i] * dts[i])
        }

        var ssRes = 0.0, ssTot = 0.0, maxDev = 0.0
        var suspicious: [Int] = []
        let mean = v.reduce(0, +) / Double(v.count)
        for i in v.indices {
            guard let p = pred[i] else { continue }
            ssRes += (v[i] - p) * (v[i] - p)
            ssTot += (v[i] - mean) * (v[i] - mean)
            let dev = abs(v[i] - p) / max(v[i], 1e-6)
            if dev > maxDev { maxDev = dev }
            if dev > config.maxDeviation { suspicious.append(i) }
        }
        let r2 = ssTot > 0 ? 1 - ssRes / ssTot : 0
        return FitResult(k: k, r2: r2, maxDev: maxDev, suspicious: suspicious, estimateCount: ks.count)
    }
}
