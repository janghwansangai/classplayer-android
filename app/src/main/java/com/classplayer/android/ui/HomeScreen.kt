package com.classplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classplayer.android.data.MediaEntry
import com.classplayer.android.data.Playlist
import com.classplayer.android.data.formatDuration
import com.classplayer.android.player.UiState
import com.classplayer.android.ui.theme.Accent
import com.classplayer.android.ui.theme.Bg
import com.classplayer.android.ui.theme.CoverColors
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Line
import com.classplayer.android.ui.theme.Muted
import com.classplayer.android.ui.theme.Surface

@Composable
fun HomeScreen(
    state: UiState,
    playlists: List<Playlist>,
    favorites: List<MediaEntry>,
    recents: List<MediaEntry>,
    isPlaying: Boolean,
    onTab: (UiState.HomeTab) -> Unit,
    onPlayText: (String) -> Unit,
    onPickFile: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onPlayEntry: (MediaEntry) -> Unit,
    onToggleFavorite: (MediaEntry) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onUpdateYt: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("라이브러리", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            IconButton(onClick = { showSettings = true }, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Surface)) { Icon(Icons.Default.Settings, "설정", tint = Ink) }
        }

        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("재생목록", state.homeTab == UiState.HomeTab.Playlists, { onTab(UiState.HomeTab.Playlists) })
            Pill("즐겨찾기", state.homeTab == UiState.HomeTab.Favorites, { onTab(UiState.HomeTab.Favorites) })
            Pill("최근", state.homeTab == UiState.HomeTab.Recents, { onTab(UiState.HomeTab.Recents) })
        }

        Box(Modifier.weight(1f)) {
            when (state.homeTab) {
                UiState.HomeTab.Playlists -> PlaylistsTab(playlists, favorites, onOpenPlaylist, onPlayPlaylist, { newName = "" }, onPlayEntry, onToggleFavorite)
                UiState.HomeTab.Favorites -> EntryList(favorites, "하트를 누른 영상이 여기에 모입니다.", onPlayEntry) { e ->
                    SmallIcon(Icons.Default.Favorite, "즐겨찾기 해제", tint = Accent) { onToggleFavorite(e) }
                }
                UiState.HomeTab.Recents -> EntryList(recents, "아직 재생한 영상이 없습니다.", onPlayEntry) { e ->
                    SmallIcon(Icons.Default.PlayArrow, "재생", tint = Ink) { onPlayEntry(e) }
                }
            }
        }

        // 링크 입력 + 내 영상 (엄지 영역)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f).height(52.dp),
                placeholder = { Text("유튜브 링크 붙여넣기", fontSize = 14.sp, color = Muted) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line, focusedContainerColor = Surface, unfocusedContainerColor = Surface),
                trailingIcon = { IconButton(onClick = { clipboard.getText()?.text?.let { text = it } }) { Icon(Icons.Default.ContentPaste, "붙여넣기", tint = Accent) } },
            )
            RoundIconButton(Icons.Default.PlayArrow, "재생", 52.dp, Accent, Bg) { onPlayText(text) }
            RoundIconButton(Icons.Default.FolderOpen, "내 영상 열기", 52.dp, Surface, Ink, onPickFile)
        }

        state.current?.let { cur ->
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(64.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Surface).border(1.dp, Line, RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenPlayer).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(cur, Modifier.size(44.dp), radius = 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Text("${state.queueName} · ${state.queueIndex + 1}/${state.queue.size}" + if (isPlaying) " · 재생 중" else "", fontSize = 11.sp, color = Muted)
                }
                IconButton(onClick = onTogglePlay) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "재생/일시정지", tint = Ink) }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "다음 곡", tint = Ink) }
            }
        } ?: Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().height(72.dp).background(Bg).padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
            NavItem(Icons.Default.LibraryMusic, "라이브러리", true) { onTab(UiState.HomeTab.Playlists) }
            NavItem(Icons.Default.PlayCircle, "지금 재생", false, onOpenPlayer)
            NavItem(Icons.Default.Settings, "설정", false) { showSettings = true }
        }
    }

    newName?.let { name ->
        AlertDialog(
            onDismissRequest = { newName = null },
            title = { Text("새 재생목록") },
            text = { OutlinedTextField(value = name, onValueChange = { newName = it }, placeholder = { Text("이름 (예: 아침 활동 동요)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onCreatePlaylist(name); newName = null }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { newName = null }) { Text("취소") } },
        )
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.ytReady) "유튜브 엔진 준비됨" + (state.ytVersion?.let { " (yt-dlp $it)" } ?: "") else "유튜브 엔진 준비 중…", color = Muted, fontSize = 13.sp)
                    Text("유튜브가 안 열리면 엔진을 업데이트해 보세요. 하루 한 번은 자동으로 합니다.", fontSize = 13.sp)
                    Text("화면이 꺼지거나 다른 앱으로 가도 소리는 계속 재생됩니다. 알림에서 멈출 수 있습니다.", fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { onUpdateYt(); showSettings = false }, enabled = state.ytReady) { Text("엔진 업데이트") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("닫기") } },
        )
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (active) Accent else Muted)
        Text(label, fontSize = 11.sp, color = if (active) Accent else Muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>, favorites: List<MediaEntry>,
    onOpen: (Playlist) -> Unit, onPlay: (Playlist) -> Unit, onNew: () -> Unit,
    onPlayEntry: (MediaEntry) -> Unit, onToggleFavorite: (MediaEntry) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playlists, key = { it.id }) { p -> PlaylistCard(p, { onOpen(p) }, { onPlay(p) }) }
                item {
                    Column(Modifier.width(150.dp).clickable(onClick = onNew), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(150.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, Line, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, "새 재생목록", tint = Muted, modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("새로 만들기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Muted)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("즐겨찾기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                Text("꾹 누르면 재생목록에 추가", fontSize = 12.sp, color = Muted)
            }
        }
        if (favorites.isEmpty()) item { Text("재생 화면의 하트를 누르면 여기에 모입니다.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        items(favorites.take(8), key = { it.id }) { e ->
            EntryRow(e, Modifier.padding(horizontal = 14.dp), onClick = { onPlayEntry(e) }) {
                SmallIcon(Icons.Default.Favorite, "즐겨찾기 해제", tint = Accent) { onToggleFavorite(e) }
            }
        }
    }
}

@Composable
private fun PlaylistCard(p: Playlist, onOpen: () -> Unit, onPlay: () -> Unit) {
    Column(Modifier.width(150.dp).clickable(onClick = onOpen)) {
        Box(Modifier.size(150.dp).clip(RoundedCornerShape(16.dp)).background(CoverColors[p.colorIndex % CoverColors.size])) {
            p.items.firstOrNull()?.let { Thumb(it, Modifier.fillMaxSize(), radius = 16.dp) }
                ?: Icon(Icons.Default.MusicNote, null, tint = Ink.copy(alpha = 0.7f), modifier = Modifier.size(44.dp).align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp).clip(RoundedCornerShape(20.dp)).background(Ink).clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "재생", tint = Bg)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${p.items.size}곡 · ${formatDuration(p.durationSec)}", fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun EntryList(list: List<MediaEntry>, empty: String, onPlay: (MediaEntry) -> Unit, trailing: @Composable (MediaEntry) -> Unit) {
    if (list.isEmpty()) { Text(empty, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(20.dp)); return }
    LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(list, key = { it.id }) { e -> EntryRow(e, onClick = { onPlay(e) }) { trailing(e) } }
    }
}
