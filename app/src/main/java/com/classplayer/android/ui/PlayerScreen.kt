package com.classplayer.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.classplayer.android.player.UiState
import com.classplayer.android.ui.theme.Blue
import com.classplayer.android.vr.VrVideoView
import kotlinx.coroutines.delay

/** 재생 화면: 검은 바탕 위에 영상(일반 또는 360도), 탭하면 컨트롤이 나타난다. */
@Composable
fun PlayerScreen(
    state: UiState,
    player: Player?,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggle360: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    onVrSurface: (android.view.Surface?) -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 0.5초마다 재생 위치를 읽어 슬라이더에 반영한다
    LaunchedEffect(player) {
        while (true) {
            player?.let {
                if (!dragging) position = it.currentPosition.coerceAtLeast(0)
                duration = it.duration.coerceAtLeast(0)
                isPlaying = it.isPlaying
            }
            delay(500)
        }
    }
    // 재생 중이면 몇 초 뒤 컨트롤을 자동으로 숨긴다
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) { delay(4000); controlsVisible = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.audioOnly) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Headphones, null, tint = Color.White, modifier = Modifier.size(72.dp))
                Spacer(Modifier.size(12.dp))
                Text("소리만 재생 중", color = Color.White, fontSize = 18.sp)
            }
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controlsVisible = !controlsVisible })
        } else if (state.is360) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> VrVideoView(ctx, onVrSurface).also { it.onTap = { controlsVisible = !controlsVisible } } },
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setOnClickListener { controlsVisible = !controlsVisible }
                    }
                },
                update = { view -> view.player = player },
                onRelease = { view -> view.player = null },
            )
        }

        state.loading?.let { msg ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.size(10.dp))
                Text(msg, color = Color.White, fontSize = 15.sp)
            }
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // 위쪽: 뒤로 가기 + 제목 + 모드 버튼
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White) }
                    Text(state.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 15.sp)
                    ModeChip("360°", state.is360, Icons.Default.Explore, onToggle360)
                    Spacer(Modifier.width(4.dp))
                    ModeChip("소리만", state.audioOnly, Icons.Default.Headphones, onToggleAudioOnly)
                }

                // 가운데: 10초 되감기 / 재생·일시정지 / 10초 빨리감기
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Default.Replay10, 52.dp) { onSeekBy(-10_000) }
                    RoundIcon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 72.dp, onTogglePlay)
                    RoundIcon(Icons.Default.Forward10, 52.dp) { onSeekBy(10_000) }
                }

                // 아래쪽: 시간 슬라이더 + 배속
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Slider(
                        value = if (dragging) dragValue else if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { dragging = true; dragValue = it },
                        onValueChangeFinished = { dragging = false; onSeekTo((dragValue * duration).toLong()) },
                        colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt(position)} / ${fmt(duration)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            Text(
                                "${s}x",
                                color = if (speed == s) Blue else Color.White,
                                fontWeight = if (speed == s) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 6.dp).clickable { speed = s; onSetSpeed(s) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, active: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.background(if (active) Blue else Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Blue, RoundedCornerShape(50)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f)) }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
