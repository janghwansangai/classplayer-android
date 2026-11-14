package com.classplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.classplayer.android.data.MediaEntry
import com.classplayer.android.data.formatDuration
import com.classplayer.android.data.isAudioFile
import androidx.compose.material.icons.filled.MusicNote
import com.classplayer.android.ui.theme.CoverColors
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Muted
import com.classplayer.android.ui.theme.Surface

/** 썸네일: 유튜브는 이미지 URL, 내 영상은 첫 프레임을 뽑아 보여 준다. */
@Composable
fun Thumb(entry: MediaEntry, modifier: Modifier = Modifier, radius: Dp = 10.dp) {
    val ctx = LocalContext.current
    val model = if (entry.isLink) entry.thumbnail else entry.source
    Box(modifier.clip(RoundedCornerShape(radius)).background(CoverColors[entry.source.hashCode().mod(CoverColors.size)]), contentAlignment = Alignment.Center) {
        Icon(if (entry.isLink) Icons.Default.PlayArrow else if (entry.isAudioFile) Icons.Default.MusicNote else Icons.Default.VideoFile, null, tint = Ink.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
        if (model != null && !entry.isAudioFile) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(model).crossfade(true)
                    .apply { if (!entry.isLink) decoderFactory(VideoFrameDecoder.Factory()) }
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 목록 한 줄: 썸네일 + 제목 + 부가 정보 + 오른쪽 버튼. */
@Composable
fun EntryRow(
    entry: MediaEntry,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))
            .background(if (highlight) Surface else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(entry, Modifier.size(width = 72.dp, height = 48.dp), radius = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(subtitle ?: "${if (entry.isLink) "유튜브" else "내 영상"} · ${formatDuration(entry.durationSec)}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, label: String, size: Dp, bg: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun Pill(text: String, active: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (active) com.classplayer.android.ui.theme.Accent else Surface)
            .then(if (active) Modifier else Modifier.border(1.dp, com.classplayer.android.ui.theme.Line, RoundedCornerShape(20.dp)))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (active) com.classplayer.android.ui.theme.Bg else Ink, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 13.sp, maxLines = 1, softWrap = false, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, color = if (active) com.classplayer.android.ui.theme.Bg else Ink)
    }
}

@Composable
fun SmallIcon(icon: ImageVector, label: String, tint: Color = Muted, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = tint) }
}

val LinkIcon = Icons.Default.Link
