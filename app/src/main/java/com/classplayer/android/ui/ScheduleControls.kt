package com.classplayer.android.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classplayer.android.data.Playlist
import com.classplayer.android.schedule.Scheduler
import com.classplayer.android.ui.theme.Accent
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Muted
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "오늘 오후 3:20" / "내일 오전 9:00" */
fun formatWhen(at: Long): String {
    val today = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val day = if (c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "오늘" else "내일"
    return day + " " + SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(at))
}

/** 자동 종료 / 자동 시작 예약 조작부(설정 화면과 재생 화면 타이머 창에서 같이 쓴다). */
@Composable
fun ScheduleControls(
    schedule: Scheduler.State,
    playlists: List<Playlist>,
    showStart: Boolean,
    onStopIn: (Int) -> Unit,
    onStopAt: (Int, Int) -> Unit,
    onCancelStop: () -> Unit,
    onStartAt: (Int, Int, Playlist, Boolean) -> Unit,
    onCancelStart: () -> Unit,
    onNeedPlaylist: () -> Unit,
) {
    val ctx = LocalContext.current
    var daily by remember { mutableStateOf(schedule.startDaily) }
    var pickedTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val now = Calendar.getInstance()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Default.Bedtime, null, tint = Accent)
            Text("  자동 종료", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Text(if (schedule.stopAt > 0) "${formatWhen(schedule.stopAt)}에 소리를 줄이며 멈춥니다" else "예약 없음", color = Muted, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { m -> Pill("${m}분 후", false, { onStopIn(m) }) }
            Pill("시각 지정", false, {
                TimePickerDialog(ctx, { _, h, m -> onStopAt(h, m) }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            })
            if (schedule.stopAt > 0) Pill("취소", true, onCancelStop)
        }

        if (showStart) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Default.Alarm, null, tint = Accent)
                Text("  자동 시작", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(
                if (schedule.startAt > 0) "${formatWhen(schedule.startAt)}에 '${schedule.startPlaylistName}' 재생" + if (schedule.startDaily) " (매일)" else ""
                else "예약 없음 · 화면이 꺼져 있어도 시작됩니다",
                color = Muted, fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("시각과 재생목록 고르기", false, {
                    if (playlists.isEmpty()) onNeedPlaylist()
                    else TimePickerDialog(ctx, { _, h, m -> pickedTime = h to m }, 9, 0, false).show()
                })
                if (schedule.startAt > 0) Pill("취소", true, onCancelStart)
            }
            Row(Modifier.clickable { daily = !daily }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = daily, onCheckedChange = { daily = it })
                Text("매일 같은 시각에 반복", color = Ink, fontSize = 14.sp)
            }
        }
    }

    pickedTime?.let { (h, m) ->
        AlertDialog(
            onDismissRequest = { pickedTime = null },
            title = { Text("어떤 재생목록을 시작할까요?") },
            text = {
                Column {
                    playlists.forEach { p ->
                        Text(
                            "${p.name} (${p.items.size}곡)", color = Ink, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onStartAt(h, m, p, daily); pickedTime = null }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickedTime = null }) { Text("취소") } },
        )
    }
}
