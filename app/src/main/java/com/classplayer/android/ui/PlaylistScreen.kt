package com.classplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.classplayer.android.data.Playlist
import com.classplayer.android.data.formatDuration
import com.classplayer.android.ui.theme.Accent
import com.classplayer.android.ui.theme.Bg
import com.classplayer.android.ui.theme.CoverColors
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Line
import com.classplayer.android.ui.theme.Muted
import com.classplayer.android.ui.theme.Surface

/** 재생목록 편집(시안 B): 이름, 링크/내 영상 추가, 길게 눌러 순서 바꾸기, 삭제, 재생. */
@Composable
fun PlaylistScreen(
    playlist: Playlist,
    repeatMode: Int,
    shuffle: Boolean,
    ytReady: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onAddLink: (String) -> Unit,
    onAddFile: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onPlayFrom: (Int) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable(playlist.id) { mutableStateOf(playlist.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val rowPx = with(LocalDensity.current) { 72.dp.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onRename(name); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Ink) }
            Text("재생목록 편집", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Pill("완료", true, { onRename(name); onBack() })
        }

        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(CoverColors[playlist.colorIndex % CoverColors.size])) {
                playlist.items.firstOrNull()?.let { Thumb(it, Modifier.fillMaxSize(), radius = 16.dp) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("이름") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line),
                )
                Text("${playlist.items.size}곡 · ${formatDuration(playlist.durationSec)}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("유튜브 링크 또는 재생목록 링크", fontSize = 13.sp, color = Muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line, focusedContainerColor = Surface, unfocusedContainerColor = Surface),
                trailingIcon = { TextButton(onClick = { clipboard.getText()?.text?.let { link = it } }) { Text("붙여넣기", fontSize = 12.sp, color = Accent) } },
            )
            Pill("추가", true, { if (link.isNotBlank()) { onAddLink(link); link = "" } })
            RoundIconButton(Icons.Default.FolderOpen, "내 영상에서 추가", 48.dp, Surface, Ink, onAddFile)
        }
        if (!ytReady) Text("유튜브 재생목록 링크는 엔진 준비 후 가져올 수 있어요.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

        Text("길게 눌러 끌면 순서가 바뀝니다", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            itemsIndexed(playlist.items, key = { _, e -> e.id }) { i, e ->
                val lifted = i == dragIndex
                Row(
                    Modifier.fillMaxWidth().height(72.dp).background(if (lifted) Surface else Bg)
                        .pointerInput(playlist.id, i) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragIndex = i; dragOffset = 0f },
                                onDragEnd = { dragIndex = -1; dragOffset = 0f },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    if (dragOffset > rowPx && dragIndex < playlist.items.lastIndex) { onMove(dragIndex, dragIndex + 1); dragIndex += 1; dragOffset -= rowPx }
                                    else if (dragOffset < -rowPx && dragIndex > 0) { onMove(dragIndex, dragIndex - 1); dragIndex -= 1; dragOffset += rowPx }
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DragHandle, null, tint = Muted, modifier = Modifier.width(32.dp))
                    EntryRow(e, Modifier.weight(1f), onClick = { onPlayFrom(i) }, subtitle = "${i + 1}. ${if (e.isLink) "유튜브" else "내 영상"} · ${formatDuration(e.durationSec)}") {
                        SmallIcon(Icons.Default.Close, "삭제") { onRemove(e.id) }
                    }
                }
            }
        }

        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(when (repeatMode) { Player.REPEAT_MODE_ONE -> "반복: 한 곡"; Player.REPEAT_MODE_ALL -> "반복: 전체"; else -> "반복: 끔" }, repeatMode != Player.REPEAT_MODE_OFF, onCycleRepeat, Icons.Default.Repeat)
                Pill(if (shuffle) "섞기: 켬" else "섞기: 끔", shuffle, onToggleShuffle, Icons.Default.Shuffle)
                Spacer(Modifier.weight(1f))
                Pill("삭제", false, { confirmDelete = true })
            }
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Ink).clickable { onRename(name); onPlayFrom(0) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Bg); Spacer(Modifier.width(6.dp))
                Text("처음부터 재생", color = Bg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("재생목록 삭제") },
            text = { Text("'${playlist.name}' 재생목록을 지울까요? 영상 자체는 지워지지 않습니다.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}
