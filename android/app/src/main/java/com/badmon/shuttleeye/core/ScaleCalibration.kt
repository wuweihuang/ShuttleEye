package com.badmon.shuttleeye.core

import kotlin.math.hypot

/** 时空标定（Spatiotemporal Scaling，arXiv:2509.05334 §3.2.1）
 *
 * 用户在场地上选取一条与相机成像平面平行、且与击球点等距的已知长度线段
 * （如双打后发球线），得到比例尺 S_f = d_real / d_pixel [米/像素]。
 */
data class ScaleCalibration(
    val pixelA: Point2,
    val pixelB: Point2,
    val realDistanceMeters: Double
) {

    /** 比例尺（米/像素） */
    val scale: Double
        get() {
            val d = hypot(pixelB.x - pixelA.x, pixelB.y - pixelA.y)
            if (d <= 1e-6) return 0.0
            return realDistanceMeters / d
        }

    val isValid: Boolean get() = scale > 0 && scale < 0.5

    /** 场地预设（名称 + 真实长度） */
    data class CourtPreset(val name: String, val meters: Double)

    companion object {
        /** 标准羽毛球场地尺寸预设（单位：米） */
        val courtPresets = listOf(
            CourtPreset("双打边线全场宽 6.10m", 6.10),
            CourtPreset("单打边线全场宽 5.18m", 5.18),
            CourtPreset("半场长度（网→底线）6.70m", 6.70),
            CourtPreset("前发球线距网 1.98m", 1.98),
            CourtPreset("双打后发球线距底线 0.76m", 0.76),
            CourtPreset("前场发球线→双打后发球线 5.94m", 5.94)
        )
    }
}
