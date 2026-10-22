package com.badmon.shuttleeye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.badmon.shuttleeye.ui.AppRoot
import com.badmon.shuttleeye.ui.ShuttleEyeTheme

/**
 * 羽眼 ShuttleEye —— 基于视觉的羽毛球测速 App（Android / Jetpack Compose）
 *
 * 四个标签页：实时测速（CameraX）/ 视频分析（相册抽帧）/ 历史 / 原理。
 * 核心算法与 iOS ShuttleEyeCore 语义一致（卡尔曼跟踪、轨迹修复、运动学测速、物理验证）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShuttleEyeTheme {
                AppRoot()
            }
        }
    }
}
