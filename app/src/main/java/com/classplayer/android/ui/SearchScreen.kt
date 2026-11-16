package com.classplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classplayer.android.data.MediaEntry
import com.classplayer.android.data.Playlist
import com.classplayer.android.data.formatDuration
import com.classplayer.android.player.UiState
import com.classplayer.android.ui.theme.Accent
import com.classplayer.android.ui.theme.Bg
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Line
import com.classplayer.android.ui.theme.Muted
import com.classplayer.android.ui.theme.Surface

/** 유튜브 검색 결과(시안 B). 누르면 재생, 오른쪽 버튼으로 재생목록에 추가. */
@Composable
fun SearchScreen(
    state: UiState,
    playlists: List<Playlist>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onPlay: (Int) -> Unit,
    onAddToPlaylist: (String, MediaEntry) -> Unit,
    onCreatePlaylistWith: (String, MediaEntry) -> Unit,
) {
    var query by rememberSaveable(state.searchQuery) { mutableStateOf(state.searchQuery) }
    var adding by remember { mutableStateOf<MediaEntry?>(null) }
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Ink) }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("유튜브에서 검색", color = Muted, fontSize = 14.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Ink),
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch(query) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line, focusedContainerColor = Surface, unfocusedContainerColor = Surface),
                trailingIcon = { IconButton(onClick = { focus.clearFocus(); onSearch(query) }) { Icon(Icons.Default.Search, "검색", tint = Accent) } },
            )
        }

        when {
            state.searching -> Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("'${state.searchQuery}' 검색 중…", color = Muted, fontSize = 14.sp)
            }
            state.searchError != null -> Text(state.searchError, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
            else -> {
                Text("결과 ${state.searchResults.size}개 · 누르면 재생, 다음 곡 버튼으로 이어 듣기", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(state.searchResults, key = { _, e -> e.source }) { i, e ->
                        EntryRow(
                            e, onClick = { onPlay(i) },
                            subtitle = listOfNotNull(e.channel, formatDuration(e.durationSec).takeIf { e.durationSec > 0 }).joinToString(" · ").ifBlank { "유튜브" },
                        ) {
                            SmallIcon(Icons.Default.PlaylistAdd, "재생목록에 추가", tint = Accent) { adding = e }
                        }
                    }
                }
            }
        }
    }

    adding?.let { e ->
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("재생목록에 추가") },
            text = {
                // 재생목록이 많아도 아래 '새 재생목록' 칸이 밀려 사라지지 않게 스크롤한다
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.title, color = Muted, fontSize = 13.sp, maxLines = 2)
                    playlists.forEach { p ->
                        Text(
                            "${p.name} (${p.items.size}곡)", fontSize = 16.sp, color = Ink,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onAddToPlaylist(p.id, e); adding = null }.padding(12.dp),
                        )
                    }
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, placeholder = { Text("새 재생목록 이름") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { onCreatePlaylistWith(newName, e); adding = null } }) { Text("새로 만들어 추가") } },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("취소") } },
        )
    }
}
