package com.badmon.shuttleeye.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmon.shuttleeye.ui.Theme
import com.badmon.shuttleeye.ui.card

/** 原理与使用说明（对应 iOS AboutView） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("原理说明") }) },
        containerColor = Theme.screen
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("鹰眼系统原理") {
                Text(
                    "本系统基于开源项目 TrackNetV3（羽毛球追踪行业标准，准确率 97.51%）" +
                            "与移动端测速研究（arXiv:2509.05334）构建：\n\n" +
                            "1. 检测：经典 CV 帧差检测（自适应阈值 + 亚像素质心；TrackNet 模型可自行扩展接入）；\n" +
                            "2. 跟踪：卡尔曼滤波（恒速模型）+ 复合评分（0.3·检测置信度 + 0.7·预测邻近度）" +
                            "+ 启发式速度边界（5~375 km/h）；\n" +
                            "3. 轨迹修复：中值离群剔除 + Catmull-Rom 样条填补短时丢失；\n" +
                            "4. 测速：v = (Δ像素/Δt) × 比例尺 × 3.6，含运动模糊前缘补偿与误差量化；\n" +
                            "5. 物理验证：拟合空气阻力模型 dv/dt = -k·v²，校验测速自洽性，" +
                            "并推算雷达枪等效速度（随距离衰减 v(x) = v₀·e^(-k·x)）。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }

            SectionCard("拍摄要求（决定精度上限）") {
                Bullet("相机垂直于球的飞行平面（侧机位），减少深度透视误差")
                Bullet("使用 240fps（其次 120fps）；帧率越高测速误差越小")
                Bullet("三脚架固定，避免机位抖动污染帧差")
                Bullet("标定线段须与击球点等距、与成像平面平行")
                Bullet("光线充足、背景简洁，避免白色广告牌等干扰")
            }

            SectionCard("速度验证说明") {
                Text(
                    "为什么本系统峰值速度显著高于雷达枪？\n\n" +
                            "羽毛球空气阻力极大（击球瞬间减速度可达 ~200g），球速沿飞行方向快速衰减。" +
                            "本系统测得的是击球瞬间的真实峰值，而雷达枪需要球飞行一段距离才能锁定，" +
                            "读到的是已衰减的速度。App 内的「雷达等效速度」即按阻力模型推算出的 " +
                            "4m 距离处读数，可与雷达枪直接对比。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }

            SectionCard("开源参考") {
                Link("TrackNetV3 (qaz812345)", "https://github.com/qaz812345/TrackNetV3", uriHandler)
                Link("TrackNetV2 (NYCU)", "https://nol.cs.nctu.edu.tw:234/open-source/TrackNetv2/", uriHandler)
                Link("Badminton_Challenge (IJCAI 金奖)", "https://github.com/jean50621/Badminton_Challenge", uriHandler)
                Link("MonoTrack 单目3D轨迹重建", "https://arxiv.org/abs/2204.01899", uriHandler)
                Link("移动端杀球测速系统 (arXiv:2509.05334)", "https://arxiv.org/abs/2509.05334", uriHandler)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.card().fillMaxWidth()) {
        Text(title, fontSize = 16.sp)
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .background(Theme.accent, CircleShape)
        )
        Text(
            text,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun Link(text: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(url) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 13.sp, color = Theme.cyan)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Default.OpenInNew,
            contentDescription = null,
            tint = Theme.secondaryText,
            modifier = Modifier.size(14.dp)
        )
    }
}
