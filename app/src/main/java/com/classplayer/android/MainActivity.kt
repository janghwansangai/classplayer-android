package com.classplayer.android

import android.Manifest
import android.content.Intent
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
import com.classplayer.android.ui.theme.ClassPlayerTheme

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        // 최근 목록에서 다시 열 수 있게 읽기 권한을 오래 유지한다
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.openUri(uri)
    }

    private val askNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            ClassPlayerTheme {
                val state by vm.state.collectAsState()
                val controller by vm.controller.collectAsState()
                val ctx = LocalContext.current

                // 토스트 안내
                LaunchedEffect(state.status) {
                    state.status?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.clearStatus() }
                }
                // 재생 화면에서는 전체화면 + 화면 꺼짐 방지
                LaunchedEffect(state.screen) { applyImmersive(state.screen == UiState.Screen.Player) }

                when (state.screen) {
                    UiState.Screen.Home -> HomeScreen(
                        state = state,
                        onPlayText = vm::openText,
                        onPickFile = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                        onOpenRecent = vm::openRecent,
                        onRemoveRecent = vm::removeRecent,
                        onUpdateYt = vm::updateYtDlp,
                    )
                    UiState.Screen.Player -> {
                        BackHandler { vm.backToHome() }
                        PlayerScreen(
                            state = state,
                            player = controller,
                            onBack = vm::backToHome,
                            onTogglePlay = vm::togglePlay,
                            onSeekBy = vm::seekBy,
                            onSeekTo = { controller?.seekTo(it) },
                            onSetSpeed = vm::setSpeed,
                            onToggle360 = vm::toggle360,
                            onToggleAudioOnly = vm::toggleAudioOnly,
                            onVrSurface = vm::attachVrSurface,
                        )
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
        intent.action = null // 회전 등으로 다시 들어와도 중복 재생하지 않게
    }

    private fun applyImmersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        super.onStop()
        vm.onAppBackground()
    }
}
