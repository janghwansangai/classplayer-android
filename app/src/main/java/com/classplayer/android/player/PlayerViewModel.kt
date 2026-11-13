package com.classplayer.android.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.youtube.StreamResolver
import com.google.common.util.concurrent.MoreExecutors
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 최근 재생 항목(홈 화면 목록용). */
data class RecentItem(val label: String, val source: String, val isLink: Boolean)

data class UiState(
    val screen: Screen = Screen.Home,
    val title: String = "",
    val status: String? = null,        // 토스트/상태 문구
    val loading: String? = null,       // 로딩 문구(있으면 로딩 표시)
    val is360: Boolean = false,
    val audioOnly: Boolean = false,
    val ytReady: Boolean = false,
    val ytVersion: String? = null,
    val recents: List<RecentItem> = emptyList(),
) {
    enum class Screen { Home, Player }
}

/**
 * 화면 상태와 재생 컨트롤러를 쥐고 있는 뷰모델.
 * 실제 재생은 PlaybackService의 ExoPlayer가 하고, 여기서는 MediaController로 조작한다.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val prefs = app.getSharedPreferences("classplayer", Context.MODE_PRIVATE)
    private var vrSurface: Surface? = null

    init {
        loadRecents()
        viewModelScope.launch {
            (app as ClassPlayerApp).ytReady.collect { s ->
                when (s) {
                    is ClassPlayerApp.YtState.Ready -> _state.update {
                        it.copy(ytReady = true, ytVersion = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull())
                    }
                    is ClassPlayerApp.YtState.Failed -> _state.update { it.copy(status = "유튜브 엔진 준비 실패: ${s.message}") }
                    else -> Unit
                }
            }
        }
        connectController()
    }

    private fun connectController() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { c ->
                    c.addListener(playerListener)
                    _controller.value = c
                }
                .onFailure { e -> notify("재생 엔진 연결 실패: ${e.message}") }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(ClassPlayerApp.TAG, "재생 오류", error)
            _state.update { it.copy(loading = null) }
            notify("재생할 수 없습니다: ${error.errorCodeName}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(loading = "버퍼링 중…") }
                Player.STATE_READY, Player.STATE_ENDED, Player.STATE_IDLE ->
                    _state.update { if (it.loading == "버퍼링 중…") it.copy(loading = null) else it }
            }
        }
    }

    fun notify(message: String) {
        _state.update { it.copy(status = message) }
    }

    fun clearStatus() = _state.update { it.copy(status = null) }

    /** 홈 화면의 입력(링크 또는 아무 텍스트)을 받아 재생한다. */
    fun openText(text: String) {
        val url = StreamResolver.extractUrl(text)
        if (url == null) {
            notify("링크를 찾지 못했습니다. 유튜브 링크를 붙여넣어 주세요.")
            return
        }
        openLink(url)
    }

    fun openLink(url: String) {
        val c = _controller.value ?: run { notify("재생 엔진을 준비하는 중입니다. 잠시 후 다시 눌러 주세요."); return }
        if (!_state.value.ytReady) {
            notify("유튜브 엔진을 준비하는 중입니다(처음 한 번만 몇 초 걸립니다).")
            return
        }
        _state.update { it.copy(screen = UiState.Screen.Player, title = url, loading = "주소를 푸는 중…", is360 = false) }
        viewModelScope.launch {
            try {
                val stream = StreamResolver.resolve(getApplication(), url)
                val extras = Bundle().apply {
                    stream.audioUrl?.let { putString(PlaybackService.KEY_AUDIO_URL, it) }
                    stream.mimeType?.let { putString(PlaybackService.KEY_MIME, it) }
                    putBundle(PlaybackService.KEY_HEADERS, Bundle().apply { stream.headers.forEach { (k, v) -> putString(k, v) } })
                }
                val item = MediaItem.Builder()
                    .setMediaId(url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(stream.title).setArtist("클래스플레이어").build())
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(Uri.parse(stream.videoUrl))
                            .setExtras(extras)
                            .build(),
                    )
                    .build()
                _state.update { it.copy(title = stream.title, loading = "불러오는 중…", is360 = stream.is360) }
                play(c, item)
                addRecent(RecentItem(stream.title, url, isLink = true))
            } catch (e: Exception) {
                Log.e(ClassPlayerApp.TAG, "링크 해석 실패", e)
                _state.update { it.copy(loading = null) }
                notify("링크를 열 수 없습니다: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e}")
            }
        }
    }

    /** 로컬 파일(content:// 또는 file://)을 연다. */
    fun openUri(uri: Uri) {
        val c = _controller.value ?: run { notify("재생 엔진을 준비하는 중입니다."); return }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            _state.update { it.copy(screen = UiState.Screen.Player, title = name, loading = "불러오는 중…", is360 = name.contains("360")) }
            val item = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setArtist("클래스플레이어").build())
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .build()
            play(c, item)
            addRecent(RecentItem(name, uri.toString(), isLink = false))
        }
    }

    fun openRecent(item: RecentItem) {
        if (item.isLink) openLink(item.source) else openUri(Uri.parse(item.source))
    }

    private fun play(c: MediaController, item: MediaItem) {
        c.setMediaItem(item)
        applyAudioOnly(c, _state.value.audioOnly)
        c.prepare()
        c.playWhenReady = true
    }

    fun togglePlay() {
        val c = _controller.value ?: return
        if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_ENDED) c.seekTo(0); c.play() }
    }

    fun seekBy(ms: Long) {
        val c = _controller.value ?: return
        c.seekTo((c.currentPosition + ms).coerceIn(0, c.duration.coerceAtLeast(0)))
    }

    fun setSpeed(speed: Float) {
        _controller.value?.setPlaybackSpeed(speed)
    }

    fun toggle360() {
        _state.update { it.copy(is360 = !it.is360) }
    }

    /** 소리만 재생: 영상 트랙을 끄면 화면이 없어도 디코더가 돌지 않아 배터리를 아낀다. */
    fun toggleAudioOnly() {
        val next = !_state.value.audioOnly
        _state.update { it.copy(audioOnly = next) }
        _controller.value?.let { applyAudioOnly(it, next) }
        notify(if (next) "소리만 재생: 화면을 꺼도 계속 재생됩니다" else "영상 재생으로 돌아왔습니다")
    }

    private fun applyAudioOnly(c: MediaController, audioOnly: Boolean) {
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .build()
    }

    /** 360도 뷰가 만든 Surface를 플레이어에 연결한다(null이면 해제). */
    fun attachVrSurface(surface: Surface?) {
        vrSurface = surface
        val c = _controller.value ?: return
        if (surface != null) c.setVideoSurface(surface) else c.clearVideoSurface()
    }

    /** 앱이 뒤로 갈 때: 소리만 재생 모드가 아니면 멈춘다(수업 중 실수로 소리만 나는 것 방지). */
    fun onAppBackground() {
        if (!_state.value.audioOnly) _controller.value?.pause()
    }

    fun backToHome() {
        _controller.value?.let { it.stop(); it.clearMediaItems() }
        _state.update { it.copy(screen = UiState.Screen.Home, loading = null, is360 = false) }
    }

    /** yt-dlp 업데이트(유튜브 쪽 변경으로 재생이 안 될 때 사용). */
    fun updateYtDlp() {
        notify("유튜브 엔진을 업데이트하는 중…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { YoutubeDL.getInstance().updateYoutubeDL(getApplication(), YoutubeDL.UpdateChannel.STABLE) }
            withContext(Dispatchers.Main) {
                result.onSuccess { st ->
                    val v = runCatching { YoutubeDL.getInstance().version(getApplication()) }.getOrNull()
                    _state.update { it.copy(ytVersion = v) }
                    notify(if (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "이미 최신 버전입니다 ($v)" else "업데이트 완료 ($v)")
                }.onFailure { notify("업데이트 실패: ${it.message}") }
            }
        }
    }

    // ---- 최근 목록 ----
    private fun loadRecents() {
        val raw = prefs.getString("recents", "") ?: ""
        val list = raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 3) RecentItem(p[0], p[1], p[2] == "1") else null
        }.toList()
        _state.update { it.copy(recents = list) }
    }

    private fun addRecent(item: RecentItem) {
        val list = (listOf(item) + _state.value.recents.filter { it.source != item.source }).take(10)
        _state.update { it.copy(recents = list) }
        prefs.edit().putString("recents", list.joinToString("\n") { "${it.label.replace('\t', ' ')}\t${it.source}\t${if (it.isLink) 1 else 0}" }).apply()
    }

    fun removeRecent(item: RecentItem) {
        val list = _state.value.recents.filter { it.source != item.source }
        _state.update { it.copy(recents = list) }
        prefs.edit().putString("recents", list.joinToString("\n") { "${it.label}\t${it.source}\t${if (it.isLink) 1 else 0}" }).apply()
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "영상"
        return runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "영상"
    }

    override fun onCleared() {
        _controller.value?.let { it.removeListener(playerListener); it.release() }
        super.onCleared()
    }
}
