package com.badmon.shuttleeye.media

import android.graphics.Bitmap
import com.badmon.shuttleeye.core.GrayscaleImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 灰度转换（对应 iOS GrayscaleConverter）：降采样 + 亮度加权 */
object GrayscaleConverter {

    data class Result(
        val image: GrayscaleImage,
        /** 降采样倍率（原始像素 / 灰度图像素），用于坐标映射回原始帧 */
        val downscale: Double
    )

    /** Bitmap → 灰度帧（最大边限制为 maxDimension） */
    fun convert(source: Bitmap, maxDimension: Int = 960): Result? {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return null

        val scale = min(1.0, maxDimension.toDouble() / max(w, h))
        var bmp = source
        var owned = false
        if (scale < 1.0) {
            val nw = max(1, (w * scale).roundToInt())
            val nh = max(1, (h * scale).roundToInt())
            bmp = Bitmap.createScaledBitmap(source, nw, nh, true)
            owned = bmp !== source
        }

        val gw = bmp.width
        val gh = bmp.height
        val out = ByteArray(gw * gh)
        val pixels = IntArray(gw * gh)
        try {
            bmp.getPixels(pixels, 0, gw, 0, 0, gw, gh)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // ITU-R BT.601 亮度
                out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
        } finally {
            if (owned) bmp.recycle()
        }

        return Result(GrayscaleImage(gw, gh, out), downscale = w.toDouble() / gw)
    }

    /** 等比缩放位图（最大边限制），用于首帧背景/标定图显示 */
    fun downscaleBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val m = max(source.width, source.height)
        if (m <= maxDimension) return source
        val s = maxDimension.toDouble() / m
        val nw = max(1, (source.width * s).roundToInt())
        val nh = max(1, (source.height * s).roundToInt())
        val scaled = Bitmap.createScaledBitmap(source, nw, nh, true)
        return if (scaled !== source) scaled else source
    }
}
