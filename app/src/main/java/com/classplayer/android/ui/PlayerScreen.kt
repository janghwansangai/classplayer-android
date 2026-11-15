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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.classplayer.android.data.Playlist
import com.classplayer.android.data.formatDuration
import com.classplayer.android.player.UiState
import com.classplayer.android.schedule.Scheduler
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FiberManualRecord
import com.classplayer.android.ui.theme.Accent
import com.classplayer.android.ui.theme.Bg
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Line
import com.classplayer.android.ui.theme.Muted
import com.classplayer.android.ui.theme.Surface
import com.classplayer.android.vr.VrVideoView
import kotlinx.coroutines.delay

/** 지금 재생 화면(시안 B). 세로는 음악 앱처럼, 가로는 영상 전체화면. */
@Composable
fun PlayerScreen(
    state: UiState,
    player: Player?,
    playlists: List<Playlist>,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggle360: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    onToggleLandscape: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onVrSurface: (android.view.Surface?) -> Unit,
    backgroundPlay: Boolean,
    schedule: Scheduler.State,
    onToggleBackground: () -> Unit,
    onRecord: (audioOnly: Boolean, startMs: Long?, endMs: Long?) -> Unit,
    onStopIn: (Int) -> Unit,
    onStopAt: (Int, Int) -> Unit,
    onCancelStop: () -> Unit,
    videoLocked: Boolean = false,
    hasLockPassword: Boolean = false,
    onLockVideo: () -> Boolean = { false },
    onUnlockVideo: (String) -> Boolean = { false },
    onSetLockPassword: (String?, String, String) -> String? = { _, _, _ -> null },
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showQueue by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showRecord by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showSetPw by remember { mutableStateOf(false) }

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

    val video: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black)) {
            if (videoLocked) {
                // 영상 잠금: 화면은 가리고 소리만 나온다. 비밀번호로 풀어야 영상이 보인다
                Column(Modifier.align(Alignment.Center).padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, tint = Ink, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("영상 잠김 · 소리만 재생 중", color = Ink, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Pill("잠금 풀기", true, { showUnlock = true }, Icons.Default.LockOpen)
                }
            } else if (state.audioOnly) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    state.current?.let { Thumb(it, Modifier.size(width = 160.dp, height = 100.dp), radius = 12.dp) }
                    Spacer(Modifier.height(8.dp))
                    Text("소리만 재생 중", color = Ink, fontSize = 14.sp)
                }
            } else if (state.is360) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> VrVideoView(ctx, onVrSurface) })
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                )
            }
            state.loading?.let { msg ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = Ink, fontSize = 13.sp)
                }
            }
        }
    }

    if (state.landscape) {
        LandscapePlayer(state, isPlaying, position, duration, video, onBack = onToggleLandscape, onTogglePlay, onNext, onPrevious, onSeekTo, onCycleRepeat, onToggleLandscape)
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "닫기", tint = Ink) }
            Text("${state.queueName} · ${state.queueIndex + 1} / ${state.queue.size}", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = {
                if (videoLocked) showUnlock = true
                else if (!hasLockPassword) showSetPw = true
                else onLockVideo()
            }) { Icon(if (videoLocked) Icons.Default.Lock else Icons.Default.LockOpen, if (videoLocked) "영상 잠금 풀기" else "영상 잠그기", tint = if (videoLocked) Accent else Ink) }
            IconButton(onClick = { showTimer = true }) { Icon(Icons.Default.Bedtime, "자동 종료 예약", tint = if (schedule.stopAt > 0) Accent else Ink) }
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.PlaylistAdd, "재생목록에 추가", tint = Ink) }
            IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "재생 큐", tint = Ink) }
        }

        Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))) {
            video(Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Pill("소리만", state.audioOnly, onToggleAudioOnly, Icons.Default.Headphones)
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundIconButton(Icons.Default.FiberManualRecord, "녹화·녹음", 44.dp, Bg.copy(alpha = 0.75f), Color(0xFFF87171)) { if (state.current != null) showRecord = true }
                RoundIconButton(Icons.Default.Explore, "360도", 44.dp, if (state.is360) Accent else Bg.copy(alpha = 0.75f), if (state.is360) Bg else Ink, onToggle360)
                RoundIconButton(Icons.Default.ScreenRotation, "가로로 보기", 44.dp, Bg.copy(alpha = 0.75f), Ink, onToggleLandscape)
            }
        }

        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.current?.title ?: "", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.current?.isLink == true) "유튜브" else "내 영상", color = Muted, fontSize = 13.sp)
                Pill(if (backgroundPlay) "백그라운드 재생 켬" else "백그라운드 재생 끔", backgroundPlay, onToggleBackground, Icons.Default.Headphones)
            }
            if (schedule.stopAt > 0) Text("${formatWhen(schedule.stopAt)}에 자동 종료", color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.padding(horizontal = 24.dp)) {
            Slider(
                value = if (dragging) dragValue else if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; onSeekTo((dragValue * duration).toLong()) },
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Accent, inactiveTrackColor = Line),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(position / 1000), color = Muted, fontSize = 12.sp)
                Text(formatDuration(duration / 1000), color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Shuffle, "섞기", tint = if (state.shuffle) Accent else Muted) }
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "이전 곡", tint = Ink, modifier = Modifier.size(36.dp)) }
                RoundIconButton(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "재생/일시정지", 84.dp, Ink, Bg, onTogglePlay)
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "다음 곡", tint = Ink, modifier = Modifier.size(36.dp)) }
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "반복", tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) Muted else Accent,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                Pill("${speed}x", false, { val i = (speeds.indexOf(speed) + 1) % speeds.size; speed = speeds[i]; onSetSpeed(speed) })
                Pill("−10초", false, { onSeekBy(-10_000) }, Icons.Default.Replay10)
                Pill("+10초", false, { onSeekBy(10_000) }, Icons.Default.Forward10)
                Spacer(Modifier.weight(1f))
                RoundIconButton(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "즐겨찾기", 40.dp, Surface, Accent, onToggleFavorite)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).clickable { showQueue = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("다음", color = Muted, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                state.next?.let { n ->
                    Thumb(n, Modifier.size(40.dp), radius = 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(n.title, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatDuration(n.durationSec), color = Muted, fontSize = 12.sp)
                } ?: Text("마지막 곡입니다", color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showUnlock) {
        PasswordAskDialog("영상 잠금 풀기", "비밀번호를 넣으면 영상이 보입니다.", "풀기", { showUnlock = false }, onUnlockVideo)
    }
    if (showSetPw) {
        PasswordSetDialog(false, { showSetPw = false }) { c, n, f -> onSetLockPassword(c, n, f).also { if (it == null) onLockVideo() } }
    }
    if (showRecord) state.current?.let { cur ->
        RecordDialog(cur, player, onDismiss = { showRecord = false }, onStart = onRecord)
    }
    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("자동 종료") },
            text = { ScheduleControls(schedule, playlists, false, { onStopIn(it); showTimer = false }, { h, m -> onStopAt(h, m); showTimer = false }, { onCancelStop(); showTimer = false }, { _, _, _, _ -> }, {}, {}) },
            confirmButton = { TextButton(onClick = { showTimer = false }) { Text("닫기") } },
        )
    }
    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("${state.queueName} (${state.queue.size})") },
            text = {
                LazyColumn(Modifier.height(400.dp)) {
                    itemsIndexed(state.queue) { i, e ->
                        EntryRow(e, highlight = i == state.queueIndex, onClick = { onPlayQueueIndex(i); showQueue = false }, subtitle = "${i + 1}. ${formatDuration(e.durationSec)}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("닫기") } },
        )
    }
    if (showAdd) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("재생목록에 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    playlists.forEach { p ->
                        Text(
                            "${p.name} (${p.items.size}곡)", fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onAddToPlaylist(p.id); showAdd = false }.padding(12.dp),
                        )
                    }
                    androidx.compose.material3.OutlinedTextField(value = newName, onValueChange = { newName = it }, placeholder = { Text("새 재생목록 이름") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { onCreatePlaylist(newName); showAdd = false } }) { Text("새로 만들어 추가") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun LandscapePlayer(
    state: UiState, isPlaying: Boolean, position: Long, duration: Long,
    video: @Composable (Modifier) -> Unit,
    onBack: () -> Unit, onTogglePlay: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit, onCycleRepeat: () -> Unit, onToggleLandscape: () -> Unit,
) {
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls, isPlaying) { if (controls && isPlaying) { delay(4000); controls = false } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls })
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "세로로", tint = Ink) }
                    Column(Modifier.weight(1f)) {
                        Text(state.current?.title ?: "", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${state.queueName} · ${state.queueIndex + 1}/${state.queue.size}", color = Muted, fontSize = 12.sp)
                    }
                    Pill(when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "한 곡 반복"; Player.REPEAT_MODE_ALL -> "전체 반복"; else -> "반복 끔" }, false, onCycleRepeat, Icons.Default.Repeat)
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(Icons.Default.ScreenRotation, "세로로 보기", 44.dp, Accent, Bg, onToggleLandscape)
                }
                Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(position / 1000), color = Ink, fontSize = 12.sp)
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { onSeekTo((it * duration).toLong()) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Accent, inactiveTrackColor = Line),
                    )
                    Text(formatDuration(duration / 1000), color = Ink, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    RoundIconButton(Icons.Default.SkipPrevious, "이전 곡", 48.dp, Ink.copy(alpha = 0.2f), Ink, onPrevious)
                    Spacer(Modifier.width(10.dp))
                    RoundIconButton(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "재생/일시정지", 64.dp, Accent, Bg, onTogglePlay)
                    Spacer(Modifier.width(10.dp))
                    RoundIconButton(Icons.Default.SkipNext, "다음 곡", 48.dp, Ink.copy(alpha = 0.2f), Ink, onNext)
                }
            }
        }
    }
}
