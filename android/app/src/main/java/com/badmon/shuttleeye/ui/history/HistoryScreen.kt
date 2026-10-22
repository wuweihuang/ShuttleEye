package com.badmon.shuttleeye.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmon.shuttleeye.model.SessionRecord
import com.badmon.shuttleeye.store.HistoryStore
import com.badmon.shuttleeye.ui.EmptyHint
import com.badmon.shuttleeye.ui.SpeedCurveCanvas
import com.badmon.shuttleeye.ui.StatColumn
import com.badmon.shuttleeye.ui.Theme
import com.badmon.shuttleeye.ui.card
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史记录（对应 iOS HistoryView） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val store = remember { HistoryStore(context) }
    var records by remember { mutableStateOf(store.load()) }
    var detail by remember { mutableStateOf<SessionRecord?>(null) }

    if (detail != null) {
        RecordDetail(record = detail!!, onBack = { detail = null })
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("历史记录") }) },
        containerColor = Theme.screen
    ) { pad ->
        if (records.isEmpty()) {
            EmptyHint("暂无历史记录", modifier = Modifier.padding(pad))
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records, key = { it.id }) { r ->
                    Row(
                        modifier = Modifier
                            .card()
                            .fillMaxWidth()
                            .clickable { detail = r },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("%.1f".format(r.peakKmh), fontSize = 20.sp, color = Theme.accent)
                                Text(
                                    " km/h",
                                    fontSize = 11.sp,
                                    color = Theme.secondaryText,
                                    modifier = Modifier.padding(start = 2.dp)
                                )
                                if (r.physicsConsistent) {
                                    Icon(
                                        Icons.Default.Verified,
                                        contentDescription = "物理验证通过",
                                        tint = Theme.good,
                                        modifier = Modifier.padding(start = 6.dp).size(14.dp)
                                    )
                                }
                            }
                            Text(
                                "${r.source} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(r.dateMillis))}",
                                fontSize = 11.sp,
                                color = Theme.secondaryText,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        IconButton(onClick = {
                            store.delete(r)
                            records = store.load()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Theme.secondaryText)
                        }
                    }
                }
            }
        }
    }
}

/** 记录详情 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDetail(record: SessionRecord, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记录详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.History, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.card().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.1f".format(record.peakKmh), fontSize = 56.sp, color = Theme.accent)
                Text(
                    "km/h  ±${"%.1f".format(record.uncertaintyKmh)}",
                    fontSize = 12.sp,
                    color = Theme.secondaryText
                )
            }

            Column(modifier = Modifier.card().fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatColumn("平滑峰值", "%.1f".format(record.smoothedPeakKmh))
                    StatColumn("雷达等效", record.radarEquivalentKmh?.let { "%.1f".format(it) } ?: "--")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    StatColumn("气动系数 k", record.dragCoeffK?.let { "%.3f /m".format(it) } ?: "--")
                    StatColumn("R²", record.r2?.let { "%.3f".format(it) } ?: "--")
                }
            }

            if (record.speedCurve.size > 1) {
                Column(modifier = Modifier.card().fillMaxWidth()) {
                    Text("速度曲线", fontSize = 16.sp)
                    SpeedCurveCanvas(
                        segments = record.speedCurve.mapIndexed { i, row ->
                            com.badmon.shuttleeye.core.SegmentSpeed(
                                index = i,
                                t0 = row[0],
                                t1 = row[0],
                                speedKmh = row[1],
                                uncertaintyKmh = 0.0
                            )
                        },
                        peakIndex = record.speedCurve.indices.maxBy { record.speedCurve[it][1] }
                    )
                }
            }

            Text(
                "标定：${"%.4f".format(record.calibration.scale)} m/px · ${"%.0f".format(record.fps)} fps",
                fontSize = 11.sp,
                color = Theme.secondaryText
            )
        }
    }
}
