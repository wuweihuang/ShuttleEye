import Foundation
import CoreGraphics

/// 分析管线输出
public struct AnalysisOutput {
    public let rawPoints: [TrackPoint]
    public let rectified: [TrackPoint]
    public let speed: SpeedResult?
    public let physics: PhysicsReport?
    public let quality: Quality

    public enum Quality: String, Codable {
        case good      // 点数充足，可直接信任
        case fair      // 点数偏少，结果仅供参考
        case insufficient // 点数不足，无法给出可靠结论
    }
}

/// 端到端分析管线：原始轨迹 → 修复 → 测速 → 物理验证
public enum AnalysisPipeline {

    public static func analyze(track: [TrackPoint],
                               frameWidth: Double,
                               calibration: ScaleCalibration?,
                               speedConfig: SpeedCalculator.Config = .init(),
                               physicsConfig: PhysicsValidator.Config = .init()) -> AnalysisOutput {
        // 1. 轨迹修复
        let rectified = TrajectoryRectifier.fillGaps(
            TrajectoryRectifier.removeOutliers(track, frameWidth: frameWidth)
        )

        // 2. 测速（需标定）
        var speed: SpeedResult? = nil
        var physics: PhysicsReport? = nil
        if let cal = calibration {
            speed = SpeedCalculator.compute(points: rectified, calibration: cal, config: speedConfig)
            if speed != nil {
                physics = PhysicsValidator.validate(points: rectified, calibration: cal, config: physicsConfig)
            }
        }

        // 3. 质量分级
        let meanConf = track.isEmpty ? 0 : track.map(\.confidence).reduce(0, +) / Double(track.count)
        let quality: AnalysisOutput.Quality
        if rectified.count < 6 {
            quality = .insufficient
        } else if rectified.count >= 12 && meanConf >= 0.15 {
            quality = .good
        } else {
            quality = .fair
        }

        return AnalysisOutput(rawPoints: track, rectified: rectified,
                              speed: speed, physics: physics, quality: quality)
    }

    /// 多段轨迹择优：点数 ≥6 且质量达标中，取平滑峰值最高的一段
    public static func bestAnalysis(tracks: [[TrackPoint]],
                                    frameWidth: Double,
                                    calibration: ScaleCalibration?) -> (track: [TrackPoint], analysis: AnalysisOutput)? {
        var best: ([TrackPoint], AnalysisOutput, Double)?
        for t in tracks where t.count >= 6 {
            let a = analyze(track: t, frameWidth: frameWidth, calibration: calibration)
            guard a.quality != .insufficient else { continue }
            let score = a.speed?.smoothedPeakKmh ?? 0
            if best == nil || score > best!.2 {
                best = (t, a, score)
            }
        }
        guard let b = best else { return nil }
        return (b.0, b.1)
    }
}
