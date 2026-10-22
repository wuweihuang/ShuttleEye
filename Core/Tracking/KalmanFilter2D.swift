import Foundation
import CoreGraphics

/// 恒速（Constant Velocity）模型的二维卡尔曼滤波器
/// 状态向量: [x, y, vx, vy]
///
/// 参考 arXiv:2509.05334（移动端杀球测速）与 SORT 的 tracking-by-detection 范式。
/// 过程噪声取较大的加速度标准差，以适应羽毛球被击打后的剧烈减速（峰值可达 ~200g）。
public final class KalmanFilter2D {

    private var x: [Double]          // 状态 [x, y, vx, vy]
    private var P: [[Double]]        // 4x4 协方差
    private let accelStd: Double     // 过程噪声：加速度标准差 (px/s²)
    private let measStd: Double      // 量测噪声 (px)

    public var position: CGPoint { CGPoint(x: x[0], y: x[1]) }
    public var velocity: CGPoint { CGPoint(x: x[2], y: x[3]) }
    public var speedPxPerSec: Double { hypot(x[2], x[3]) }
    public var positionStd: Double { sqrt(max(0, (P[0][0] + P[1][1]) / 2)) }

    public init(at initial: CGPoint, accelStd: Double = 4.0e5, measStd: Double = 1.8) {
        self.accelStd = accelStd
        self.measStd = measStd
        x = [Double(initial.x), Double(initial.y), 0, 0]
        P = Self.identity()
        P[0][0] = 64; P[1][1] = 64
        P[2][2] = accelStd * accelStd * 0.01
        P[3][3] = accelStd * accelStd * 0.01
    }

    /// 用前两个实测点直接种入速度（避免滤波器从零速度缓慢收敛，提升击球瞬间精度）
    public func seedVelocity(from previous: CGPoint, dt: Double) {
        guard dt > 0 else { return }
        x[2] = (Double(previous.x) - x[0]) / dt
        x[3] = (Double(previous.y) - x[1]) / dt
        P[2][2] = accelStd * accelStd * 0.01
        P[3][3] = accelStd * accelStd * 0.01
    }

    public func predict(dt: Double) {
        // F = I + [[0,0,dt,0],[0,0,0,dt],[0,0,0,0],[0,0,0,0]]
        x = [x[0] + x[2] * dt, x[1] + x[3] * dt, x[2], x[3]]

        var F = Self.identity()
        F[0][2] = dt
        F[1][3] = dt
        P = Self.mul(Self.mul(F, P), Self.transpose(F))

        // Q：恒加速白噪声模型
        let q2 = accelStd * accelStd
        let dt2 = dt * dt, dt3 = dt2 * dt, dt4 = dt2 * dt2
        let Q = [
            [q2 * dt4 / 4, 0,          q2 * dt3 / 2, 0],
            [0,            q2 * dt4/4, 0,            q2 * dt3 / 2],
            [q2 * dt3 / 2, 0,          q2 * dt2,     0],
            [0,            q2 * dt3/2, 0,            q2 * dt2]
        ]
        P = Self.add(P, Q)
    }

    public func update(measured: CGPoint) {
        // S = H P Hᵀ + R（2x2），H 取前两行单位阵
        let r2 = measStd * measStd
        let S00 = P[0][0] + r2
        let S01 = P[0][1]
        let S10 = P[1][0]
        let S11 = P[1][1] + r2
        let det = S00 * S11 - S01 * S10
        guard abs(det) > 1e-12 else { return }
        let i00 = S11 / det, i01 = -S01 / det
        let i10 = -S10 / det, i11 = S00 / det

        // K = P Hᵀ S⁻¹（4x2），P Hᵀ 即 P 的前两列
        var K = [[Double]](repeating: [0, 0], count: 4)
        for i in 0..<4 {
            K[i][0] = P[i][0] * i00 + P[i][1] * i10
            K[i][1] = P[i][0] * i01 + P[i][1] * i11
        }

        let y0 = Double(measured.x) - x[0]
        let y1 = Double(measured.y) - x[1]
        for i in 0..<4 { x[i] += K[i][0] * y0 + K[i][1] * y1 }

        // P = (I - K H) P
        var IKH = Self.identity()
        for i in 0..<4 {
            IKH[i][0] -= K[i][0]
            IKH[i][1] -= K[i][1]
        }
        P = Self.mul(IKH, P)
    }

    /// 预测 + 更新一步完成
    public func step(dt: Double, measured: CGPoint) {
        predict(dt: dt)
        update(measured: measured)
    }

    // MARK: - 4x4 小矩阵工具

    static func identity() -> [[Double]] {
        var m = [[Double]](repeating: [Double](repeating: 0, count: 4), count: 4)
        for i in 0..<4 { m[i][i] = 1 }
        return m
    }

    static func mul(_ a: [[Double]], _ b: [[Double]]) -> [[Double]] {
        let n = a.count, k = a[0].count, m = b[0].count
        var out = [[Double]](repeating: [Double](repeating: 0, count: m), count: n)
        for i in 0..<n {
            for p in 0..<k {
                let aip = a[i][p]
                guard aip != 0 else { continue }
                for j in 0..<m { out[i][j] += aip * b[p][j] }
            }
        }
        return out
    }

    static func add(_ a: [[Double]], _ b: [[Double]]) -> [[Double]] {
        var out = a
        for i in out.indices { for j in out[i].indices { out[i][j] += b[i][j] } }
        return out
    }

    static func transpose(_ a: [[Double]]) -> [[Double]] {
        var out = a
        for i in a.indices { for j in a.indices { out[j][i] = a[i][j] } }
        return out
    }
}
