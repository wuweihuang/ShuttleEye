import Foundation
import CoreGraphics

/// 轨迹修复（Trajectory Rectification）
///
/// 参考 TrackNetV3 的轨迹修复思想（arXiv 2305.Matches / ACM MM 2023）：
/// 1. 中值离群点剔除：帧间位移突变的孤立误检被移除
/// 2. 间隙插值：短时丢失（≤ maxGapFrames）用 Catmull-Rom 样条补全，
///    实测点不做平滑（避免抹平击球瞬间的速度峰值，保证峰值测速真实）
public enum TrajectoryRectifier {

    /// 滑窗中值离群点剔除
    public static func removeOutliers(_ points: [TrackPoint],
                                      frameWidth: Double,
                                      window: Int = 5,
                                      deviationFraction: Double = 0.08) -> [TrackPoint] {
        guard points.count >= 5 else { return points }
        let half = window / 2
        var kept: [TrackPoint] = []
        kept.reserveCapacity(points.count)

        for (i, p) in points.enumerated() {
            let lo = max(0, i - half)
            let hi = min(points.count - 1, i + half)
            var xs: [Double] = [], ys: [Double] = []
            for j in lo...hi where j != i {
                xs.append(points[j].center.x)
                ys.append(points[j].center.y)
            }
            guard xs.count >= 2 else { kept.append(p); continue }
            let mx = median(xs), my = median(ys)
            let dev = hypot(p.center.x - mx, p.center.y - my)
            if dev <= max(deviationFraction * frameWidth, 2.0) {
                kept.append(p)
            }
        }
        return kept
    }

    /// Catmull-Rom 样条填补短时丢失帧
    public static func fillGaps(_ points: [TrackPoint], maxGapFrames: Int = 8) -> [TrackPoint] {
        guard points.count >= 2 else { return points }
        var out: [TrackPoint] = []
        out.reserveCapacity(points.count + 8)

        for i in points.indices {
            let p = points[i]
            out.append(p)
            guard i + 1 < points.count else { break }
            let nxt = points[i + 1]
            let gap = nxt.frameIndex - p.frameIndex - 1
            guard gap > 0, gap <= maxGapFrames else { continue }

            let p0 = points[max(i - 1, 0)].center
            let p1 = p.center
            let p2 = nxt.center
            let p3 = points[min(i + 2, points.count - 1)].center

            for k in 1...gap {
                let s = Double(k) / Double(gap + 1)
                let c = catmullRom(p0, p1, p2, p3, s)
                let t = p.time + (nxt.time - p.time) * s
                out.append(TrackPoint(frameIndex: p.frameIndex + k, time: t, center: c,
                                      confidence: 0, bboxSize: p.bboxSize,
                                      blurLength: 0, isInterpolated: true))
            }
        }
        return out
    }

    /// Catmull-Rom 插值（tension = 0.5）
    static func catmullRom(_ p0: CGPoint, _ p1: CGPoint, _ p2: CGPoint, _ p3: CGPoint, _ s: Double) -> CGPoint {
        let s2 = s * s, s3 = s2 * s
        func cr(_ a: Double, _ b: Double, _ c: Double, _ d: Double) -> Double {
            0.5 * ((2 * b) + (-a + c) * s + (2 * a - 5 * b + 4 * c - d) * s2 + (-a + 3 * b - 3 * c + d) * s3)
        }
        return CGPoint(
            x: cr(Double(p0.x), Double(p1.x), Double(p2.x), Double(p3.x)),
            y: cr(Double(p0.y), Double(p1.y), Double(p2.y), Double(p3.y))
        )
    }

    static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let n = sorted.count
        guard n > 0 else { return 0 }
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
