package com.badmon.shuttleeye.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 全局主题：颜色与卡片样式（对应 iOS ContentView 中的 Theme） */
object Theme {
    val accent = Color(1.00f, 0.58f, 0.11f)   // 荧光球橙
    val good = Color(0xFF30D158)
    val warn = Color(0xFFFFD60A)
    val bad = Color(0xFFFF453A)
    val card = Color.White.copy(alpha = 0.06f)
    val screen = Color.Black
    val cyan = Color(0xFF64D2FF)
    val secondaryText = Color(0xFFB0B4BB)
}

@Composable
fun ShuttleEyeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Theme.accent,
            onPrimary = Color.Black,
            secondary = Theme.accent,
            onSecondary = Color.Black,
            tertiary = Theme.cyan,
            background = Theme.screen,
            onBackground = Color.White,
            surface = Color(0xFF121212),
            onSurface = Color.White,
            surfaceVariant = Theme.card,
            onSurfaceVariant = Theme.secondaryText,
            error = Theme.bad
        ),
        content = content
    )
}

/** 卡片容器样式 */
fun Modifier.card(): Modifier =
    this.background(Theme.card, RoundedCornerShape(16.dp)).padding(16.dp)
