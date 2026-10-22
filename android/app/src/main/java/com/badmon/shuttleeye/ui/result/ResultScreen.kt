package com.badmon.shuttleeye.ui.result

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmon.shuttleeye.analysis.CsvExporter
import com.badmon.shuttleeye.analysis.Output
import com.badmon.shuttleeye.core.PhysicsValidator
import com.badmon.shuttleeye.core.Point2
import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.core.SpeedCalculator
import com.badmon.shuttleeye.core.TrackPoint
import com.badmon.shuttleeye.model.SessionRecord
import com.badmon.shuttleeye.store.HistoryStore
import com.badmon.shuttleeye.ui.StatColumn
import com.badmon.shuttleeye.ui.SpeedCurveCanvas
import com.badmon.shuttleeye.ui.Theme
import com.badmon.shuttleeye.ui.card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/** 结果页：峰值测速 + 物理验证 + 轨迹复核（对应 iOS ResultView） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    output: Output,
    calibration: ScaleCalibration,
    source: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { HistoryStore(context) }

    var removed by remember { mutableStateOf(setOf<Int>()) }
    val basePoints = output.analysis.rectified

    // 剔除异常点后的重算结果
    val effectivePoints: List<TrackPoint> =
        if (removed.isEmpty()) basePoints
        else basePoints.filterIndexed { idx, _ -> idx !in removed }

    val speed = remember(effectivePoints, calibration) {
        SpeedCalculator.compute(effectivePoints, calibration)
    }
    val physics = remember(effectivePoints, calibration) {
        if (speed != null) PhysicsValidator.validate(effectivePoints, calibration) else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测速结果") },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("关闭") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val file = speed?.let {
                                    CsvExporter.export(context, output, calibration, it, physics)
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (file != null) "CSV 已导出：${file.absolutePath}" else "导出失败",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = speed != null
                    ) { Text("导出 CSV", color = Theme.accent) }
                    TextButton(
                        onClick = {
                            val s = speed ?: return@TextButton
                            history.add(
                                SessionRecord(
                                    source = source,
                                    fps = output.fps,
                                    peakKmh = s.peakKmh,
                                    uncertaintyKmh = s.peakUncertaintyKmh,
                                    smoothedPeakKmh = s.smoothedPeakKmh,
                                    radarEquivalentKmh = physics?.radarEquivalentKmh,
                                    dragCoeffK = physics?.dragCoeffK,
                                    r2 = physics?.r2,
                                    physicsConsistent = physics?.isConsistent ?: false,
                                    calibration = calibration,
                                    points = effectivePoints,
                                    speedCurve = s.segments.map { listOf(it.t0, it.speedKmh) }
                                )
                            )
                            Toast.makeText(context, "已保存到历史记录", Toast.LENGTH_SHORT).show()
                            onClose()
                        },
                        enabled = speed != null
                    ) { Text("保存", color = Theme.accent) }
                }
            )
        },
        containerColor = Theme.screen
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- 峰值卡片 ----
            Column(
                modifier = Modifier.card().fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("峰值球速（击球瞬间）", fontSize = 14.sp, color = Theme.secondaryText)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = speed?.let { "%.1f".format(it.peakKmh) } ?: "--",
                        fontSize = 64.sp,
                        color = Theme.accent
                    )
                    Text(
                        " km/h",
                        fontSize = 20.sp,
                        color = Theme.secondaryText,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                if (speed != null) {
                    Text(
                        "±${"%.1f".format(speed.peakUncertaintyKmh)} km/h（像素量化误差）",
                        fontSize = 12.sp,
                        color = Theme.secondaryText
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatColumn("平滑峰值", "%.1f".format(speed.smoothedPeakKmh))
                        StatColumn("平均球速", "%.1f".format(speed.averageKmh))
                        StatColumn("帧率", "%.0f fps".format(output.fps))
                    }
                }
            }

            // ---- 物理一致性验证 ----
            Column(modifier = Modifier.card().fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (physics?.isConsistent == true) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (physics?.isConsistent == true) Theme.good else Theme.warn,
                        modifier = Modifier.height(20.dp)
                    )
                    Text("  物理一致性验证", fontSize = 16.sp)
                }
                if (physics != null) {
                    Text(
                        if (physics.isConsistent)
                            "轨迹衰减与羽毛球空气阻力模型吻合，测速结果自洽可信。"
                        else
                            "轨迹与阻力模型存在偏差，结果仅供参考。建议：提高帧率、固定机位、重新标定。",
                        fontSize = 12.sp,
                        color = Theme.secondaryText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatColumn("气动系数 k", "%.3f /m".format(physics.dragCoeffK))
                        StatColumn("拟合优度 R²", "%.3f".format(physics.r2))
                    }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatColumn(
                            "雷达等效速度",
                            physics.radarEquivalentKmh?.let { "%.1f km/h".format(it) } ?: "--"
                        )
                        StatColumn(
                            "可疑帧段",
                            if (physics.suspiciousSegments.isEmpty()) "无" else "${physics.suspiciousSegments.size} 段"
                        )
                    }
                    Text(
                        "雷达等效速度 = 距击球点 4m 处的推算速度（v(x)=v₀·e^(-k·x)），可与专业雷达枪读数直接对比。",
                        fontSize = 10.sp,
                        color = Theme.secondaryText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Text(
                        "有效段数不足，无法进行物理验证。",
                        fontSize = 12.sp,
                        color = Theme.secondaryText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // ---- 速度曲线 ----
            if (speed != null) {
                Column(modifier = Modifier.card().fillMaxWidth()) {
                    Text("速度曲线（逐帧段速度）", fontSize = 16.sp)
                    SpeedCurveCanvas(
                        segments = speed.segments,
                        peakIndex = speed.peakSegmentIndex,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "点击轨迹图上的点可剔除异常帧并自动重算",
                        fontSize = 10.sp,
                        color = Theme.secondaryText,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // ---- 轨迹叠加（可点击剔除）----
            TrajectoryOverlay(
                backgroundImage = output.backgroundImage,
                points = basePoints,
                removed = removed,
                onToggleRemove = { idx ->
                    removed = if (idx in removed) removed - idx else removed + idx
                },
                modifier = Modifier.fillMaxWidth().height(260.dp)
            )
        }
    }
}

/** 轨迹叠加视图（支持点击剔除异常点后重算，对应 iOS TrajectoryOverlayView） */
@Composable
fun TrajectoryOverlay(
    backgroundImage: Bitmap?,
    points: List<TrackPoint>,
    removed: Set<Int>,
    onToggleRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.card()) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        val bmpW = backgroundImage?.width ?: 16
        val bmpH = backgroundImage?.height ?: 9
        val scale = minOf(containerW / bmpW, containerH / bmpH).let { if (it > 0 && it.isFinite()) it else 1f }
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val offX = (containerW - drawW) / 2
        val offY = (containerH - drawH) / 2

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points, removed, backgroundImage) {
                    detectTapGestures { loc ->
                        // 命中检测：显示坐标下找最近点（阈值 = 显示宽度的 3%）
                        val threshold = drawW * 0.03f
                        var bestIdx = -1
                        var bestDist = Float.MAX_VALUE
                        points.forEachIndexed { i, p ->
                            if (i in removed) return@forEachIndexed
                            val dx = offX + (p.center.x * scale).toFloat() - loc.x
                            val dy = offY + (p.center.y * scale).toFloat() - loc.y
                            val d = hypot(dx, dy)
                            if (d < bestDist) {
                                bestDist = d
                                bestIdx = i
                            }
                        }
                        if (bestIdx >= 0 && bestDist <= threshold) onToggleRemove(bestIdx)
                    }
                }
        ) {
            // 背景（首帧）
            backgroundImage?.let {
                drawImage(
                    image = it.asImageBitmap(),
                    dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                    dstSize = IntSize(drawW.toInt(), drawH.toInt())
                )
            }

            // 轨迹线（未剔除点）
            val visible = points.withIndex().filter { it.index !in removed }
            if (visible.isNotEmpty()) {
                val path = Path()
                var started = false
                for ((_, p) in visible) {
                    val o = Offset(offX + (p.center.x * scale).toFloat(), offY + (p.center.y * scale).toFloat())
                    if (!started) {
                        path.moveTo(o.x, o.y)
                        started = true
                    } else {
                        path.lineTo(o.x, o.y)
                    }
                }
                drawPath(path, color = Theme.accent.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx()))
            }

            // 速度渐变点：插值点灰色小点，实测点青色
            for ((_, p) in visible) {
                val o = Offset(offX + (p.center.x * scale).toFloat(), offY + (p.center.y * scale).toFloat())
                val r = if (p.isInterpolated) 2.5.dp.toPx() else 3.5.dp.toPx()
                drawCircle(
                    color = if (p.isInterpolated) androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.6f) else Theme.cyan,
                    radius = r,
                    center = o
                )
            }
        }
    }
}
