import Foundation
import CoreGraphics
import ShuttleEyeCore

/// 历史记录条目
struct SessionRecord: Codable, Identifiable, Equatable {
    let id: UUID
    let date: Date
    let source: String          // "实时测量" / "视频分析"
    let fps: Double
    let peakKmh: Double
    let uncertaintyKmh: Double
    let smoothedPeakKmh: Double
    let radarEquivalentKmh: Double?
    let dragCoeffK: Double?
    let r2: Double?
    let physicsConsistent: Bool
    let calibration: ScaleCalibration
    /// 修复后的轨迹（原始像素坐标）
    let points: [TrackPoint]
    /// 速度曲线 (t, km/h)
    let speedCurve: [[Double]]

    init(id: UUID = UUID(), date: Date = Date(), source: String, fps: Double,
         peakKmh: Double, uncertaintyKmh: Double, smoothedPeakKmh: Double,
         radarEquivalentKmh: Double?, dragCoeffK: Double?, r2: Double?,
         physicsConsistent: Bool, calibration: ScaleCalibration,
         points: [TrackPoint], speedCurve: [[Double]]) {
        self.id = id
        self.date = date
        self.source = source
        self.fps = fps
        self.peakKmh = peakKmh
        self.uncertaintyKmh = uncertaintyKmh
        self.smoothedPeakKmh = smoothedPeakKmh
        self.radarEquivalentKmh = radarEquivalentKmh
        self.dragCoeffK = dragCoeffK
        self.r2 = r2
        self.physicsConsistent = physicsConsistent
        self.calibration = calibration
        self.points = points
        self.speedCurve = speedCurve
    }

    static func from(output: AnalysisRunner.Output, source: String, calibration: ScaleCalibration) -> SessionRecord? {
        guard let speed = output.analysis.speed else { return nil }
        return SessionRecord(
            source: source,
            fps: output.fps,
            peakKmh: speed.peakKmh,
            uncertaintyKmh: speed.peakUncertaintyKmh,
            smoothedPeakKmh: speed.smoothedPeakKmh,
            radarEquivalentKmh: output.analysis.physics?.radarEquivalentKmh,
            dragCoeffK: output.analysis.physics?.dragCoeffK,
            r2: output.analysis.physics?.r2,
            physicsConsistent: output.analysis.physics?.isConsistent ?? false,
            calibration: calibration,
            points: output.analysis.rectified,
            speedCurve: speed.segments.map { [$0.t0, $0.speedKmh] }
        )
    }
}
