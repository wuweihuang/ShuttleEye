package com.badmon.shuttleeye.ui.calibration

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmon.shuttleeye.core.Point2
import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.ui.Theme
import com.badmon.shuttleeye.ui.card
import kotlin.math.max
import kotlin.math.min

/** 场地标定视图：在冻结帧上点击两个点，选择真实距离（对应 iOS CalibrationView） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    image: Bitmap,
    onDone: (ScaleCalibration?) -> Unit
) {
    var pointA by remember { mutableStateOf<Point2?>(null) }
    var pointB by remember { mutableStateOf<Point2?>(null) }
    var presetIndex by remember { mutableIntStateOf(0) }
    var presetsMenuOpen by remember { mutableStateOf(false) }

    val presets = ScaleCalibration.courtPresets

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标定比例尺") },
                navigationIcon = {
                    IconButton(onClick = { onDone(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "取消")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val a = pointA ?: return@IconButton
                            val b = pointB ?: return@IconButton
                            onDone(
                                ScaleCalibration(
                                    pixelA = a,
                                    pixelB = b,
                                    realDistanceMeters = presets[presetIndex].meters
                                )
                            )
                        },
                        enabled = pointA != null && pointB != null
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "确定", tint = Theme.accent)
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 图像 + 标注（aspect-fit）----
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val density = LocalDensity.current
                val containerW = with(density) { maxWidth.toPx() }
                val containerH = with(density) { maxHeight.toPx() }
                val fit = aspectFitRect(image.width.toFloat(), image.height.toFloat(), containerW, containerH)
                val fitW = with(density) { (fit.first / density.density).dp }
                val fitH = with(density) { (fit.second / density.density).dp }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "冻结帧",
                        modifier = Modifier
                            .width(fitW)
                            .height(fitH),
                        contentScale = ContentScale.FillBounds
                    )

                    // 已选点与连线（叠加层与图像同区域）
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(image) {
                                detectTapGestures { loc ->
                                    val sx = image.width / fit.third
                                    val sy = image.height / fit.third
                                    val ix = (loc.x - (containerW - fit.first) / 2) / fit.third
                                    val iy = (loc.y - (containerH - fit.second) / 2) / fit.third
                                    if (ix < 0 || iy < 0 || ix > sx || iy > sy) return@detectTapGestures
                                    val p = Point2(ix, iy)
                                    if (pointA == null) {
                                        pointA = p
                                    } else if (pointB == null) {
                                        pointB = p
                                    } else {
                                        pointA = p
                                        pointB = null
                                    }
                                }
                            }
                    ) {
                        val cx = size.width / 2
                        val cy = size.height / 2
                        val drawW = fit.first
                        val drawH = fit.second
                        fun toDisplay(p: Point2): Offset = Offset(
                            x = (cx - drawW / 2 + (p.x * fit.third).toFloat()),
                            y = (cy - drawH / 2 + (p.y * fit.third).toFloat())
                        )

                        val a = pointA
                        val b = pointB
                        if (a != null && b != null) {
                            val p1 = toDisplay(a)
                            val p2 = toDisplay(b)
                            drawLine(
                                color = Theme.accent,
                                start = p1,
                                end = p2,
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                            )
                        }
                        for (p in listOfNotNull(a, b)) {
                            val o = toDisplay(p)
                            drawCircle(color = Theme.accent, radius = 6.dp.toPx(), center = o)
                            drawCircle(
                                color = androidx.compose.ui.graphics.Color.White,
                                radius = 6.dp.toPx(),
                                center = o,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            }

            // ---- 底部说明与预设 ----
            Column(
                modifier = Modifier.card().fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "在场地上点击两个点：选择一条与相机成像平面平行、且与击球点等距的已知线段（如双打边线）。",
                    fontSize = 12.sp,
                    color = Theme.secondaryText
                )
                Box {
                    TextButton(onClick = { presetsMenuOpen = true }) {
                        Text("参考长度：${presets[presetIndex].name}", color = Theme.accent)
                    }
                    DropdownMenu(expanded = presetsMenuOpen, onDismissRequest = { presetsMenuOpen = false }) {
                        presets.forEachIndexed { i, preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name) },
                                onClick = {
                                    presetIndex = i
                                    presetsMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            pointA == null -> "① 点击第一个点"
                            pointB == null -> "② 点击第二个点"
                            else -> "✓ 完成，可重新点击修改"
                        },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    if (pointA != null || pointB != null) {
                        Button(
                            onClick = {
                                pointA = null
                                pointB = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Theme.card)
                        ) {
                            Text("重置", color = Theme.accent)
                        }
                    }
                }
            }
        }
    }
}

/** aspect-fit 计算并居中，返回 (fitWidth, fitHeight, scale) */
internal fun aspectFitRect(imageW: Float, imageH: Float, containerW: Float, containerH: Float): Triple<Float, Float, Float> {
    val scale = min(containerW / max(1f, imageW), containerH / max(1f, imageH))
    return Triple(imageW * scale, imageH * scale, scale)
}
