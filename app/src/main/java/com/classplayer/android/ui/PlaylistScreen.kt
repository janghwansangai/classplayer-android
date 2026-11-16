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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
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
import com.classplayer.android.data.MediaEntry
import com.classplayer.android.data.Playlist
import com.classplayer.android.offline.Offline
import com.classplayer.android.youtube.StreamResolver
import com.classplayer.android.youtube.YoutubeMeta
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
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
    offline: PlaylistOffline = PlaylistOffline(),
    onSetAutoDownload: (on: Boolean, deleteFiles: Boolean) -> Unit = { _, _ -> },
    onLinkPlaylist: (String) -> Unit = {},
    onUnlink: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onRetryOffline: () -> Unit = {},
) {
    var link by rememberSaveable { mutableStateOf("") }
    var askLink by remember { mutableStateOf<String?>(null) } // 유튜브 재생목록 링크: 계속 연결할지 한 번만 넣을지
    var askOff by remember { mutableStateOf(false) }         // 미리 받기 끌 때 받은 파일을 지울지
    var name by rememberSaveable(playlist.id) { mutableStateOf(playlist.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val rowPx = with(LocalDensity.current) { 72.dp.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 화면 왼쪽 위 뒤로 버튼뿐 아니라 시스템 뒤로 가기(제스처)로 나가도 바꾼 이름을 저장한다
    BackHandler { onRename(name); onBack() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Pill("추가", true, {
                if (link.isNotBlank()) {
                    if (isYoutubePlaylistLink(link)) askLink = link else onAddLink(link)
                    link = ""
                }
            })
            RoundIconButton(Icons.Default.FolderOpen, "내 영상에서 추가", 48.dp, Surface, Ink, onAddFile)
        }
        if (!ytReady) Text("유튜브 재생목록 링크는 엔진 준비 후 가져올 수 있어요.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

        OfflineCard(
            playlist, offline,
            onToggle = { on ->
                val have = playlist.items.count { e -> Offline.idOf(e.source)?.let { it in offline.downloaded } == true }
                if (on || have == 0) onSetAutoDownload(on, false) else askOff = true
            },
            onSyncNow = onSyncNow, onUnlink = onUnlink, onRetry = onRetryOffline,
        )

        Text("길게 눌러 끌면 순서가 바뀝니다", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            itemsIndexed(playlist.items, key = { _, e -> e.id }) { i, e ->
                val lifted = i == dragIndex
                val index by rememberUpdatedState(i)
                val lastIndex by rememberUpdatedState(playlist.items.lastIndex)
                Row(
                    Modifier.fillMaxWidth().height(72.dp).background(if (lifted) Surface else Bg)
                        // 키를 순서(i)가 아닌 항목(id)으로 둬야, 끄는 도중 한 칸 옮겨져도 끌기가 끊기지 않는다
                        .pointerInput(playlist.id, e.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragIndex = index; dragOffset = 0f },
                                onDragEnd = { dragIndex = -1; dragOffset = 0f },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    if (dragOffset > rowPx && dragIndex < lastIndex) { onMove(dragIndex, dragIndex + 1); dragIndex += 1; dragOffset -= rowPx }
                                    else if (dragOffset < -rowPx && dragIndex > 0) { onMove(dragIndex, dragIndex - 1); dragIndex -= 1; dragOffset += rowPx }
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DragHandle, null, tint = Muted, modifier = Modifier.width(32.dp))
                    EntryRow(e, Modifier.weight(1f), onClick = { onPlayFrom(i) }, subtitle = "${i + 1}. ${if (e.isLink) "유튜브" else "내 영상"} · ${formatDuration(e.durationSec)}" + (offlineStatus(e, playlist, offline)?.let { " · $it" } ?: "")) {
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

    askLink?.let { url ->
        AlertDialog(
            onDismissRequest = { askLink = null; link = url },
            title = { Text("유튜브 재생목록") },
            text = {
                Text(
                    "계속 연결하면 유튜브에서 이 재생목록에 새로 저장한 영상이 여기에 자동으로 추가됩니다(앱을 열 때와 6시간마다 확인).\n\n" +
                        "유튜브 재생목록은 '공개'나 '일부 공개'여야 합니다. 좋아요 목록은 로그인이 필요해 연결할 수 없어요.",
                )
            },
            confirmButton = { TextButton(onClick = { askLink = null; onLinkPlaylist(url) }) { Text("계속 연결") } },
            dismissButton = { TextButton(onClick = { askLink = null; onAddLink(url) }) { Text("한 번만 추가") } },
        )
    }
    if (askOff) {
        AlertDialog(
            onDismissRequest = { askOff = false },
            title = { Text("미리 받기 끄기") },
            text = { Text("이미 받은 영상 파일은 어떻게 할까요? 남겨 두면 인터넷 없이 계속 바로 재생됩니다.") },
            confirmButton = { TextButton(onClick = { askOff = false; onSetAutoDownload(false, false) }) { Text("남겨 두기") } },
            dismissButton = { TextButton(onClick = { askOff = false; onSetAutoDownload(false, true) }) { Text("파일 지우기") } },
        )
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

/** 재생목록 화면에 보일 미리 받기 상태. */
data class PlaylistOffline(
    val downloaded: Set<String> = emptySet(),
    val progress: Offline.Progress? = null,
    val failed: Map<String, Offline.Failure> = emptyMap(),
    val notice: String? = null,
    val wifiOnly: Boolean = true,
)

private val Warn = Color(0xFFF87171)

private fun isYoutubePlaylistLink(text: String): Boolean {
    val url = StreamResolver.extractUrl(text) ?: return false
    return StreamResolver.isYoutubeUrl(url) && YoutubeMeta.playlistId(url) != null && YoutubeMeta.videoId(url) == null
}

/** 항목 옆에 붙일 받기 상태(유튜브가 아니거나 해당 없으면 null). */
private fun offlineStatus(e: MediaEntry, p: Playlist, o: PlaylistOffline): String? {
    val id = Offline.idOf(e.source) ?: return null
    return when {
        id in o.downloaded -> "받음"
        o.progress?.videoId == id -> "받는 중 ${(o.progress.percent * 100).toInt()}%"
        o.failed[id]?.skip == true -> "받을 수 없음"
        p.autoDownload -> "받기 대기"
        else -> null
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 24 * 60 -> "${m / 60}시간 전"
        else -> "${m / (24 * 60)}일 전"
    }
}

@Composable
private fun OfflineCard(
    playlist: Playlist, o: PlaylistOffline,
    onToggle: (Boolean) -> Unit, onSyncNow: () -> Unit, onUnlink: () -> Unit, onRetry: () -> Unit,
) {
    val ids = playlist.items.mapNotNull { Offline.idOf(it.source) }.distinct()
    val have = ids.count { it in o.downloaded }
    val failed = ids.count { o.failed[it]?.skip == true }
    val current = o.progress?.takeIf { it.videoId in ids }
    val summary = when {
        ids.isEmpty() -> "유튜브 영상을 받아 두면 인터넷 없이 바로 재생됩니다"
        current != null -> "받는 중 ${(current.percent * 100).toInt()}% · ${ids.size}개 중 ${have}개 받음"
        playlist.autoDownload && have + failed < ids.size ->
            "${ids.size}개 중 ${have}개 받음 · " + if (o.wifiOnly) "와이파이에서 이어 받음" else "이어 받음"
        playlist.autoDownload -> "${ids.size}개 중 ${have}개 받음 · 인터넷 없이 재생됩니다"
        have > 0 -> "${ids.size}개 중 ${have}개 받아 둠 · 켜면 나머지도 받습니다"
        else -> "켜면 유튜브 영상을 차례로 받아 두어 인터넷 없이 바로 재생합니다"
    }
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DownloadForOffline, null, tint = if (playlist.autoDownload) Accent else Muted)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("미리 받기", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(summary, color = Muted, fontSize = 12.sp)
            }
            Switch(
                checked = playlist.autoDownload, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Bg),
            )
        }
        o.notice?.takeIf { playlist.autoDownload }?.let { Text(it, color = Warn, fontSize = 12.sp) }
        if (failed > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("받을 수 없는 영상 ${failed}개(삭제·비공개 등)", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("다시 시도", color = Accent, fontSize = 12.sp) }
            }
        }
        playlist.syncUrl?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "유튜브 재생목록 연결됨" + if (playlist.syncedAt > 0) " · ${ago(playlist.syncedAt)} 확인" else "",
                    color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSyncNow) { Text("지금 확인", color = Accent, fontSize = 12.sp) }
                TextButton(onClick = onUnlink) { Text("끊기", color = Muted, fontSize = 12.sp) }
            }
            playlist.syncError?.let { Text(it, color = Warn, fontSize = 12.sp) }
        }
    }
}
