package com.classplayer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classplayer.android.data.Playlist
import com.classplayer.android.player.UiState
import com.classplayer.android.schedule.Scheduler
import com.classplayer.android.ui.theme.Accent
import com.classplayer.android.ui.theme.Bg
import com.classplayer.android.ui.theme.Ink
import com.classplayer.android.ui.theme.Line
import com.classplayer.android.ui.theme.Muted
import com.classplayer.android.ui.theme.Surface
import com.classplayer.android.update.Updater

/** 설정 화면(시안 B): 재생, 예약, 저장, 업데이트, 유튜브 엔진, 오류 신고. */
@Composable
fun SettingsScreen(
    state: UiState,
    backgroundPlay: Boolean,
    autoUpdate: Boolean,
    schedule: Scheduler.State,
    update: Updater.State,
    playlists: List<Playlist>,
    savedCount: Int,
    savedDir: String,
    onBack: () -> Unit,
    onBackgroundPlay: (Boolean) -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onStopIn: (Int) -> Unit,
    onStopAt: (Int, Int) -> Unit,
    onCancelStop: () -> Unit,
    onStartAt: (Int, Int, Playlist, Boolean) -> Unit,
    onCancelStart: () -> Unit,
    onNeedPlaylist: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onUpdateYt: () -> Unit,
    onOpenSaved: () -> Unit,
    onBugReport: () -> Unit,
    videoLocked: Boolean = false,
    hasLockPassword: Boolean = false,
    onLockVideo: () -> Boolean = { false },
    onUnlockVideo: (String) -> String? = { null },
    onSetLockPassword: (String?, String, String) -> String? = { _, _, _ -> null },
    onRemoveLockPassword: (String) -> String? = { null },
    onShareApp: () -> Unit = {},
    onCopyInstallLink: () -> Unit = {},
) {
    var dialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    when (dialog) {
        "set" -> PasswordSetDialog(hasLockPassword, { dialog = null }, onSetLockPassword)
        "unlock" -> PasswordAskDialog("영상 잠금 풀기", "비밀번호를 넣으면 영상이 보입니다.", "풀기", { dialog = null }, onUnlockVideo)
        "remove" -> PasswordAskDialog("영상 잠금 없애기", "비밀번호를 지우면 잠금 기능이 꺼집니다.", "지우기", { dialog = null }, onRemoveLockPassword)
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Ink) }
            Text("설정", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card("재생") {
                SwitchRow("백그라운드 재생", "화면이 꺼지거나 다른 앱으로 가도 소리를 계속 재생합니다. 알림에서 멈출 수 있어요.", backgroundPlay, onBackgroundPlay)
            }
            Card("영상 잠금") {
                Text(
                    when {
                        !hasLockPassword -> "비밀번호를 정하면 영상을 잠글 수 있습니다. 잠기면 소리만 나오고, 비밀번호를 넣어야 영상이 보입니다."
                        videoLocked -> "잠김 · 소리만 나옵니다"
                        else -> "풀림 · 영상이 보입니다. 재생 화면 위쪽 자물쇠로도 잠글 수 있어요."
                    }, color = if (videoLocked) Accent else Muted, fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasLockPassword) {
                        if (videoLocked) Pill("잠금 풀기", true, { dialog = "unlock" }) else Pill("지금 잠그기", true, { onLockVideo() })
                        Pill("비밀번호 바꾸기", false, { dialog = "set" })
                    } else {
                        Pill("비밀번호 정하기", true, { dialog = "set" })
                    }
                }
                if (hasLockPassword) Row { Pill("잠금 없애기", false, { dialog = "remove" }) }
            }
            Card("예약") {
                ScheduleControls(schedule, playlists, true, onStopIn, onStopAt, onCancelStop, onStartAt, onCancelStart, onNeedPlaylist)
            }
            Card("저장한 영상·소리") {
                Text("$savedCount 개 · 앱 다운로드 폴더", color = Ink, fontSize = 14.sp)
                Text(savedDir, color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("저장됨 목록 열기", false, onOpenSaved) }
            }
            Card("다른 폰에 설치하기") {
                Text("카카오톡·문자 등으로 설치 링크를 보내면, 받은 사람이 눌러서 바로 받을 수 있어요. 링크는 항상 최신 버전을 받습니다.", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("설치 링크 보내기", true, onShareApp, androidx.compose.material.icons.Icons.Default.Share)
                    Pill("링크 복사", false, onCopyInstallLink)
                }
            }
            Card("업데이트") {
                Text("지금 버전 ${Updater.currentVersion}", color = Ink, fontSize = 14.sp)
                when (update) {
                    is Updater.State.Checking -> Text("확인 중…", color = Muted, fontSize = 13.sp)
                    is Updater.State.UpToDate -> Text("최신 버전입니다", color = Muted, fontSize = 13.sp)
                    is Updater.State.Available -> {
                        Text("새 버전 ${update.version} (${update.size / (1024 * 1024)}MB)", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Row { Pill("내려받기", true, onDownloadUpdate) }
                    }
                    is Updater.State.Downloading -> {
                        Text("내려받는 중 ${(update.progress * 100).toInt()}%", color = Muted, fontSize = 13.sp)
                        LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Line)
                    }
                    is Updater.State.Ready -> {
                        Text("새 버전 ${update.version} 준비됨", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Row { Pill("설치하기", true, onInstallUpdate) }
                    }
                    is Updater.State.Failed -> Text(update.message, color = Muted, fontSize = 13.sp)
                    else -> {}
                }
                Row { Pill("새 버전 확인", false, onCheckUpdate) }
                SwitchRow("자동으로 확인", "앱을 열 때 새 버전을 확인하고, 와이파이에서는 미리 내려받아 둡니다. 설치는 한 번 눌러 주셔야 합니다.", autoUpdate, onAutoUpdate)
            }
            Card("유튜브 엔진") {
                Text(if (state.ytReady) "준비됨" + (state.ytVersion?.let { " (yt-dlp $it)" } ?: "") else "준비 중…", color = Ink, fontSize = 14.sp)
                Text("유튜브가 안 열리면 업데이트해 보세요. 하루 한 번은 자동으로 합니다.", color = Muted, fontSize = 12.sp)
                Row { Pill("엔진 업데이트", false, onUpdateYt) }
            }
            Card("도움말") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onBugReport).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("오류 신고", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("오류나 개선할 점을 보내 주세요(브라우저가 열립니다)", color = Muted, fontSize = 12.sp)
                    }
                    Text("열기", color = Accent, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Bg))
    }
}
