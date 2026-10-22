import Foundation
import CoreGraphics

/// 时空标定（Spatiotemporal Scaling，arXiv:2509.05334 §3.2.1）
///
/// 用户在场地上选取一条与相机成像平面平行、且与击球点等距的已知长度线段
/// （如双打后发球线），得到比例尺 S_f = d_real / d_pixel [米/像素]。
public struct ScaleCalibration: Codable, Equatable {
    public let pixelA: CGPoint
    public let pixelB: CGPoint
    public let realDistanceMeters: Double

    public init(pixelA: CGPoint, pixelB: CGPoint, realDistanceMeters: Double) {
        self.pixelA = pixelA
        self.pixelB = pixelB
        self.realDistanceMeters = realDistanceMeters
    }

    /// 比例尺（米/像素）
    public var scale: Double {
        let d = hypot(pixelB.x - pixelA.x, pixelB.y - pixelA.y)
        guard d > 1e-6 else { return 0 }
        return realDistanceMeters / d
    }

    public var isValid: Bool { scale > 0 && scale < 0.5 }

    /// 标准羽毛球场地尺寸预设（单位：米）
    public static let courtPresets: [(name: String, meters: Double)] = [
        ("双打边线全场宽 6.10m", 6.10),
        ("单打边线全场宽 5.18m", 5.18),
        ("半场长度（网→底线）6.70m", 6.70),
        ("前发球线距网 1.98m", 1.98),
        ("双打后发球线距底线 0.76m", 0.76),
        ("前场发球线→双打后发球线 5.94m", 5.94)
    ]
}
