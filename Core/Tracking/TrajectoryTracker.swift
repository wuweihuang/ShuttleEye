import Foundation
import CoreGraphics

/// 轨迹跟踪器（tracking-by-detection）
///
/// 核心策略（源自 arXiv:2509.05334 并做增强）：
/// 1. 复合评分：S = 0.3·C_det + 0.7·P_kalman（时间平滑优先于检测置信度）
/// 2. 启发式速度边界：5 ~ 375 km/h 之外的检测直接剔除
/// 3. 自适应门限（本项目优化）：gate = max(W/4, 2.5·|v̂|·dt)，
///    低帧率（30fps）下球单帧位移可达数百像素，固定 W/4 门限会跟丢
/// 4. 丢失帧进入 coast 模式（仅预测不更新），超过 maxCoastFrames 判定本次击球结束
public final class TrajectoryTracker {

    public struct Config {
        public var confidenceWeight: Double = 0.3
        public var proximityWeight: Double = 0.7
        public var minDetectionConfidence: Double = 0.1
        /// 论文默认门限：帧宽的 1/4
        public var gateFrameFraction: Double = 0.25
        public var minSpeedKmh: Double = 5.0
        public var maxSpeedKmh: Double = 375.0
        public var maxCoastFrames: Int = 5
        public var accelStd: Double = 4.0e5
        public var measurementStd: Double = 1.8
        public init() {}
    }

    public var config: Config
    public private(set) var points: [TrackPoint] = []
    public private(set) var isFinished = false

    private var kalman: KalmanFilter2D?
    private var lastPoint: TrackPoint?
    private var coastCount = 0

    public init(config: Config = Config()) {
        self.config = config
    }

    public func reset() {
        points.removeAll()
        kalman = nil
        lastPoint = nil
        coastCount = 0
        isFinished = false
    }

    /// 处理一帧。返回被采纳的轨迹点（无采纳则返回 nil）。
    /// - Parameters:
    ///   - scaleMetersPerPixel: 时空标定比例（m/px）。nil 时跳过绝对速度校验（仅用相对门限）。
    @discardableResult
    public func process(frameIndex: Int,
                        time: Double,
                        detections: [Detection],
                        frameWidth: Double,
                        scaleMetersPerPixel: Double?) -> TrackPoint? {
        guard !isFinished else { return nil }

        // ---- 未初始化：取置信度最高的检测作为轨迹起点 ----
        guard let kf = kalman, let last = lastPoint else {
            guard let best = detections.max(by: { $0.confidence < $1.confidence }) else { return nil }
            let p = TrackPoint(frameIndex: frameIndex, time: time, center: best.center,
                               confidence: best.confidence, bboxSize: best.bboxSize,
                               blurLength: best.blurLength)
            kalman = KalmanFilter2D(at: best.center,
                                    accelStd: config.accelStd,
                                    measStd: config.measurementStd)
            points.append(p)
            lastPoint = p
            coastCount = 0
            return p
        }

        let dt = time - last.time
        guard dt > 0 else { return nil }
        kf.predict(dt: dt)

        // ---- 自适应门限 ----
        let predicted = kf.position
        let predictedStep = kf.speedPxPerSec * dt
        let gate = max(config.gateFrameFraction * frameWidth, predictedStep * 2.5)

        // ---- 复合评分选优 ----
        var best: (Detection, Double)?
        for d in detections where d.confidence >= config.minDetectionConfidence {
            let dist = hypot(d.center.x - predicted.x, d.center.y - predicted.y)
            guard dist <= gate else { continue }
            let proximity = max(0, 1 - dist / max(gate, 1))
            let score = config.confidenceWeight * d.confidence + config.proximityWeight * proximity
            if best == nil || score > best!.1 { best = (d, score) }
        }

        guard let chosen = best else {
            coastCount += 1
            if coastCount > config.maxCoastFrames { isFinished = true }
            return nil
        }

        // ---- 启发式速度边界（heuristic bounding）----
        if let scale = scaleMetersPerPixel {
            let d = hypot(chosen.0.center.x - last.center.x, chosen.0.center.y - last.center.y)
            let kmh = d / dt * scale * 3.6
            if kmh < config.minSpeedKmh || kmh > config.maxSpeedKmh {
                coastCount += 1
                if coastCount > config.maxCoastFrames { isFinished = true }
                return nil
            }
        }

        // ---- 更新滤波器（第二次测量时种入速度）----
        if points.count == 1 {
            kf.seedVelocity(from: last.center, dt: dt)
        }
        kf.update(measured: chosen.0.center)

        let p = TrackPoint(frameIndex: frameIndex, time: time, center: chosen.0.center,
                           confidence: chosen.0.confidence, bboxSize: chosen.0.bboxSize,
                           blurLength: chosen.0.blurLength)
        points.append(p)
        lastPoint = p
        coastCount = 0
        return p
    }
}
