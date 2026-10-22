import Foundation
import CoreGraphics

/// 轨迹点（坐标均为原始帧像素坐标）
public struct TrackPoint: Codable, Equatable {
    public let frameIndex: Int
    /// 秒（帧的 PTS，非帧序号，避免变帧率误差）
    public let time: Double
    public let center: CGPoint
    public let confidence: Double
    public let bboxSize: CGSize
    public let blurLength: Double
    /// 由轨迹修复插值补全的点（非实测）
    public var isInterpolated: Bool

    public init(frameIndex: Int,
                time: Double,
                center: CGPoint,
                confidence: Double,
                bboxSize: CGSize,
                blurLength: Double = 0,
                isInterpolated: Bool = false) {
        self.frameIndex = frameIndex
        self.time = time
        self.center = center
        self.confidence = confidence
        self.bboxSize = bboxSize
        self.blurLength = blurLength
        self.isInterpolated = isInterpolated
    }
}
