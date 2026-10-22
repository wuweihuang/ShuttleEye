package com.badmon.shuttleeye.analysis

import android.content.Context
import android.os.Environment
import com.badmon.shuttleeye.core.PhysicsReport
import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.core.SpeedResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 测速报告 CSV 导出 */
object CsvExporter {

    /** 导出到应用专属文档目录（无需存储权限），失败返回 null */
    fun export(
        context: Context,
        output: Output,
        calibration: ScaleCalibration,
        speed: SpeedResult,
        physics: PhysicsReport?
    ): File? {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "ShuttleEye_$stamp.csv")

            val sb = StringBuilder()
            sb.append('\uFEFF') // UTF-8 BOM，便于 Excel 识别中文
            sb.appendLine("# 羽眼 ShuttleEye 测速报告")
            sb.appendLine("# 导出时间,${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            sb.appendLine("# 峰值球速(km/h),${fmt(speed.peakKmh)}")
            sb.appendLine("# 峰值不确定度(km/h),${fmt(speed.peakUncertaintyKmh)}")
            sb.appendLine("# 平滑峰值(km/h),${fmt(speed.smoothedPeakKmh)}")
            sb.appendLine("# 平均球速(km/h),${fmt(speed.averageKmh)}")
            sb.appendLine("# 帧率(fps),${String.format(Locale.US, "%.0f", output.fps)}")
            if (physics != null) {
                sb.appendLine("# 气动系数k(1/m),${fmt3(physics.dragCoeffK)}")
                sb.appendLine("# 拟合优度R2,${fmt3(physics.r2)}")
                sb.appendLine("# 雷达等效速度(km/h),${physics.radarEquivalentKmh?.let { fmt(it) } ?: "--"}")
                sb.appendLine("# 物理一致性,${if (physics.isConsistent) "通过" else "未通过"}")
            }
            sb.appendLine("# 标定(m/px),${String.format(Locale.US, "%.6f", calibration.scale)}")
            sb.appendLine()
            sb.appendLine("段序号,t0(s),t1(s),速度(km/h),不确定度(±km/h)")
            for (seg in speed.segments) {
                sb.appendLine(
                    "${seg.index},${fmt(seg.t0)},${fmt(seg.t1)},${fmt(seg.speedKmh)},${fmt(seg.uncertaintyKmh)}"
                )
            }

            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)
}
