import XCTest
import CoreGraphics
@testable import ShuttleEyeCore

/// 物理验证（阻力模型）测试
final class PhysicsValidatorTests: XCTestCase {

    private func makeCalibration() -> ScaleCalibration {
        // 200 px = 1.0 m → 0.005 m/px
        ScaleCalibration(pixelA: .zero, pixelB: CGPoint(x: 200, y: 0), realDistanceMeters: 1.0)
    }

    /// 合成轨迹：真实 k=0.2 的阻力减速，应被精确还原
    func testDragCoeffRecovery() {
        let cal = makeCalibration()
        let scale = cal.scale
        let dt = 1.0 / 120.0
        let kTrue = 0.2
        var v = 80.0 // m/s
        var x = 0.0
        var points: [TrackPoint] = []
        for i in 0...30 {
            points.append(TrackPoint(frameIndex: i, time: Double(i) * dt,
                                     center: CGPoint(x: x / scale, y: 300),
                                     confidence: 0.9, bboxSize: CGSize(width: 4, height: 4)))
            x += v * dt
            v = v / (1 + kTrue * v * dt)
        }
        let report = PhysicsValidator.validate(points: points, calibration: cal)
        XCTAssertNotNil(report)
        XCTAssertEqual(report!.dragCoeffK, kTrue, accuracy: 0.005, "气动系数应精确还原")
        XCTAssertGreaterThan(report!.r2, 0.999, "阻力模型拟合优度应接近 1")
        XCTAssertTrue(report!.isConsistent)
        XCTAssertTrue(report!.suspiciousSegments.isEmpty)
    }

    /// 雷达等效速度：必然小于击球峰值，且随距离增大而衰减
    func testRadarEquivalentMonotonicDecay() {
        let v0 = 100.0, k = 0.2
        let v4 = PhysicsValidator.speedAfter(distanceMeters: 4, initialSpeed: v0, dragCoeffK: k)
        let v8 = PhysicsValidator.speedAfter(distanceMeters: 8, initialSpeed: v0, dragCoeffK: k)
        XCTAssertLessThan(v4, v0)
        XCTAssertLessThan(v8, v4)
        // 量级合理性：4m 处 100 m/s 应衰减约 40%
        XCTAssertEqual(v4, 1.0 / (1.0 / 100.0 + 0.8), accuracy: 0.01)
    }

    /// 注入离群帧后：验证器应标记可疑段
    func testSuspiciousSegmentFlagging() {
        let cal = makeCalibration()
        let scale = cal.scale
        let dt = 1.0 / 120.0
        var v = 60.0
        var x = 0.0
        var points: [TrackPoint] = []
        for i in 0...20 {
            var cx = x / scale
            if i == 10 { cx += 80 } // 注入一个明显偏离物理模型的点
            points.append(TrackPoint(frameIndex: i, time: Double(i) * dt,
                                     center: CGPoint(x: cx, y: 300),
                                     confidence: 0.9, bboxSize: CGSize(width: 4, height: 4)))
            x += v * dt
            v = v / (1 + 0.2 * v * dt)
        }
        let report = PhysicsValidator.validate(points: points, calibration: cal)
        XCTAssertNotNil(report)
        XCTAssertFalse(report!.suspiciousSegments.isEmpty, "离群段应被标记为可疑")
    }
}
