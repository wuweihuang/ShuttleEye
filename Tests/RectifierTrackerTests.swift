import XCTest
import CoreGraphics
@testable import ShuttleEyeCore

/// 跟踪器与轨迹修复测试
final class RectifierTrackerTests: XCTestCase {

    /// 完整链路：匀速目标 + 丢帧 + 离群误检
    /// 12px/帧 @240fps、0.005m/px → 51.8 km/h（在 5~375 合法区间内）
    func testTrackRectifyPipeline() {
        let dt = 1.0 / 240.0
        var tracker = TrajectoryTracker()
        var points: [TrackPoint] = []

        for i in 0..<30 {
            var dets: [Detection] = []
            if i != 12 { // 第 12 帧丢失
                var cx = Double(i) * 12
                if i == 20 { cx += 120 } // 第 20 帧注入离群误检
                dets = [Detection(center: CGPoint(x: cx, y: 150 + Double(i) * 4),
                                  confidence: 0.9,
                                  bboxSize: CGSize(width: 6, height: 6))]
            }
            if let p = tracker.process(frameIndex: i, time: Double(i) * dt,
                                       detections: dets, frameWidth: 1280,
                                       scaleMetersPerPixel: 0.005) {
                points.append(p)
            }
        }

        // 离群点（大跳变）应被速度门限拒绝
        XCTAssertFalse(points.contains { $0.frameIndex == 20 },
                       "离群检测不应被采纳")

        // 修复：补帧 + 无剩余大间隙
        let rectified = TrajectoryRectifier.fillGaps(
            TrajectoryRectifier.removeOutliers(points, frameWidth: 1280)
        )
        XCTAssertGreaterThanOrEqual(rectified.count, points.count, "间隙应被插值补全")

        let filledFrames = Set(rectified.map(\.frameIndex))
        for i in 0..<30 where i != 20 {
            XCTAssertTrue(filledFrames.contains(i), "帧 \(i) 应存在（实测或插值）")
        }
        let interpolated = Set(rectified.filter(\.isInterpolated).map(\.frameIndex))
        XCTAssertTrue(interpolated.isSubset(of: [12, 20]),
                      "仅丢失帧 12 与被拒帧 20 需要插值，实际: \(interpolated)")
    }

    /// 中值离群剔除
    func testOutlierRemoval() {
        func pt(_ i: Int, _ x: Double) -> TrackPoint {
            TrackPoint(frameIndex: i, time: Double(i) / 240.0,
                       center: CGPoint(x: x, y: 0), confidence: 0.9,
                       bboxSize: CGSize(width: 4, height: 4))
        }
        var pts: [TrackPoint] = []
        for i in 0..<10 { pts.append(pt(i, Double(i) * 10)) }
        pts.append(pt(10, 500)) // 离群
        for i in 11..<20 { pts.append(pt(i, Double(i) * 10)) }

        let cleaned = TrajectoryRectifier.removeOutliers(pts, frameWidth: 1280)
        XCTAssertFalse(cleaned.contains { $0.frameIndex == 10 }, "离群点应被剔除")
        XCTAssertEqual(cleaned.count, pts.count - 1)
    }

    /// Catmull-Rom 插值经过控制点
    func testCatmullRomEndpoints() {
        let p0 = CGPoint(x: 0, y: 0), p1 = CGPoint(x: 10, y: 10)
        let p2 = CGPoint(x: 20, y: 10), p3 = CGPoint(x: 30, y: 0)
        let mid = TrajectoryRectifier.catmullRom(p0, p1, p2, p3, 0.5)
        XCTAssertEqual(mid.x, 15, accuracy: 1e-6)
        XCTAssertFalse(mid.x.isNaN)
    }

    /// 低于速度下限的检测被拒绝（静止误检）
    func testStaticFalsePositiveRejected() {
        let dt = 1.0 / 240.0
        var tracker = TrajectoryTracker()
        // 起点
        _ = tracker.process(frameIndex: 0, time: 0,
                            detections: [Detection(center: .init(x: 100, y: 100), confidence: 0.9,
                                                   bboxSize: .init(width: 5, height: 5))],
                            frameWidth: 1280, scaleMetersPerPixel: 0.005)
        // 移动 0.1 px/帧 → 0.43 km/h < 5 km/h 下限
        var accepted = 0
        for i in 1..<10 {
            if tracker.process(frameIndex: i, time: Double(i) * dt,
                               detections: [Detection(center: CGPoint(x: 100 + Double(i) * 0.1, y: 100),
                                                      confidence: 0.9,
                                                      bboxSize: .init(width: 5, height: 5))],
                               frameWidth: 1280, scaleMetersPerPixel: 0.005) != nil {
                accepted += 1
            }
        }
        XCTAssertEqual(accepted, 0, "静止误检应全部被速度下限拒绝")
    }
}
