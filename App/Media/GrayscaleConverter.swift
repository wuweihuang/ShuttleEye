import Foundation
import CoreVideo
import CoreGraphics
import ShuttleEyeCore

/// CVPixelBuffer(BGRA) → 灰度帧，多行并发降采样
///
/// 检测在降采样图上进行（最长边默认 960px），
/// 检测坐标 × downscale 映射回原始分辨率坐标系。
enum GrayscaleConverter {

    struct Converted {
        let image: GrayscaleImage
        /// 原始尺寸 / 处理尺寸 的整数倍率
        let downscale: Int
    }

    static func convert(_ pb: CVPixelBuffer, maxDimension: Int = 960) -> Converted? {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }

        guard let base = CVPixelBufferGetBaseAddress(pb) else { return nil }
        guard CVPixelBufferGetPixelFormatType(pb) == kCVPixelFormatType_32BGRA else { return nil }

        let w = CVPixelBufferGetWidth(pb)
        let h = CVPixelBufferGetHeight(pb)
        let bpr = CVPixelBufferGetBytesPerRow(pb)
        let step = max(1, Int((Double(max(w, h)) / Double(maxDimension)).rounded(.up)))
        let dw = w / step
        let dh = h / step
        guard dw > 4, dh > 4 else { return nil }

        var out = [UInt8](repeating: 0, count: dw * dh)
        let buf = base.assumingMemoryBound(to: UInt8.self)

        DispatchQueue.concurrentPerform(iterations: dh) { row in
            for col in 0..<dw {
                var acc = 0
                var cnt = 0
                let y0 = row * step
                let x0 = col * step
                for dy in 0..<step {
                    let y = min(y0 + dy, h - 1)
                    let rowPtr = buf + y * bpr
                    for dx in 0..<step {
                        let x = min(x0 + dx, w - 1)
                        let o = x * 4   // BGRA
                        let b = Int(rowPtr[o])
                        let g = Int(rowPtr[o + 1])
                        let r = Int(rowPtr[o + 2])
                        acc += (77 * r + 151 * g + 28 * b) >> 8
                        cnt += 1
                    }
                }
                out[row * dw + col] = UInt8(min(max(acc / max(cnt, 1), 0), 255))
            }
        }

        return Converted(image: GrayscaleImage(width: dw, height: dh, pixels: out), downscale: step)
    }
}

/// GrayscaleImage → CGImage（用于标定界面与轨迹叠加背景）
extension GrayscaleImage {
    func toCGImage() -> CGImage? {
        var px = pixels
        return px.withUnsafeMutableBytes { raw -> CGImage? in
            guard let base = raw.baseAddress else { return nil }
            guard let ctx = CGContext(data: base,
                                      width: width,
                                      height: height,
                                      bitsPerComponent: 8,
                                      bytesPerRow: width,
                                      space: CGColorSpaceCreateDeviceGray(),
                                      bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
            return ctx.makeImage()
        }
    }
}
