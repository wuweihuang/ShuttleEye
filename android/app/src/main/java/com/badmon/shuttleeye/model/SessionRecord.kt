package com.badmon.shuttleeye.model

import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.core.TrackPoint
import java.util.UUID

/** 历史记录条目（对应 iOS SessionRecord） */
data class SessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val dateMillis: Long = System.currentTimeMillis(),
    /** "实时测量" / "视频分析" */
    val source: String,
    val fps: Double,
    val peakKmh: Double,
    val uncertaintyKmh: Double,
    val smoothedPeakKmh: Double,
    val radarEquivalentKmh: Double?,
    val dragCoeffK: Double?,
    val r2: Double?,
    val physicsConsistent: Boolean,
    val calibration: ScaleCalibration,
    /** 修复后的轨迹（原始像素坐标） */
    val points: List<TrackPoint>,
    /** 速度曲线 (t, km/h) */
    val speedCurve: List<List<Double>>
)
