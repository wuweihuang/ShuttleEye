package com.badmon.shuttleeye.ui.live

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.badmon.shuttleeye.analysis.Output
import com.badmon.shuttleeye.analysis.VideoAnalyzer
import com.badmon.shuttleeye.camera.CameraService
import com.badmon.shuttleeye.core.Point2
import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.media.VideoFrameReader
import com.badmon.shuttleeye.ui.Theme
import com.badmon.shuttleeye.ui.calibration.CalibrationScreen
import com.badmon.shuttleeye.ui.result.ResultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 实时测速主界面（对应 iOS LiveAnalysisView，简化版：CameraX 帧流采集） */
@Composable
fun LiveAnalysisScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var phase by remember { mutableStateOf(Phase.IDLE) }
    var activeFps by remember { mutableStateOf(0) }
    var calibration by remember { mutableStateOf<ScaleCalibration?>(null) }
    var frozenImage by remember { mutableStateOf<Bitmap?>(null) }
    var showCalibration by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Output?>(null) }
    var statusText by remember { mutableStateOf("") }
    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val camera = remember(context, lifecycleOwner) {
        CameraService(context, lifecycleOwner) { fps -> activeFps = fps }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(hasPermission) {
        if (hasPermission) camera.start(previewView)
        onDispose { camera.release() }
    }

    fun startCapture() {
        val cal = calibration ?: return
        phase = Phase.RECORDING
        statusText = "录制中，请保持手机固定…"
        // 录制开始时抓一帧作轨迹背景
        camera.capturePreviewFrame { bmp -> backgroundBitmap = bmp }
        camera.recordClip(durationSec = 2.0) { samples ->
            phase = Phase.ANALYZING
            statusText = "分析中…"
            scope.launch(Dispatchers.Default) {
                val frames = samples.map {
                    VideoFrameReader.Frame(
                        image = it.gray,
                        time = it.timeSec,
                        downscale = it.downscale,
                        originalWidth = it.width,
                        originalHeight = it.height
                    )
                }
                val fps = if (frames.size >= 2) {
                    (frames.size - 1) / maxOf(0.001, frames.last().time - frames.first().time)
                } else 0.0
                val output = VideoAnalyzer.analyze(frames, fps, cal, backgroundBitmap)
                withContext(Dispatchers.Main) {
                    if (output != null) {
                        result = output
                        statusText = ""
                    } else {
                        phase = Phase.IDLE
                        statusText = "未捕捉到有效轨迹，请确保：球在画面内、机位固定、光线充足"
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 相机预览
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("需要相机权限才能进行实时测速", color = Theme.secondaryText)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予权限")
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // ---- 顶部栏 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (activeFps > 0) "$activeFps FPS" else "未连接",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        camera.capturePreviewFrame { bmp ->
                            frozenImage = bmp
                            showCalibration = true
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (calibration == null) Theme.accent.copy(alpha = 0.85f)
                        else Theme.good.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        if (calibration == null) "标定比例尺" else "已标定 ✓",
                        color = Color.Black,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- 底部控制 ----
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (statusText.isNotEmpty()) {
                    Text(
                        statusText,
                        fontSize = 12.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                val canStart = calibration != null && (phase == Phase.IDLE || phase == Phase.RESULT)
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(
                                if (canStart) Theme.accent else Color.Gray.copy(alpha = 0.5f),
                                CircleShape
                            )
                    )
                    when (phase) {
                        Phase.RECORDING -> CircularProgressIndicator(color = Color.White)
                        else -> TextButton(onClick = { startCapture() }, enabled = canStart) {
                            Text("捕捉", color = Color.White, fontSize = 16.sp)
                        }
                    }
                }

                if (calibration == null) {
                    Text("请先标定比例尺，再开始捕捉", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }

    // ---- 标定覆盖层 ----
    if (showCalibration && frozenImage != null) {
        CalibrationScreen(image = frozenImage!!) { cal ->
            showCalibration = false
            if (cal != null) calibration = cal
        }
    }

    // ---- 结果覆盖层 ----
    result?.let { output ->
        ResultScreen(
            output = output,
            calibration = calibration ?: ScaleCalibration(Point2(0.0, 0.0), Point2(1.0, 0.0), 1.0),
            source = "实时测量",
            onClose = {
                result = null
                phase = Phase.IDLE
            }
        )
    }
}

private enum class Phase { IDLE, RECORDING, ANALYZING, RESULT }
