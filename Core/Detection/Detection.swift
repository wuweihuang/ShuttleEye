import Foundation
import CoreGraphics

/// 灰度帧（检测算法的输入，平台无关）
public struct GrayscaleImage {
    public let width: Int
    public let height: Int
    public let pixels: [UInt8]

    public init(width: Int, height: Int, pixels: [UInt8]) {
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    @inline(__always)
    public func at(_ x: Int, _ y: Int) -> UInt8 {
        pixels[y * width + x]
    }
}

/// 单帧检测结果
/// - center: 亮度加权质心（亚像素精度，优于包围盒中心）
/// - blurLength: 沿运动方向的模糊拖影长度估计（px），用于前缘补偿
public struct Detection: Codable, Equatable {
    public var center: CGPoint
    public var confidence: Double
    public var bboxSize: CGSize
    public var blurLength: Double

    public init(center: CGPoint, confidence: Double, bboxSize: CGSize, blurLength: Double = 0) {
        self.center = center
        self.confidence = confidence
        self.bboxSize = bboxSize
        self.blurLength = blurLength
    }
}

/// 检测器协议：CoreML 模型检测器与经典 CV 检测器均实现此协议
public protocol ShuttlecockDetector: AnyObject {
    /// previous 为上一帧（用于运动差分/多帧堆叠），可为 nil（首帧）
    func detect(previous: GrayscaleImage?, current: GrayscaleImage) -> [Detection]
}
