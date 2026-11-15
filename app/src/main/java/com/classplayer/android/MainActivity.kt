package com.classplayer.android

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.classplayer.android.player.PlayerViewModel
import com.classplayer.android.player.UiState
import com.classplayer.android.ui.HomeScreen
import com.classplayer.android.ui.PlayerScreen
import com.classplayer.android.ui.PlaylistScreen
import com.classplayer.android.ui.SearchScreen
import com.classplayer.android.ui.SettingsScreen
import com.classplayer.android.ui.theme.Bg
import com.classplayer.android.ui.theme.ClassPlayerTheme
import com.classplayer.android.update.Updater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()
    private var pickTargetPlaylist: String? = null // 파일 선택 결과를 넣을 재생목록(null이면 바로 재생)

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        vm.persistUri(uri) // 나중에 다시 열 수 있게 읽기 권한 유지
        val target = pickTargetPlaylist
        pickTargetPlaylist = null
        if (target != null) vm.addUriToPlaylist(target, uri) else vm.openUri(uri)
    }

    private val askNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            ClassPlayerTheme {
                val state by vm.state.collectAsState()
                val controller by vm.controller.collectAsState()
                val playlists by vm.library.playlists.collectAsState()
                val favorites by vm.library.favorites.collectAsState()
                val recents by vm.library.recents.collectAsState()
                val saved by vm.savedFiles.collectAsState()
                val jobs by vm.recordJobs.collectAsState()
                val backgroundPlay by vm.settings.backgroundPlay.collectAsState()
                val autoUpdate by vm.settings.autoUpdate.collectAsState()
                val schedule by vm.schedule.collectAsState()
                val update by vm.update.collectAsState()
                val videoLocked by vm.settings.videoLocked.collectAsState()
                val hasLockPassword by vm.settings.hasLockPassword.collectAsState()
                val ctx = LocalContext.current

                LaunchedEffect(state.status) {
                    state.status?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.clearStatus() }
                }
                LaunchedEffect(state.screen, state.landscape) {
                    val playing = state.screen == UiState.Screen.Player
                    requestedOrientation = if (playing && state.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    applyImmersive(playing && state.landscape)
                    if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                // 가로 전체화면 재생만 화면 끝까지, 나머지는 상태바·내비게이션바·키보드를 피해서 그린다
                val fullBleed = state.screen == UiState.Screen.Player && state.landscape
                Box(Modifier.fillMaxSize().background(Bg).then(if (fullBleed) Modifier else Modifier.safeDrawingPadding())) {
                when (state.screen) {
                    UiState.Screen.Home -> HomeScreen(
                        state = state, playlists = playlists, favorites = favorites, recents = recents, saved = saved, jobs = jobs,
                        isPlaying = controller?.isPlaying == true,
                        onTab = vm::setHomeTab,
                        onPlayText = vm::openText,
                        onPickFile = { pickTargetPlaylist = null; pickFile.launch(arrayOf("video/*", "audio/*")) },
                        onOpenPlaylist = { vm.openPlaylist(it.id); vm.prefetchPlaylist(it) },
                        onPlayPlaylist = { vm.playPlaylist(it) },
                        onCreatePlaylist = { vm.createPlaylist(it) },
                        onPlayEntry = vm::playEntry,
                        onToggleFavorite = vm::toggleFavorite,
                        onOpenPlayer = { if (state.current != null) vm.goPlayer() else vm.notify("재생 중인 영상이 없습니다") },
                        onTogglePlay = vm::togglePlay,
                        onNext = vm::next,
                        onOpenSettings = vm::goSettings,
                        onPlaySaved = vm::playSaved,
                        onDeleteSaved = vm::deleteSaved,
                        onCancelJob = vm::cancelRecord,
                    )
                    UiState.Screen.Player -> {
                        BackHandler { if (state.landscape) vm.toggleLandscape() else vm.goHome() }
                        PlayerScreen(
                            state = state, player = controller, playlists = playlists,
                            isFavorite = vm.isFavorite(state.current) && favorites.any { it.source == state.current?.source },
                            onBack = vm::goHome,
                            onTogglePlay = vm::togglePlay, onNext = vm::next, onPrevious = vm::previous,
                            onSeekBy = vm::seekBy, onSeekTo = vm::seekTo, onSetSpeed = vm::setSpeed,
                            onCycleRepeat = vm::cycleRepeat, onToggleShuffle = vm::toggleShuffle,
                            onToggle360 = vm::toggle360, onToggleAudioOnly = vm::toggleAudioOnly,
                            onToggleLandscape = vm::toggleLandscape,
                            onToggleFavorite = { state.current?.let(vm::toggleFavorite) },
                            onAddToPlaylist = vm::addCurrentToPlaylist,
                            onCreatePlaylist = { name -> vm.createPlaylist(name, open = false); vm.library.playlists.value.lastOrNull()?.let { vm.addCurrentToPlaylist(it.id) } },
                            onPlayQueueIndex = vm::playQueueIndex,
                            onVrSurface = vm::attachVrSurface,
                            backgroundPlay = backgroundPlay,
                            schedule = schedule,
                            onToggleBackground = {
                                vm.settings.setBackgroundPlay(!backgroundPlay)
                                vm.notify(if (!backgroundPlay) "백그라운드 재생 켬: 화면을 꺼도 계속 재생" else "백그라운드 재생 끔: 앱을 나가면 멈춤")
                            },
                            onRecord = vm::startRecord,
                            onStopIn = vm::stopIn, onStopAt = vm::stopAt, onCancelStop = vm::cancelStop,
                            videoLocked = videoLocked, hasLockPassword = hasLockPassword,
                            onLockVideo = vm::lockVideo, onUnlockVideo = vm::unlockVideo, onSetLockPassword = vm::setLockPassword,
                        )
                    }
                    UiState.Screen.PlaylistEdit -> {
                        val p = playlists.firstOrNull { it.id == state.editingPlaylistId }
                        BackHandler { vm.goHome() }
                        if (p == null) vm.goHome() else PlaylistScreen(
                            playlist = p, repeatMode = state.repeatMode, shuffle = state.shuffle, ytReady = state.ytReady,
                            onBack = vm::goHome,
                            onRename = { vm.renamePlaylist(p.id, it) },
                            onDelete = { vm.deletePlaylist(p.id) },
                            onAddLink = { vm.addLinkToPlaylist(p.id, it) },
                            onAddFile = { pickTargetPlaylist = p.id; pickFile.launch(arrayOf("video/*", "audio/*")) },
                            onRemove = { vm.removeFromPlaylist(p.id, it) },
                            onMove = { a, b -> vm.moveInPlaylist(p.id, a, b) },
                            onPlayFrom = { vm.playPlaylist(vm.library.playlist(p.id) ?: p, it) },
                            onCycleRepeat = vm::cycleRepeat, onToggleShuffle = vm::toggleShuffle,
                        )
                    }
                    UiState.Screen.Search -> {
                        BackHandler { vm.goHome() }
                        SearchScreen(
                            state = state, playlists = playlists,
                            onBack = vm::goHome,
                            onSearch = vm::search,
                            onPlay = vm::playSearchResult,
                            onAddToPlaylist = { id, e -> vm.addEntryToPlaylist(id, e) },
                            onCreatePlaylistWith = { name, e -> vm.createPlaylistWith(name, e) },
                        )
                    }
                    UiState.Screen.Settings -> {
                        BackHandler { vm.goHome() }
                        SettingsScreen(
                            state = state, backgroundPlay = backgroundPlay, autoUpdate = autoUpdate,
                            schedule = schedule, update = update, playlists = playlists,
                            savedCount = saved.size, savedDir = vm.savedDir,
                            onBack = vm::goHome,
                            onBackgroundPlay = vm.settings::setBackgroundPlay,
                            onAutoUpdate = vm.settings::setAutoUpdate,
                            onStopIn = vm::stopIn, onStopAt = vm::stopAt, onCancelStop = vm::cancelStop,
                            onStartAt = vm::startAt, onCancelStart = vm::cancelStart,
                            onNeedPlaylist = { vm.notify("먼저 재생목록을 만들어 주세요") },
                            onCheckUpdate = vm::checkUpdate,
                            onDownloadUpdate = vm::downloadUpdate,
                            onInstallUpdate = vm::installUpdate,
                            onUpdateYt = vm::updateYtDlp,
                            onOpenSaved = { vm.setHomeTab(UiState.HomeTab.Saved); vm.goHome() },
                            onBugReport = { vm.openBugReport("설정") },
                            videoLocked = videoLocked, hasLockPassword = hasLockPassword,
                            onLockVideo = vm::lockVideo, onUnlockVideo = vm::unlockVideo,
                            onSetLockPassword = vm::setLockPassword, onRemoveLockPassword = vm::removeLockPassword,
                        )
                    }
                }
                }

                // 새 버전 안내: 와이파이에서 미리 받아 두었으면 설치를, 아니면 내려받기를 묻는다
                if (!state.updateDialogDismissed && state.screen != UiState.Screen.Settings) {
                    when (val u = update) {
                        is Updater.State.Ready -> AlertDialog(
                            onDismissRequest = vm::dismissUpdateDialog,
                            title = { Text("새 버전 ${u.version} 준비됨") },
                            text = { Text("설치를 누르면 안드로이드 설치 화면이 열립니다. 재생목록과 저장한 파일은 그대로 남습니다.") },
                            confirmButton = { TextButton(onClick = { vm.dismissUpdateDialog(); vm.installUpdate() }) { Text("설치") } },
                            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text("나중에") } },
                        )
                        is Updater.State.Available -> AlertDialog(
                            onDismissRequest = vm::dismissUpdateDialog,
                            title = { Text("새 버전 ${u.version}이 있습니다") },
                            text = { Text("${u.size / (1024 * 1024)}MB를 내려받습니다. 모바일 데이터라면 와이파이에서 받는 것을 권합니다.") },
                            confirmButton = { TextButton(onClick = { vm.dismissUpdateDialog(); vm.downloadUpdate() }) { Text("내려받기") } },
                            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text("나중에") } },
                        )
                        else -> {}
                    }
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 공유(SEND)로 온 텍스트, 또는 VIEW로 온 파일·링크를 연다. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.openText(it) }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") vm.openLink(uri.toString()) else vm.openUri(uri)
            }
        }
        intent.action = null
    }

    private fun applyImmersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            c.isAppearanceLightStatusBars = false
            c.isAppearanceLightNavigationBars = false
        }
    }

    override fun onStart() { super.onStart(); vm.onAppForeground() }
    override fun onStop() { super.onStop(); vm.onAppBackground() }
}
