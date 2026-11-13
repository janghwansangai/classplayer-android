package com.classplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classplayer.android.player.RecentItem
import com.classplayer.android.player.UiState
import com.classplayer.android.ui.theme.Blue
import com.classplayer.android.ui.theme.Line
import com.classplayer.android.ui.theme.Muted
import com.classplayer.android.ui.theme.Panel

@Composable
fun HomeScreen(
    state: UiState,
    onPlayText: (String) -> Unit,
    onPickFile: () -> Unit,
    onOpenRecent: (RecentItem) -> Unit,
    onRemoveRecent: (RecentItem) -> Unit,
    onUpdateYt: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("클래스플레이어", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("광고 없이, 영상만 보여 주는 수업용 플레이어", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("유튜브 링크를 붙여넣으세요") },
            leadingIcon = { Icon(Icons.Default.Link, null, tint = Muted) },
            trailingIcon = {
                IconButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                    Icon(Icons.Default.ContentPaste, "붙여넣기", tint = Blue)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Line),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(text = "재생", icon = { Icon(Icons.Default.PlayArrow, null) }, modifier = Modifier.weight(1f)) { onPlayText(text) }
            BigButton(text = "내 영상 열기", icon = { Icon(Icons.Default.FolderOpen, null) }, modifier = Modifier.weight(1f), tonal = true) { onPickFile() }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.ytReady) "유튜브 엔진 준비됨" + (state.ytVersion?.let { " (yt-dlp $it)" } ?: "") else "유튜브 엔진 준비 중…",
                color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUpdateYt, enabled = state.ytReady) { Text("엔진 업데이트", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(16.dp))

        Text("최근 재생", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        if (state.recents.isEmpty()) {
            Text("아직 재생한 영상이 없습니다.\n유튜브 앱에서 공유 → 클래스플레이어를 눌러도 됩니다.", color = Muted, fontSize = 14.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recents, key = { it.source }) { item ->
                    RecentRow(item, onClick = { onOpenRecent(item) }, onRemove = { onRemoveRecent(item) })
                }
            }
        }
    }
}

@Composable
fun BigButton(text: String, icon: @Composable () -> Unit, modifier: Modifier = Modifier, tonal: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = if (tonal) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        else ButtonDefaults.buttonColors(containerColor = Blue, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        icon(); Spacer(Modifier.width(6.dp)); Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecentRow(item: RecentItem, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .background(Panel, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (item.isLink) Icons.Default.Link else Icons.Default.VideoFile, null, tint = Blue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            Text(if (item.isLink) item.source else "내 기기 영상", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "삭제", tint = Muted) }
    }
}
