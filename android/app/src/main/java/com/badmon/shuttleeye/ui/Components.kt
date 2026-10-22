package com.badmon.shuttleeye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmon.shuttleeye.core.SegmentSpeed
import kotlin.math.max

/** 小型统计项（数值 + 名称） */
@Composable
fun StatColumn(name: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = value,
            fontSize = 15.sp,
            color = Color.White
        )
        Text(
            text = name,
            fontSize = 10.sp,
            color = Theme.secondaryText
        )
    }
}

/** 速度曲线（逐帧段速度，Canvas 实现，对应 iOS SpeedChartView）
 * 峰值段高亮，其余青色细线。
 */
@Composable
fun SpeedCurveCanvas(
    segments: List<SegmentSpeed>,
    peakIndex: Int,
    modifier: Modifier = Modifier
) {
    if (segments.isEmpty()) return

    val accent = Theme.accent
    val cyan = Theme.cyan
    val labelColor = Theme.secondaryText

    val minX = segments.minOf { it.t0 }
    val maxX = segments.maxOf { it.t1 }
    val minY = segments.minOf { it.speedKmh }
    val maxY = segments.maxOf { it.speedKmh }

    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        val padLeft = 44.dp.toPx()
        val padRight = 12.dp.toPx()
        val padTop = 18.dp.toPx()
        val padBottom = 22.dp.toPx()
        val plotW = max(1f, size.width - padLeft - padRight)
        val plotH = max(1f, size.height - padTop - padBottom)
        val tRange = max(1e-9, maxX - minX)
        val vRange = max(1e-9, maxY - minY)

        fun px(t: Double): Float = padLeft + ((t - minX) / tRange).toFloat() * plotW
        fun py(v: Double): Float = padTop + (1f - ((v - minY) / vRange).toFloat()) * plotH

        // 坐标参考：纵轴最大/最小值、横轴时间
        val yMaxLabel = "${maxY.toInt()} km/h"
        drawText(
            textMeasurer = textMeasurer,
            text = yMaxLabel,
            style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor),
            topLeft = Offset(4.dp.toPx(), 0f)
        )
        val yMinLabel = "${minY.toInt()}"
        drawText(
            textMeasurer = textMeasurer,
            text = yMinLabel,
            style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor),
            topLeft = Offset(4.dp.toPx(), padTop + plotH - 6.dp.toPx())
        )
        drawText(
            textMeasurer = textMeasurer,
            text = "t (s) ${"%.2f".format(minX)} → ${"%.2f".format(maxX)}",
            style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor),
            topLeft = Offset(padLeft, size.height - 14.dp.toPx())
        )

        // 逐段折线：峰值段高亮
        for (i in segments.indices) {
            val s = segments[i]
            val start = Offset(px(s.t0), py(s.speedKmh))
            val end = Offset(px(s.t1), py(s.speedKmh))
            val isPeak = i == peakIndex
            drawLine(
                color = if (isPeak) accent else cyan.copy(alpha = 0.6f),
                start = start,
                end = end,
                strokeWidth = if (isPeak) 3.dp.toPx() else 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = if (isPeak) accent else cyan.copy(alpha = 0.5f),
                radius = if (isPeak) 4.dp.toPx() / 2 else 2.5f,
                center = start
            )
            if (i == segments.size - 1) {
                drawCircle(
                    color = cyan.copy(alpha = 0.5f),
                    radius = 2.5f,
                    center = end
                )
            }
        }
    }
}

/** 通用空态文本 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Text(text = text, color = Theme.secondaryText)
    }
}

@Suppress("unused")
private val keepColor: Color = Color.Transparent
