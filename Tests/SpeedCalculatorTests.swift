import XCTest
import CoreGraphics
@testable import ShuttleEyeCore

/// 测速精度测试
final class SpeedCalculatorTests: XCTestCase {

    private func makeCalibration() -> ScaleCalibration {
        // 100 px 对应 1.0 m → 0.01 m/px
        ScaleCalibration(pixelA: CGPoint(x: 0, y: 0), pixelB: CGPoint(x: 100, y: 0), realDistanceMeters: 1.0)
    }

    /// 匀速运动测速：10 px/帧 @240fps、0.01m/px → 86.4 km/h
    func testConstantSpeed() {
        let cal = makeCalibration()
        let dt = 1.0 / 240.0
        var points: [TrackPoint] = []
        for i in 0..<30 {
            points.append(TrackPoint(frameIndex: i, time: Double(i) * dt,
                                     center: CGPoint(x: Double(i) * 10, y: 100),
                                     confidence: 0.9, bboxSize: CGSize(width: 6, height: 6)))
        }
        let result = SpeedCalculator.compute(points: points, calibration: cal)
        XCTAssertNotNil(result)
        let expected = 10.0 / dt * 0.01 * 3.6 // 86.4 km/h
        XCTAssertEqual(result!.peakKmh, expected, accuracy: 0.01, "匀速段测速应精确")
        // 无模糊时不确定度 σ = √2·σ_px·S/dt·3.6
        let expectedUnc = sqrt(2) * 1.5 * 0.01 / dt * 3.6
        XCTAssertEqual(result!.peakUncertaintyKmh, expectedUnc, accuracy: 0.01)
        // 匀速时平滑峰值等于峰值
        XCTAssertEqual(result!.smoothedPeakKmh, expected, accuracy: 0.01)
    }

    /// 运动模糊前缘补偿：均匀拖影不改变段速度（两端等量前移）
    func testUniformBlurNoBias() {
        let cal = makeCalibration()
        let dt = 1.0 / 240.0
        var plain: [TrackPoint] = []
        var blurred: [TrackPoint] = []
        for i in 0..<20 {
            let c = CGPoint(x: Double(i) * 12, y: 50)
            plain.append(TrackPoint(frameIndex: i, time: Double(i) * dt, center: c,
                                    confidence: 0.9, bboxSize: CGSize(width: 6, height: 20)))
            blurred.append(TrackPoint(frameIndex: i, time: Double(i) * dt, center: c,
                                      confidence: 0.9, bboxSize: CGSize(width: 6, height: 20),
                                      blurLength: 14))
        }
        let r1 = SpeedCalculator.compute(points: plain, calibration: cal)!
        let r2 = SpeedCalculator.compute(points: blurred, calibration: cal)!
        XCTAssertEqual(r1.peakKmh, r2.peakKmh, accuracy: 0.01, "均匀拖影经前缘补偿后不应引入偏差")
    }

    /// 加速运动：峰值应取最大段
    func testPeakDetection() {
        let cal = makeCalibration()
        let dt = 1.0 / 120.0
        var points: [TrackPoint] = []
        var x = 0.0
        for i in 0..<10 {
            points.append(TrackPoint(frameIndex: i, time: Double(i) * dt,
                                     center: CGPoint(x: x, y: 0), confidence: 0.9,
                                     bboxSize: CGSize(width: 4, height: 4)))
            x += 5.0 * Double(i + 1) // 每帧位移递增
        }
        let r = SpeedCalculator.compute(points: points, calibration: cal)!
        let maxStep = 5.0 * 10.0
        XCTAssertEqual(r.peakKmh, maxStep / dt * 0.01 * 3.6, accuracy: 0.01)
        XCTAssertEqual(r.peakSegmentIndex, r.segments.count - 1)
    }
}
