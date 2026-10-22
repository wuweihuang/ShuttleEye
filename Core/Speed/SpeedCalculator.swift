import Foundation
import CoreGraphics

/// 单段速度（相邻两帧之间）
public struct SegmentSpeed: Codable, Equatable {
    public let index: Int
    public let t0: Double
    public let t1: Double
    public let speedKmh: Double
    /// 像素量化带来的不确定度（±km/h）：σ_v = √2·σ_px·S_f/dt
    public let uncertaintyKmh: Double

    public init(index: Int, t0: Double, t1: Double, speedKmh: Double, uncertaintyKmh: Double) {
        self.index = index
        self.t0 = t0
        self.t1 = t1
        self.speedKmh = speedKmh
        self.uncertaintyKmh = uncertaintyKmh
    }
}

/// 测速结果
public struct SpeedResult: Codable, Equatable {
    /// 峰值速度（击球瞬间，逐段最大值）
    public let peakKmh: Double
    public let peakUncertaintyKmh: Double
    /// 3 段中值平滑后的峰值（降噪参考值，避免单帧噪声导致虚高）
    public let smoothedPeakKmh: Double
    public let averageKmh: Double
    public let peakSegmentIndex: Int
    public let segments: [SegmentSpeed]

    public init(peakKmh: Double, peakUncertaintyKmh: Double, smoothedPeakKmh: Double,
                averageKmh: Double, peakSegmentIndex: Int, segments: [SegmentSpeed]) {
        self.peakKmh = peakKmh
        self.peakUncertaintyKmh = peakUncertaintyKmh
        self.smoothedPeakKmh = smoothedPeakKmh
        self.averageKmh = averageKmh
        self.peakSegmentIndex = peakSegmentIndex
        self.segments = segments
    }
}

/// 运动学测速器
///
/// v_{i→i+1} = (Δpixel / Δt) × S_f × 3.6  [km/h]（arXiv:2509.05334 §3.2.3）
///
/// 本项目相对原论文的精度优化：
/// 1. 亚像素质心：检测端用亮度加权质心替代包围盒几何中心
/// 2. 运动模糊前缘补偿：把质心沿运动方向前移 blurLength/2，
///    使测量点一致对应曝光末端时刻，抑制拖影帧的系统性偏差
/// 3. 不确定度量化：σ_v = √2·σ_px·S_f/dt，随帧率与标定精度自动给出误差棒
public enum SpeedCalculator {

    public struct Config {
        /// 单帧质心定位标准差（px）。亚像素质心通常 1~2 px
        public var sigmaPixel: Double = 1.5
        public var useLeadingEdge: Bool = true
        public init() {}
    }

    public static func compute(points: [TrackPoint],
                               calibration: ScaleCalibration,
                               config: Config = Config()) -> SpeedResult? {
        let scale = calibration.scale
        guard scale > 0, points.count >= 2 else { return nil }

        // ---- 前缘补偿后的位置 ----
        var adjusted: [CGPoint] = []
        adjusted.reserveCapacity(points.count)
        for i in points.indices {
            let p = points[i]
            guard config.useLeadingEdge, p.blurLength > 0 else {
                adjusted.append(p.center)
                continue
            }
            // 运动方向由邻域位移估计
            let prev = points[max(i - 1, 0)].center
            let next = points[min(i + 1, points.count - 1)].center
            var dir = CGPoint(x: next.x - prev.x, y: next.y - prev.y)
            let len = hypot(dir.x, dir.y)
            if len > 1e-6 {
                dir = CGPoint(x: dir.x / len, y: dir.y / len)
            } else {
                dir = CGPoint(x: 1, y: 0)
            }
            adjusted.append(CGPoint(x: p.center.x + dir.x * p.blurLength / 2,
                                    y: p.center.y + dir.y * p.blurLength / 2))
        }

        // ---- 逐段速度 ----
        var segments: [SegmentSpeed] = []
        segments.reserveCapacity(points.count - 1)
        for i in 0..<(points.count - 1) {
            let a = points[i], b = points[i + 1]
            let dt = b.time - a.time
            guard dt > 0 else { continue }
            let dpx = hypot(adjusted[i + 1].x - adjusted[i].x,
                            adjusted[i + 1].y - adjusted[i].y)
            let mps = dpx / dt * scale
            let unc = sqrt(2) * config.sigmaPixel * scale / dt * 3.6
            segments.append(SegmentSpeed(index: i, t0: a.time, t1: b.time,
                                         speedKmh: mps * 3.6, uncertaintyKmh: unc))
        }
        guard !segments.isEmpty else { return nil }

        // ---- 峰值与平滑峰值 ----
        let peakIdx = segments.indices.max { segments[$0].speedKmh < segments[$1].speedKmh }!
        var smoothed: [Double] = []
        smoothed.reserveCapacity(segments.count)
        for i in segments.indices {
            let lo = max(0, i - 1), hi = min(segments.count - 1, i + 1)
            let win = (lo...hi).map { segments[$0].speedKmh }.sorted()
            smoothed.append(win[win.count / 2])
        }
        let avg = segments.reduce(0) { $0 + $1.speedKmh } / Double(segments.count)

        return SpeedResult(peakKmh: segments[peakIdx].speedKmh,
                           peakUncertaintyKmh: segments[peakIdx].uncertaintyKmh,
                           smoothedPeakKmh: smoothed.max() ?? 0,
                           averageKmh: avg,
                           peakSegmentIndex: peakIdx,
                           segments: segments)
    }
}
