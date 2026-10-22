import Foundation
import CoreML
import CoreGraphics

/// TrackNet 风格 CoreML 检测器（可选增强，需将 TrackNetV3 权重转换为 CoreML）
///
/// 模型约定（由 Tools/convert_tracknet_to_coreml.py 导出）：
/// - 输入: "input"  — MLMultiArray [1, 3, H, W]，最近 3 帧灰度堆叠
/// - 输出: "output" — MLMultiArray [1, 1, H', W'] 热图（0~1）
///
/// 若 App 包内不存在 TrackNetShuttle.mlmodelc，则自动回退到经典 CV 检测器。
final class CoreMLShuttleDetector: ShuttlecockDetector {

    private let model: MLModel
    private let inputWidth: Int
    private let inputHeight: Int
    private var history: [GrayscaleImage] = []
    private let neededFrames = 3
    private let confidenceFloor = 0.2

    init?(modelNamed name: String = "TrackNetShuttle") {
        guard let url = Bundle.main.url(forResource: name, withExtension: "mlmodelc"),
              let m = try? MLModel(contentsOf: url) else { return nil }
        model = m
        guard let inputDesc = m.modelDescription.inputDescriptionsByName["input"],
              let constraint = inputDesc.imageConstraint else { return nil }
        inputWidth = Int(constraint.pixelWidth)
        inputHeight = Int(constraint.pixelHeight)
    }

    func detect(previous: GrayscaleImage?, current: GrayscaleImage) -> [Detection] {
        history.append(current)
        if history.count > neededFrames { history.removeFirst() }
        guard history.count == neededFrames,
              let multiArray = Self.buildInput(from: history,
                                               width: inputWidth,
                                               height: inputHeight) else { return [] }

        guard let input = try? MLDictionaryFeatureProvider(dictionary: ["input": MLFeatureValue(multiArray: multiArray)]),
              let output = try? model.prediction(from: input),
              let firstName = output.featureNames.first,
              let heatmap = output.featureValue(for: firstName)?.multiArrayValue else { return [] }

        return Self.decode(heatmap: heatmap, source: current)
    }

    // MARK: - 输入构造：3 帧灰度堆叠 [1,3,H,W]

    private static func buildInput(from frames: [GrayscaleImage], width: Int, height: Int) -> MLMultiArray? {
        guard let arr = try? MLMultiArray(shape: [1, 3, NSNumber(value: height), NSNumber(value: width)],
                                          dataType: .float32) else { return nil }
        let n = frames.count
        for (ci, frame) in frames.enumerated() {
            // 通道按时间顺序：最早帧在前（与 TrackNet 训练约定一致）
            let channel = ci - (3 - n)   // 不满 3 帧时前置补 0 通道（不写入即为 0）
            guard channel >= 0 else { continue }
            let sx = Double(frame.width) / Double(width)
            let sy = Double(frame.height) / Double(height)
            for y in 0..<height {
                let srcY = min(frame.height - 1, Int(Double(y) * sy))
                for x in 0..<width {
                    let srcX = min(frame.width - 1, Int(Double(x) * sx))
                    arr[[0, channel, y, x]] = NSNumber(value: Double(frame.pixels[srcY * frame.width + srcX]) / 255.0)
                }
            }
        }
        return arr
    }

    // MARK: - 热图解码：峰值 + 3×3 亚像素质心

    private static func decode(heatmap: MLMultiArray, source: GrayscaleImage) -> [Detection] {
        let count = heatmap.count
        guard count > 0 else { return [] }

        var maxVal = -Double.infinity
        var maxIdx = 0
        for i in 0..<count {
            let v = heatmap[i].doubleValue
            if v > maxVal { maxVal = v; maxIdx = i }
        }
        guard maxVal >= 0.2 else { return [] }

        let hmH = heatmap.shape.count >= 3 ? heatmap.shape[heatmap.shape.count - 2].intValue : 0
        let hmW = heatmap.shape.count >= 2 ? heatmap.shape[heatmap.shape.count - 1].intValue : 0
        guard hmW > 1, hmH > 1 else { return [] }
        let x0 = maxIdx % hmW
        let y0 = maxIdx / hmW

        // 3×3 亮度加权质心（亚像素）
        var sw = 0.0, sx = 0.0, sy = 0.0
        for dy in -1...1 {
            for dx in -1...1 {
                let x = min(max(x0 + dx, 0), hmW - 1)
                let y = min(max(y0 + dy, 0), hmH - 1)
                let w = heatmap[[y, x]].doubleValue
                sw += w; sx += Double(x) * w; sy += Double(y) * w
            }
        }
        guard sw > 0 else { return [] }
        let hx = sx / sw, hy = sy / sw

        // 热图坐标 → 处理图坐标 →（由调用方再映射原始坐标，此处直接映射到 source）
        let scaleX = Double(source.width) / Double(hmW)
        let scaleY = Double(source.height) / Double(hmH)

        return [Detection(center: CGPoint(x: hx * scaleX, y: hy * scaleY),
                          confidence: maxVal,
                          bboxSize: CGSize(width: 4, height: 4),
                          blurLength: 0)]
    }
}
