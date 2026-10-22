import Foundation
import CoreGraphics

/// 经典 CV 运动检测器（无需训练模型的开箱即用方案）
///
/// 原理：相邻帧差分 → 自适应阈值（mean + 3σ）→ 连通域提取 →
///       面积/尺寸过滤 → 亮度加权亚像素质心
///
/// 适用条件：三脚架固定机位（机位抖动会引入全画面差分噪声）。
/// 高帧率（120/240fps）下羽毛球呈现为亮白色小斑点/短拖影，此方法实测可靠。
public final class ClassicalMotionDetector: ShuttlecockDetector {

    public struct Config {
        /// 差分幅度下限（8-bit 灰度）
        public var baseThreshold: Double = 26
        /// 自适应阈值系数：thr = max(base, mean + sigmaScale·σ)
        public var sigmaScale: Double = 3.0
        public var minArea: Int = 3
        public var maxArea: Int = 4000
        /// 连通域最大边长占帧宽比例（排除人影等大块运动）
        public var maxDimensionFraction: Double = 0.08
        public var maxBlobs: Int = 48
        public init() {}
    }

    public var config = Config()

    public init() {}

    public func detect(previous: GrayscaleImage?, current: GrayscaleImage) -> [Detection] {
        guard let prev = previous,
              prev.width == current.width,
              prev.height == current.height,
              prev.pixels.count == current.pixels.count else { return [] }

        let w = current.width
        let h = current.height
        let n = w * h

        // ---- 帧差分 + 统计 ----
        var diff = [UInt8](repeating: 0, count: n)
        var sum = 0.0
        for i in 0..<n {
            let d = abs(Int(current.pixels[i]) - Int(prev.pixels[i]))
            diff[i] = UInt8(min(d, 255))
            sum += Double(d)
        }
        let mean = sum / Double(n)
        var varSum = 0.0
        for i in 0..<n {
            let d = Double(diff[i])
            varSum += (d - mean) * (d - mean)
        }
        let std = sqrt(varSum / Double(n))
        let thr = max(config.baseThreshold, mean + config.sigmaScale * std)
        let thrI = Int(thr)

        // ---- 连通域提取（BFS，避免递归栈溢出）----
        var visited = [Bool](repeating: false, count: n)
        var stack: [Int] = []
        stack.reserveCapacity(256)
        var results: [Detection] = []
        let maxDim = Double(w) * config.maxDimensionFraction

        for start in 0..<n where !visited[start] && Int(diff[start]) > thrI {
            visited[start] = true
            stack.removeAll(keepingCapacity: true)
            stack.append(start)

            var area = 0
            var sumX = 0.0, sumY = 0.0, sumW = 0.0
            var minX = w, maxX = 0, minY = h, maxY = 0

            while let idx = stack.popLast() {
                let x = idx % w, y = idx / w
                let v = Int(diff[idx])
                area += 1
                sumX += Double(x * v); sumY += Double(y * v); sumW += Double(v)
                if x < minX { minX = x }; if x > maxX { maxX = x }
                if y < minY { minY = y }; if y > maxY { maxY = y }

                // 8 邻域
                let yLo = y > 0 ? y - 1 : 0
                let yHi = y < h - 1 ? y + 1 : h - 1
                let xLo = x > 0 ? x - 1 : 0
                let xHi = x < w - 1 ? x + 1 : w - 1
                for ny in yLo...yHi {
                    let rowBase = ny * w
                    for nx in xLo...xHi {
                        let nIdx = rowBase + nx
                        if !visited[nIdx] && Int(diff[nIdx]) > thrI {
                            visited[nIdx] = true
                            stack.append(nIdx)
                        }
                    }
                }
            }

            // ---- 过滤 ----
            guard area >= config.minArea, area <= config.maxArea else { continue }
            let bw = maxX - minX + 1
            let bh = maxY - minY + 1
            guard Double(max(bw, bh)) <= maxDim else { continue }
            guard sumW > 0 else { continue }

            // 亮度加权质心（亚像素）
            let cx = sumX / sumW
            let cy = sumY / sumW
            // 拖影估计：长轴 - 短轴（圆形斑点为 0，运动拖影为正）
            let blur = Double(max(bw, bh) - min(bw, bh))
            // 置信度：归一化差分能量（经验映射）
            let energy = sumW / Double(area)
            let confidence = min(1.0, energy / 160.0)

            results.append(Detection(center: CGPoint(x: cx, y: cy),
                                     confidence: confidence,
                                     bboxSize: CGSize(width: bw, height: bh),
                                     blurLength: blur))
            if results.count >= config.maxBlobs { break }
        }
        return results
    }
}
