import XCTest
@testable import ShuttleEyeCore

/// 卡尔曼滤波器精度测试
final class KalmanFilterTests: XCTestCase {

    /// 恒速运动 + 噪声测量：速度估计应收敛到真值
    func testConstantVelocityConvergence() {
        let trueV = 1200.0 // px/s
        let dt = 1.0 / 240.0
        let kf = KalmanFilter2D(at: CGPoint(x: 100, y: 200),
                                accelStd: 4.0e5, measStd: 1.8)
        var t = 0.0
        // 种子：前两点
        kf.update(measured: CGPoint(x: 100 + trueV * dt, y: 200))
        kf.seedVelocity(from: CGPoint(x: 100, y: 200), dt: dt)
        t = dt
        for _ in 0..<60 {
            t += dt
            kf.predict(dt: dt)
            let noisyX = 100 + trueV * t + 1.2   // 固定小偏移
            kf.update(measured: CGPoint(x: noisyX, y: 200))
        }
        XCTAssertEqual(kf.velocity.x, trueV, accuracy: trueV * 0.15, "速度收敛偏差应 <15%")
        XCTAssertEqual(kf.position.x, 100 + trueV * t, accuracy: 6.0, "位置跟踪误差应 <6px")
    }

    /// 预测步进：纯预测应等于运动学外推
    func testPurePrediction() {
        let kf = KalmanFilter2D(at: CGPoint(x: 0, y: 0))
        kf.seedVelocity(from: CGPoint(x: -30, y: -40), dt: 0.1) // v = (300, 400) px/s
        kf.predict(dt: 0.1)
        XCTAssertEqual(kf.position.x, 30, accuracy: 1e-6)
        XCTAssertEqual(kf.position.y, 40, accuracy: 1e-6)
    }

    /// 剧烈减速场景（羽毛球被击打后）：不发散
    func testHeavyDecelerationStability() {
        let dt = 1.0 / 240.0
        let kf = KalmanFilter2D(at: CGPoint(x: 0, y: 0), accelStd: 4.0e5)
        var v = 25000.0 // px/s，接近职业杀球
        kf.update(measured: CGPoint(x: v * dt, y: 0))
        kf.seedVelocity(from: .zero, dt: dt)
        var x = v * dt
        for _ in 0..<20 {
            v = v / (1 + 0.2 * v * dt)   // k=0.2 的阻力减速
            x += v * dt
            kf.predict(dt: dt)
            kf.update(measured: CGPoint(x: x, y: 0))
        }
        XCTAssertLessThan(abs(kf.position.x - x), 30.0, "强减速下位置跟踪不应发散")
    }
}
