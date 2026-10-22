package com.badmon.shuttleeye.ui.video

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmon.shuttleeye.analysis.Output
import com.badmon.shuttleeye.analysis.VideoAnalyzer
import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.media.VideoFrameReader
import com.badmon.shuttleeye.ui.Theme
import com.badmon.shuttleeye.ui.calibration.CalibrationScreen
import com.badmon.shuttleeye.ui.card
import com.badmon.shuttleeye.ui.result.ResultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 视频分析：从相册选择视频 → 抽帧 → 标定 → 分析（对应 iOS VideoAnalysisView） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAnalysisScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.PICK) }
    var progress by remember { mutableStateOf(0f) }
    var frames by remember { mutableStateOf<List<VideoFrameReader.Frame>>(emptyList()) }
    var videoFps by remember { mutableStateOf(0.0) }
    var firstFrame by remember { mutableStateOf<Bitmap?>(null) }
    var calibration by remember { mutableStateOf<ScaleCalibration?>(null) }
    var result by remember { mutableStateOf<Output?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reset() {
        frames = emptyList()
        firstFrame = null
        calibration = null
        phase = Phase.PICK
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            errorMessage = null
            phase = Phase.READING
            progress = 0f
            scope.launch(Dispatchers.IO) {
                try {
                    val collected = ArrayList<VideoFrameReader.Frame>()
                    var bg: Bitmap? = null
                    val (fps, count) = VideoFrameReader.read(
                        context = context,
                        uri = uri,
                        onFrame = { collected.add(it) },
                        onFirstFrame = { bg = it },
                        onProgress = { p -> withContext(Dispatchers.Main) { progress = p.toFloat() } }
                    )
                    withContext(Dispatchers.Main) {
                        videoFps = fps
                        frames = collected
                        firstFrame = bg
                        if (count >= 6) {
                            phase = Phase.CALIBRATING
                        } else {
                            errorMessage = "有效帧不足，请更换视频"
                            phase = Phase.PICK
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "视频读取失败：${e.message}"
                        phase = Phase.PICK
                    }
                }
            }
        }
    }

    fun runAnalysis() {
        phase = Phase.ANALYZING
        val cal = calibration
        val bg = firstFrame
        val fs = frames
        scope.launch(Dispatchers.Default) {
            val output = if (cal != null) VideoAnalyzer.analyze(fs, videoFps, cal, bg) else null
            withContext(Dispatchers.Main) {
                if (output != null) {
                    result = output
                    phase = Phase.DONE
                } else {
                    errorMessage = "未在视频中捕捉到有效轨迹（检查标定与画面）"
                    phase = Phase.PICK
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("视频分析") }) },
        containerColor = Theme.screen
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when (phase) {
                Phase.PICK -> {
                    Box(
                        modifier = Modifier
                            .card()
                            .fillMaxWidth()
                            .height(220.dp)
                            .clickable {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Theme.accent,
                                modifier = Modifier.size(44.dp)
                            )
                            Text("选择包含击球的视频", fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                            Text(
                                "建议：侧面垂直机位、120/240fps 慢动作、三脚架固定",
                                fontSize = 12.sp,
                                color = Theme.secondaryText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Phase.READING -> {
                    Column(modifier = Modifier.card().fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Theme.accent
                        )
                        Text(
                            "正在读取视频帧… ${(progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = Theme.secondaryText,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                Phase.CALIBRATING -> Text("请在首帧上标定比例尺", color = Theme.secondaryText)

                Phase.ANALYZING -> {
                    Row(
                        modifier = Modifier.card().fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = Theme.accent, modifier = Modifier.size(20.dp))
                        Text("鹰眼分析中…")
                    }
                }

                Phase.DONE -> Text("完成", color = Theme.secondaryText)
            }

            errorMessage?.let {
                Text(it, fontSize = 12.sp, color = Theme.bad)
            }
        }
    }

    // ---- 标定覆盖层 ----
    if (phase == Phase.CALIBRATING && firstFrame != null) {
        CalibrationScreen(image = firstFrame!!) { cal ->
            if (cal != null) {
                calibration = cal
                runAnalysis()
            } else {
                phase = Phase.PICK
            }
        }
    }

    // ---- 结果覆盖层 ----
    result?.let { output ->
        ResultScreen(
            output = output,
            calibration = calibration ?: ScaleCalibration(
                com.badmon.shuttleeye.core.Point2(0.0, 0.0),
                com.badmon.shuttleeye.core.Point2(1.0, 0.0),
                1.0
            ),
            source = "视频分析",
            onClose = {
                result = null
                reset()
            }
        )
    }
}

private enum class Phase { PICK, READING, CALIBRATING, ANALYZING, DONE }
