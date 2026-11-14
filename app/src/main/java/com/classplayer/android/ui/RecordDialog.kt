package com.classplayer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.classplayer.android.data.MediaEntry
import com.classplayer.android.data.formatDuration
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Muted
import kotlinx.coroutines.delay

/**
 * 녹화/녹음 설정 창. 영상 녹화 또는 소리만 녹음, 전체 또는 구간.
 * 창이 떠 있는 동안에도 영상은 계속 재생되므로 "지금" 버튼으로 시작·끝을 찍을 수 있다.
 */
@Composable
fun RecordDialog(
    entry: MediaEntry,
    player: Player?,
    onDismiss: () -> Unit,
    onStart: (audioOnly: Boolean, startMs: Long?, endMs: Long?) -> Unit,
) {
    var audioOnly by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(player?.currentPosition ?: 0L) }
    var dur by remember { mutableLongStateOf(player?.duration?.coerceAtLeast(0) ?: 0L) }
    var startMs by remember { mutableLongStateOf(pos) }
    var endMs by remember { mutableLongStateOf(if (dur > 0) minOf(pos + 60_000, dur) else pos + 60_000) }

    LaunchedEffect(player) {
        while (true) {
            player?.let { pos = it.currentPosition.coerceAtLeast(0); dur = it.duration.coerceAtLeast(0) }
            delay(300)
        }
    }
    fun clamp(v: Long) = if (dur > 0) v.coerceIn(0, dur) else v.coerceAtLeast(0)
    val valid = !section || endMs > startMs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("녹화 · 녹음") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.title, color = Muted, fontSize = 13.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("영상 녹화", !audioOnly, { audioOnly = false }, Icons.Default.Videocam)
                    Pill("소리만 녹음", audioOnly, { audioOnly = true }, Icons.Default.Mic)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("전체", !section, { section = false })
                    Pill("구간 설정", section, { section = true })
                }
                if (section) {
                    Text("지금 위치 ${formatDuration(pos / 1000)}", color = Ink, fontWeight = FontWeight.Bold)
                    TimeRow("시작", startMs, { startMs = clamp(pos) }, { startMs = clamp(startMs - 5000) }, { startMs = clamp(startMs + 5000) })
                    TimeRow("끝", endMs, { endMs = clamp(pos) }, { endMs = clamp(endMs - 5000) }, { endMs = clamp(endMs + 5000) })
                    Text(
                        if (valid) "길이 ${formatDuration((endMs - startMs) / 1000)}" else "끝이 시작보다 뒤여야 합니다",
                        color = if (valid) Muted else androidx.compose.ui.graphics.Color(0xFFF87171), fontSize = 13.sp,
                    )
                } else {
                    Text("길이 ${formatDuration(dur / 1000)} 전체를 저장합니다", color = Muted, fontSize = 13.sp)
                }
                Text(
                    "앱 다운로드 폴더에 저장되고, 라이브러리의 '저장됨'에서 인터넷 없이 재생할 수 있습니다." +
                        if (entry.isLink) " 유튜브는 내려받는 동안 인터넷이 필요합니다." else "",
                    color = Muted, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(audioOnly, if (section) startMs else null, if (section) endMs else null); onDismiss() }, enabled = valid) {
                Text("저장 시작")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun TimeRow(label: String, ms: Long, onNow: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit) {
    // 좁은 폰에서도 버튼이 줄바꿈되지 않게 시간 표시와 버튼을 두 줄로 나눈다
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label ${formatDuration(ms / 1000)}", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("지금 위치", true, onNow)
            Pill("−5초", false, onMinus)
            Pill("+5초", false, onPlus)
        }
    }
}
