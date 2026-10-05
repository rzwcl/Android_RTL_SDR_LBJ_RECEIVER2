package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.weight
import androidx.compose.ui.unit.sp
import com.example.data.TrainRecord
import com.example.data.TrainSignalRecord
import com.example.ui.theme.BlueUp
import com.example.ui.theme.BlueUpSoft
import com.example.ui.theme.BorderLight
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.PrimaryBlueDark
import com.example.ui.theme.PurpleTech
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceSecondary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun HistoryDetailScreen(
    record: TrainRecord,
    signals: List<TrainSignalRecord>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val durationSeconds = max(0L, (record.lastSeenTime - record.firstSeenTime) / 1000L)
    val durationText = if (durationSeconds >= 60L) {
        (durationSeconds / 60L).toString() + "分" + (durationSeconds % 60L) + "秒"
    } else {
        durationSeconds.toString() + "秒"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回历史列表",
                    tint = TextPrimary
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "列车详情 · " + record.trainNo,
                    color = PrimaryBlueDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "共 " + signals.size + " 条 LBJ 信号记录",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        HistorySummaryCard(
            record = record,
            durationText = durationText,
            firstSeenText = timeFormat.format(Date(record.firstSeenTime)),
            lastSeenText = timeFormat.format(Date(record.lastSeenTime))
        )

        Spacer(modifier = Modifier.height(12.dp))

        val hasCoordinates = signals.any {
            it.longitude.isNotBlank() && it.latitude.isNotBlank()
        }
        if (hasCoordinates) {
            HistoryTrackMap(
                signals = signals,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (signals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "该历史记录暂无逐条 LBJ 信号",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            Text(
                text = "逐条信号",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = signals,
                    key = { it.id }
                ) { signal ->
                    SignalRecordCard(
                        signal = signal,
                        timeText = timeFormat.format(Date(signal.timestamp))
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(
    record: TrainRecord,
    durationText: String,
    firstSeenText: String,
    lastSeenText: String
) {
    val (dirBg, dirFg) = when (record.direction) {
        "下行" -> EmeraldSoft to EmeraldGreen
        "上行" -> BlueUpSoft to BlueUp
        else -> SurfaceSecondary to TextMuted
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderLight, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.trainNo,
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .background(dirBg, RoundedCornerShape(4.dp))
                        .border(0.5.dp, dirFg.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(record.direction, color = dirFg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = record.category,
                    color = PrimaryBlueDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            DetailLine(
                icon = Icons.Default.Train,
                label = "机车",
                value = buildLocomotiveText(record)
            )
            DetailLine(
                icon = Icons.Default.Route,
                label = "线路",
                value = record.route
            )
            DetailLine(
                icon = Icons.Default.AccessTime,
                label = "首次收到",
                value = firstSeenText
            )
            DetailLine(
                icon = Icons.Default.AccessTime,
                label = "最后收到",
                value = lastSeenText
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSecondary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "持续 " + durationText,
                    color = PrimaryBlueDark,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "信号 " + record.trainNo + " → " + record.direction,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun DetailLine(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PurpleTech,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.size(7.dp))
        Text(
            text = label + ": ",
            color = TextMuted,
            fontSize = 12.sp
        )
        Text(
            text = value.ifBlank { "未知" },
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun buildLocomotiveText(record: TrainRecord): String {
    return if (record.locoCode.isNotBlank() && record.locoCode != "---") {
        record.locoModel + " (" + record.locoCode + ")"
    } else {
        record.locoModel
    }
}

@Composable
private fun SignalRecordCard(
    signal: TrainSignalRecord,
    timeText: String
) {
    val coordinates = listOf(signal.longitude.trim(), signal.latitude.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderLight, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = timeText,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = EmeraldGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = if (signal.speed.isBlank() || signal.speed == "---") "未知" else signal.speed,
                        color = EmeraldGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " km/h",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = signal.trainNo + " · " + signal.direction,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = signal.category,
                    color = PrimaryBlueDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "机车: " + signal.locoModel.ifBlank { "未知" } + " · " + signal.locoCode.ifBlank { "未知" },
                color = TextSecondary,
                fontSize = 11.sp
            )
            Text(
                text = "线路: " + signal.route.ifBlank { "未知" } + "    公里标: " + signal.positionKm.ifBlank { "未解析" },
                color = TextSecondary,
                fontSize = 11.sp
            )

            if (coordinates.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = PrimaryBlueDark,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = coordinates,
                        color = PrimaryBlueDark,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}