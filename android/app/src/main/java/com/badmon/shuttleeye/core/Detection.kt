package com.badmon.shuttleeye.core

/**
 * 平台无关的检测基础类型（对应 iOS Core/Detection/Detection.swift）
 *
 * Android 端用 Double 精度的 Point2 / Size2 代替 CGPoint / CGSize，
 * 保证 core 包不依赖 android.graphics，便于单元测试与跨端语义一致。
 */

/** 二维点（Double 精度） */
data class Point2(val x: Double, val y: Double)

/** 二维尺寸（Double 精度） */
data class Size2(val width: Double, val height: Double)

/** 灰度帧（检测算法的输入，平台无关） */
class GrayscaleImage(val width: Int, val height: Int, val pixels: ByteArray) {
    inline fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/** 轨迹点（坐标均为原始帧像素坐标） */
data class TrackPoint(
    val frameIndex: Int,
    /** 秒（帧的 PTS，非帧序号，避免变帧率误差） */
    val time: Double,
    val center: Point2,
    val confidence: Double,
    val bboxSize: Size2,
    val blurLength: Double = 0.0,
    /** 由轨迹修复插值补全的点（非实测） */
    val isInterpolated: Boolean = false
)

/** 单帧检测结果
 * - center: 亮度加权质心（亚像素精度，优于包围盒中心）
 * - blurLength: 沿运动方向的模糊拖影长度估计（px），用于前缘补偿
 */
data class Detection(
    var center: Point2,
    val confidence: Double,
    var bboxSize: Size2,
    var blurLength: Double = 0.0
)

/** 检测器协议：经典 CV 检测器（及未来的模型检测器）均实现此协议 */
interface ShuttlecockDetector {
    /** previous 为上一帧（用于运动差分/多帧堆叠），可为 null（首帧） */
    fun detect(previous: GrayscaleImage?, current: GrayscaleImage): List<Detection>
}
