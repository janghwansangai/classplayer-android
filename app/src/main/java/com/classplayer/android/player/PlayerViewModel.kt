package com.classplayer.android.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
import com.classplayer.android.data.LibraryRepository
import com.classplayer.android.data.MediaEntry
import com.classplayer.android.data.Playlist
import com.classplayer.android.youtube.StreamResolver
import com.classplayer.android.youtube.YoutubeMeta
import com.google.common.util.concurrent.MoreExecutors
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 화면 상태. */
data class UiState(
    val screen: Screen = Screen.Home,
    val homeTab: HomeTab = HomeTab.Playlists,
    val editingPlaylistId: String? = null,
    // 지금 재생 중인 큐
    val queueName: String = "",
    val queue: List<MediaEntry> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val shuffle: Boolean = false,
    val audioOnly: Boolean = false,
    val is360: Boolean = false,
    val landscape: Boolean = false,
    val status: String? = null,
    val loading: String? = null,
    val ytReady: Boolean = false,
    val ytVersion: String? = null,
) {
    enum class Screen { Home, Player, PlaylistEdit }
    enum class HomeTab { Playlists, Favorites, Recents }
    val current: MediaEntry? get() = queue.getOrNull(queueIndex)
    val next: MediaEntry? get() = queue.getOrNull(queueIndex + 1) ?: if (repeatMode == Player.REPEAT_MODE_ALL) queue.firstOrNull()?.takeIf { queue.size > 1 } else null
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

    val library: LibraryRepository = (app as ClassPlayerApp).library

    private var vrSurface: Surface? = null
    private var pendingAction: (() -> Unit)? = null // 엔진 준비 전에 들어온 요청
    private var inBackground = false

    init {
        viewModelScope.launch {
            (app as ClassPlayerApp).ytReady.collect { s ->
                when (s) {
                    is ClassPlayerApp.YtState.Ready -> {
                        _state.update { it.copy(ytReady = true, ytVersion = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull()) }
                        pendingAction?.let { a -> pendingAction = null; a() }
                    }
                    is ClassPlayerApp.YtState.Failed -> notify("유튜브 엔진 준비 실패: ${s.message}")
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
                    c.repeatMode = _state.value.repeatMode
                    _controller.value = c
                    pendingAction?.let { a -> pendingAction = null; a() }
                }
                .onFailure { e -> notify("재생 엔진 연결 실패: ${e.message}") }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(ClassPlayerApp.TAG, "재생 오류", error)
            _state.update { it.copy(loading = null) }
            val cause = error.cause?.message?.takeIf { it.startsWith("유튜브") } ?: error.errorCodeName
            notify("재생할 수 없습니다: $cause")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(loading = "불러오는 중…") }
                else -> _state.update { it.copy(loading = null) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = _controller.value ?: return
            _state.update { it.copy(queueIndex = c.currentMediaItemIndex) }
            _state.value.current?.let { library.addRecent(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) { _state.update { it.copy(repeatMode = repeatMode) } }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) { _state.update { it.copy(shuffle = enabled) } }
    }

    fun notify(message: String) = _state.update { it.copy(status = message) }
    fun clearStatus() = _state.update { it.copy(status = null) }

    // ---- 화면 이동 ----
    fun goHome() = _state.update { it.copy(screen = UiState.Screen.Home, landscape = false) }
    fun goPlayer() = _state.update { it.copy(screen = UiState.Screen.Player) }
    fun setHomeTab(t: UiState.HomeTab) = _state.update { it.copy(homeTab = t) }
    fun openPlaylist(id: String) = _state.update { it.copy(screen = UiState.Screen.PlaylistEdit, editingPlaylistId = id) }
    fun toggleLandscape() = _state.update { it.copy(landscape = !it.landscape) }

    // ---- 재생 ----
    private fun requireReady(action: () -> Unit): Boolean {
        if (_controller.value == null || !_state.value.ytReady) {
            pendingAction = action
            _state.update { it.copy(screen = UiState.Screen.Player, loading = "엔진 준비 중…(처음 한 번만 몇 초 걸립니다)") }
            return false
        }
        return true
    }

    /** 항목 목록을 큐로 삼아 재생한다. */
    fun playEntries(entries: List<MediaEntry>, startIndex: Int = 0, name: String = "지금 재생") {
        if (entries.isEmpty()) { notify("재생할 항목이 없습니다"); return }
        if (!requireReady { playEntries(entries, startIndex, name) }) return
        val c = _controller.value!!
        val items = entries.map { e ->
            MediaItem.Builder()
                .setMediaId(e.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).setArtist(name).setArtworkUri(e.thumbnail?.let(Uri::parse)).build())
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(e.source)).build())
                .build()
        }
        _state.update {
            it.copy(screen = UiState.Screen.Player, queueName = name, queue = entries, queueIndex = startIndex, loading = "불러오는 중…", is360 = entries[startIndex].title.contains("360"))
        }
        c.setMediaItems(items, startIndex, 0)
        c.repeatMode = _state.value.repeatMode
        c.shuffleModeEnabled = _state.value.shuffle
        applyVideoEnabled(c)
        c.prepare()
        c.playWhenReady = true
    }

    fun playEntry(e: MediaEntry) = playEntries(listOf(e), 0, "지금 재생")

    fun playPlaylist(p: Playlist, startIndex: Int = 0) = playEntries(p.items, startIndex, p.name)

    fun playQueueIndex(i: Int) { _controller.value?.seekTo(i, 0); _controller.value?.play() }

    /** 홈 입력(링크/공유 텍스트)을 받아 재생. */
    fun openText(text: String) {
        val url = StreamResolver.extractUrl(text) ?: run { notify("링크를 찾지 못했습니다. 유튜브 링크를 붙여넣어 주세요."); return }
        openLink(url)
    }

    fun openLink(url: String) {
        if (!requireReady { openLink(url) }) return
        _state.update { it.copy(screen = UiState.Screen.Player, loading = "주소를 확인하는 중…") }
        viewModelScope.launch {
            if (YoutubeMeta.playlistId(url) != null && YoutubeMeta.videoId(url) == null) {
                val items = runCatching { YoutubeMeta.expandPlaylist(url) }.getOrElse { notify("재생목록을 읽지 못했습니다: ${it.message}"); emptyList() }
                if (items.isNotEmpty()) playEntries(items, 0, "유튜브 재생목록")
                return@launch
            }
            val entry = if (StreamResolver.isYoutubeUrl(url)) YoutubeMeta.quickEntry(url)
            else MediaEntry(title = url, source = url, isLink = true)
            playEntry(entry)
        }
    }

    fun openUri(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            playEntry(MediaEntry(title = name, source = uri.toString(), isLink = false))
        }
    }

    fun togglePlay() {
        val c = _controller.value ?: return
        if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_ENDED) c.seekTo(0); c.play() }
    }
    fun next() { _controller.value?.seekToNextMediaItem() }
    fun previous() {
        val c = _controller.value ?: return
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }
    fun seekBy(ms: Long) { val c = _controller.value ?: return; c.seekTo((c.currentPosition + ms).coerceIn(0, c.duration.coerceAtLeast(0))) }
    fun seekTo(ms: Long) { _controller.value?.seekTo(ms) }
    fun setSpeed(speed: Float) { _controller.value?.setPlaybackSpeed(speed) }

    /** 반복: 전체 → 한 곡 → 끄기 → 전체 */
    fun cycleRepeat() {
        val next = when (_state.value.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
        _state.update { it.copy(repeatMode = next) }
        _controller.value?.repeatMode = next
        notify(when (next) { Player.REPEAT_MODE_ALL -> "전체 반복"; Player.REPEAT_MODE_ONE -> "한 곡 반복"; else -> "반복 끄기" })
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffle
        _state.update { it.copy(shuffle = next) }
        _controller.value?.shuffleModeEnabled = next
        notify(if (next) "섞어서 재생" else "순서대로 재생")
    }

    fun toggle360() = _state.update { it.copy(is360 = !it.is360) }

    /** 소리만 재생: 영상 트랙을 끄면 디코더가 쉬어서 배터리를 아낀다. */
    fun toggleAudioOnly() {
        val next = !_state.value.audioOnly
        _state.update { it.copy(audioOnly = next) }
        _controller.value?.let { applyVideoEnabled(it) }
        notify(if (next) "소리만 재생" else "영상 재생")
    }

    private fun applyVideoEnabled(c: MediaController) {
        val disabled = _state.value.audioOnly || inBackground
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    fun attachVrSurface(surface: Surface?) {
        vrSurface = surface
        val c = _controller.value ?: return
        if (surface != null) c.setVideoSurface(surface) else c.clearVideoSurface()
    }

    /** 화면이 꺼지거나 앱이 뒤로 가도 계속 재생. 영상 디코딩만 멈춰 배터리를 아낀다. */
    fun onAppBackground() { inBackground = true; _controller.value?.let { applyVideoEnabled(it) } }
    fun onAppForeground() { inBackground = false; _controller.value?.let { applyVideoEnabled(it) } }

    // ---- 즐겨찾기 ----
    fun toggleFavorite(e: MediaEntry) {
        val now = library.toggleFavorite(e)
        notify(if (now) "즐겨찾기에 추가" else "즐겨찾기에서 제거")
    }

    fun isFavorite(e: MediaEntry?) = e != null && library.isFavorite(e.source)

    // ---- 재생목록 관리 ----
    fun createPlaylist(name: String, open: Boolean = true) {
        val p = library.createPlaylist(name.ifBlank { "새 재생목록" })
        if (open) openPlaylist(p.id)
    }
    fun renamePlaylist(id: String, name: String) { library.playlist(id)?.let { library.updatePlaylist(it.copy(name = name.ifBlank { it.name })) } }
    fun deletePlaylist(id: String) { library.deletePlaylist(id); goHome() }
    fun removeFromPlaylist(id: String, entryId: String) = library.removeFromPlaylist(id, entryId)
    fun moveInPlaylist(id: String, from: Int, to: Int) = library.moveInPlaylist(id, from, to)

    /** 유튜브 링크(단일 또는 재생목록 링크)를 재생목록에 추가. */
    fun addLinkToPlaylist(playlistId: String, text: String) {
        val url = StreamResolver.extractUrl(text) ?: run { notify("링크를 찾지 못했습니다"); return }
        viewModelScope.launch {
            try {
                val entries = if (YoutubeMeta.playlistId(url) != null && YoutubeMeta.videoId(url) == null) {
                    if (!_state.value.ytReady) { notify("엔진 준비 중입니다. 잠시 후 다시 시도해 주세요."); return@launch }
                    notify("재생목록을 읽는 중…")
                    YoutubeMeta.expandPlaylist(url)
                } else listOf(if (StreamResolver.isYoutubeUrl(url)) YoutubeMeta.quickEntry(url) else MediaEntry(title = url, source = url, isLink = true))
                library.addToPlaylist(playlistId, entries)
                notify("${entries.size}개 추가했습니다")
            } catch (e: Exception) {
                notify("추가 실패: ${e.message}")
            }
        }
    }

    fun addUriToPlaylist(playlistId: String, uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            library.addToPlaylist(playlistId, listOf(MediaEntry(title = name, source = uri.toString(), isLink = false)))
            notify("추가했습니다: $name")
        }
    }

    fun addCurrentToPlaylist(playlistId: String) {
        val e = _state.value.current ?: return
        library.addToPlaylist(playlistId, listOf(e))
        notify("재생목록에 추가했습니다")
    }

    // ---- 엔진 ----
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

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "영상"
        return runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "영상"
    }

    fun persistUri(uri: Uri) {
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun onCleared() {
        _controller.value?.let { it.removeListener(playerListener); it.release() }
        super.onCleared()
    }
}
