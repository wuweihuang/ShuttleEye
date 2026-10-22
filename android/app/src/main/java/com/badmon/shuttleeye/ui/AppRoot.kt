package com.badmon.shuttleeye.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.badmon.shuttleeye.ui.about.AboutScreen
import com.badmon.shuttleeye.ui.history.HistoryScreen
import com.badmon.shuttleeye.ui.live.LiveAnalysisScreen
import com.badmon.shuttleeye.ui.video.VideoAnalysisScreen

/** 主导航（对应 iOS ContentView 的 TabView，深色主题） */
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.VIDEO) }

    Scaffold(
        containerColor = Theme.screen,
        bottomBar = {
            NavigationBar(containerColor = androidx.compose.ui.graphics.Color(0xFF161616)) {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad)) {
            when (tab) {
                Tab.LIVE -> LiveAnalysisScreen()
                Tab.VIDEO -> VideoAnalysisScreen()
                Tab.HISTORY -> HistoryScreen()
                Tab.ABOUT -> AboutScreen()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("实时测速", Icons.Default.Camera),
    VIDEO("视频分析", Icons.Default.PlayArrow),
    HISTORY("历史", Icons.Default.History),
    ABOUT("原理", Icons.Default.MenuBook)
}
