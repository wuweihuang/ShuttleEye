package com.badmon.shuttleeye.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 经典 CV 运动检测器（无需训练模型的开箱即用方案）
 *
 * 原理：相邻帧差分 → 自适应阈值（mean + 3σ）→ 连通域提取 →
 *       面积/尺寸过滤 → 亮度加权亚像素质心
 *
 * 适用条件：三脚架固定机位（机位抖动会引入全画面差分噪声）。
 * 高帧率（120/240fps）下羽毛球呈现为亮白色小斑点/短拖影，此方法实测可靠。
 */
class ClassicalMotionDetector(var config: Config = Config()) : ShuttlecockDetector {

    data class Config(
        /** 差分幅度下限（8-bit 灰度） */
        val baseThreshold: Double = 26.0,
        /** 自适应阈值系数：thr = max(base, mean + sigmaScale·σ) */
        val sigmaScale: Double = 3.0,
        val minArea: Int = 3,
        val maxArea: Int = 4000,
        /** 连通域最大边长占帧宽比例（排除人影等大块运动） */
        val maxDimensionFraction: Double = 0.08,
        val maxBlobs: Int = 48
    )

    // 复用的 DFS 栈缓冲（帧尺寸固定时避免反复分配）
    private var stack: IntArray = IntArray(0)

    override fun detect(previous: GrayscaleImage?, current: GrayscaleImage): List<Detection> {
        val prev = previous ?: return emptyList()
        if (prev.width != current.width ||
            prev.height != current.height ||
            prev.pixels.size != current.pixels.size
        ) return emptyList()

        val w = current.width
        val h = current.height
        val n = w * h

        // ---- 帧差分 + 统计 ----
        val diff = IntArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val d = abs((current.pixels[i].toInt() and 0xFF) - (prev.pixels[i].toInt() and 0xFF))
            diff[i] = d
            sum += d
        }
        val mean = sum / n
        var varSum = 0.0
        for (i in 0 until n) {
            val d = diff[i].toDouble()
            varSum += (d - mean) * (d - mean)
        }
        val std = sqrt(varSum / n)
        val thr = max(config.baseThreshold, mean + config.sigmaScale * std)
        val thrI = thr.toInt()

        // ---- 连通域提取（显式栈 DFS，避免递归栈溢出）----
        val visited = BooleanArray(n)
        if (stack.size < n) stack = IntArray(n)
        val results = ArrayList<Detection>()
        val maxDim = w * config.maxDimensionFraction

        for (start in 0 until n) {
            if (visited[start] || diff[start] <= thrI) continue
            visited[start] = true
            var sp = 0
            stack[sp++] = start

            var area = 0
            var sumX = 0.0
            var sumY = 0.0
            var sumW = 0.0
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                val v = diff[idx]
                area++
                sumX += (x * v).toDouble()
                sumY += (y * v).toDouble()
                sumW += v.toDouble()
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                // 8 邻域
                val yLo = if (y > 0) y - 1 else 0
                val yHi = if (y < h - 1) y + 1 else h - 1
                val xLo = if (x > 0) x - 1 else 0
                val xHi = if (x < w - 1) x + 1 else w - 1
                for (ny in yLo..yHi) {
                    val rowBase = ny * w
                    for (nx in xLo..xHi) {
                        val nIdx = rowBase + nx
                        if (!visited[nIdx] && diff[nIdx] > thrI) {
                            visited[nIdx] = true
                            stack[sp++] = nIdx
                        }
                    }
                }
            }

            // ---- 过滤 ----
            if (area < config.minArea || area > config.maxArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (max(bw, bh).toDouble() > maxDim) continue
            if (sumW <= 0) continue

            // 亮度加权质心（亚像素）
            val cx = sumX / sumW
            val cy = sumY / sumW
            // 拖影估计：长轴 - 短轴（圆形斑点为 0，运动拖影为正）
            val blur = (max(bw, bh) - min(bw, bh)).toDouble()
            // 置信度：归一化差分能量（经验映射）
            val energy = sumW / area
            val confidence = min(1.0, energy / 160.0)

            results.add(
                Detection(
                    center = Point2(cx, cy),
                    confidence = confidence,
                    bboxSize = Size2(bw.toDouble(), bh.toDouble()),
                    blurLength = blur
                )
            )
            if (results.size >= config.maxBlobs) break
        }
        return results
    }
}
