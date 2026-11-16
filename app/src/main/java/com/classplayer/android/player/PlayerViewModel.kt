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
import com.classplayer.android.data.toMediaItem
import com.classplayer.android.record.RecordJob
import com.classplayer.android.record.SavedFile
import com.classplayer.android.schedule.Scheduler
import com.classplayer.android.update.Updater
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import com.classplayer.android.youtube.StreamResolver
import com.classplayer.android.youtube.YoutubeMeta
import com.classplayer.android.youtube.YoutubeSearch
import com.google.common.util.concurrent.MoreExecutors
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
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
    val updateDialogDismissed: Boolean = false,
    // 유튜브 검색
    val searchQuery: String = "",
    val searchResults: List<MediaEntry> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val backToSearch: Boolean = false, // 검색 결과에서 재생했으면 뒤로 가기 때 검색 화면으로
) {
    enum class Screen { Home, Player, PlaylistEdit, Settings, Search }
    enum class HomeTab { Playlists, Favorites, Saved, Recents }
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
    val settings = (app as ClassPlayerApp).settings
    private val recorder = (app as ClassPlayerApp).recorder
    val recordJobs: StateFlow<List<RecordJob>> = recorder.jobs
    val savedFiles: StateFlow<List<SavedFile>> = recorder.saved
    val schedule: StateFlow<Scheduler.State> = Scheduler.state
    val update: StateFlow<Updater.State> = Updater.state

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
                    is ClassPlayerApp.YtState.Failed -> {
                        pendingAction = null
                        _state.update { it.copy(loading = null) }
                        notify("유튜브 엔진 준비 실패: ${s.message}")
                    }
                    else -> Unit
                }
            }
        }
        connectController()
        viewModelScope.launch { recorder.events.collect { notify(it) } }
        // 재생 오류·곡 건너뜀 안내는 서비스가 처리하고 문구만 넘겨준다
        viewModelScope.launch { PlaybackService.messages.collect { notify(it) } }
        autoCheckUpdate()
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
                    vrSurface?.let(c::setVideoSurface) // 360도 화면이 먼저 만들어진 경우
                    _controller.value = c
                    syncQueueFromController()
                    pendingAction?.let { a -> pendingAction = null; a() }
                }
                .onFailure { e -> notify("재생 엔진 연결 실패: ${e.message}") }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // 다시 풀기·다음 곡 건너뛰기와 안내 문구는 PlaybackService.recover가 맡는다
            _state.update { it.copy(loading = null) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(loading = "불러오는 중…") }
                else -> _state.update { it.copy(loading = null) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = _controller.value ?: return
            syncQueueFromController()
            _state.update { it.copy(queueIndex = c.currentMediaItemIndex) }
            _state.value.current?.let { library.addRecent(it) }
            prefetchAfter(c.currentMediaItemIndex)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { syncQueueFromController() }

        override fun onRepeatModeChanged(repeatMode: Int) { _state.update { it.copy(repeatMode = repeatMode) } }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) { _state.update { it.copy(shuffle = enabled) } }
    }

    fun notify(message: String) = _state.update { it.copy(status = message) }
    fun clearStatus() = _state.update { it.copy(status = null) }

    // ---- 화면 이동 ----
    fun goHome() = _state.update {
        val toSearch = it.screen == UiState.Screen.Player && it.backToSearch && it.searchResults.isNotEmpty()
        it.copy(screen = if (toSearch) UiState.Screen.Search else UiState.Screen.Home, landscape = false)
    }
    fun goPlayer() = _state.update { it.copy(screen = UiState.Screen.Player) }
    fun setHomeTab(t: UiState.HomeTab) = _state.update { it.copy(homeTab = t) }
    fun openPlaylist(id: String) = _state.update { it.copy(screen = UiState.Screen.PlaylistEdit, editingPlaylistId = id) }
    fun toggleLandscape() = _state.update { it.copy(landscape = !it.landscape) }

    // ---- 재생 ----
    /**
     * 재생 엔진 연결(과, 유튜브면 yt-dlp 준비)이 끝났는지. 아직이면 요청을 기억해 두고 준비되면 실행한다.
     * 내 기기 영상은 yt-dlp가 필요 없으므로 엔진 준비(업데이트 등)를 기다리지 않고 바로 튼다.
     */
    private fun requireReady(needsYoutube: Boolean, action: () -> Unit): Boolean {
        val ytWaiting = needsYoutube && !_state.value.ytReady
        if (_controller.value == null || ytWaiting) {
            pendingAction = action
            val msg = if (ytWaiting) "유튜브 엔진 준비 중…(처음 한 번만 몇 초 걸립니다)" else "재생 엔진 준비 중…"
            _state.update { it.copy(screen = UiState.Screen.Player, loading = msg) }
            return false
        }
        return true
    }

    private fun MediaEntry.isYoutube() = isLink && StreamResolver.isYoutubeUrl(source)

    /** 항목 목록을 큐로 삼아 재생한다. */
    fun playEntries(entries: List<MediaEntry>, startIndex: Int = 0, name: String = "지금 재생", fromSearch: Boolean = false) {
        if (entries.isEmpty()) { notify("재생할 항목이 없습니다"); return }
        val start = startIndex.coerceIn(0, entries.lastIndex) // 목록이 그 사이 바뀌어도(저장 완료 등) 죽지 않게
        if (!requireReady(entries[start].isYoutube()) { playEntries(entries, start, name, fromSearch) }) return
        val c = _controller.value!!
        val items = entries.map { it.toMediaItem(name) }
        // 첫 곡과 다음 곡 유튜브 주소를 미리 풀기 시작(로딩 시간 단축)
        StreamResolver.prefetch(getApplication(), entries[start].source)
        prefetchAfter(start, entries)
        _state.update {
            it.copy(screen = UiState.Screen.Player, backToSearch = fromSearch, queueName = name, queue = entries, queueIndex = start, loading = "불러오는 중…", is360 = entries[start].title.contains("360"))
        }
        c.setMediaItems(items, start, 0)
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
        if (text.isBlank()) { notify("유튜브 링크나 검색어를 넣어 주세요"); return }
        // 링크가 아니면 유튜브에서 검색
        val url = StreamResolver.extractUrl(text) ?: run { search(text.trim()); return }
        openLink(url)
    }

    fun openLink(url: String) {
        if (!requireReady(StreamResolver.isYoutubeUrl(url)) { openLink(url) }) return
        _state.update { it.copy(screen = UiState.Screen.Player, loading = "주소를 확인하는 중…") }
        viewModelScope.launch {
            if (StreamResolver.isYoutubeUrl(url) && YoutubeMeta.playlistId(url) != null && YoutubeMeta.videoId(url) == null) {
                val items = runCatching { YoutubeMeta.expandPlaylist(getApplication(), url) }.getOrElse {
                    Log.e(ClassPlayerApp.TAG, "재생목록 펼치기 실패", it)
                    emptyList()
                }
                if (items.isNotEmpty()) playEntries(items, 0, "유튜브 재생목록")
                else { _state.update { it.copy(loading = null) }; notify("재생목록을 읽지 못했습니다. 링크와 인터넷 연결을 확인해 주세요.") }
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
        if (c.isPlaying) { c.pause(); return }
        when (c.playbackState) {
            Player.STATE_ENDED -> c.seekTo(0)
            // 오류로 멈췄거나 예약 종료로 정지된 뒤에는 다시 준비해야 재생된다(안 하면 눌러도 아무 일 없음)
            Player.STATE_IDLE -> if (c.mediaItemCount > 0) c.prepare()
        }
        c.play()
    }
    fun next() { _controller.value?.seekToNextMediaItem() }
    fun previous() {
        val c = _controller.value ?: return
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }
    fun seekBy(ms: Long) {
        val c = _controller.value ?: return
        val d = c.duration // 길이를 모르면(생방송·불러오는 중) C.TIME_UNSET(음수). 그때 0으로 자르면 처음으로 튄다
        val target = (c.currentPosition + ms).coerceAtLeast(0)
        c.seekTo(if (d > 0) target.coerceAtMost(d) else target)
    }
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
    // ---- 영상 잠금 ----
    /** 잠그기: 비밀번호가 없으면 false(먼저 정해야 함). */
    fun lockVideo(): Boolean {
        if (!settings.hasLockPassword.value) return false
        settings.setVideoLocked(true)
        _controller.value?.let { applyVideoEnabled(it) }
        notify("영상을 잠갔습니다. 소리만 나옵니다.")
        return true
    }

    /** 풀기: 비밀번호가 맞으면 null, 아니면 오류 문구(여러 번 틀리면 잠시 막힘). */
    fun unlockVideo(pw: String): String? {
        settings.tryPassword(pw)?.let { return it }
        settings.setVideoLocked(false)
        _controller.value?.let { applyVideoEnabled(it) }
        notify("영상 잠금을 풀었습니다")
        return null
    }

    /** 비밀번호 정하기/바꾸기. 이미 있으면 현재 비밀번호가 맞아야 한다. 오류 문구를 돌려준다(성공이면 null). */
    fun setLockPassword(current: String?, new: String, confirm: String): String? {
        // 새 비밀번호 모양부터 확인해야 오타 때문에 틀린 횟수가 쌓이지 않는다
        if (new.length < 4) return "비밀번호는 4자 이상으로 해 주세요"
        if (new != confirm) return "새 비밀번호가 서로 다릅니다"
        if (settings.hasLockPassword.value) settings.tryPassword(current ?: "")?.let { return "현재 " + it }
        settings.setPassword(new)
        notify("영상 잠금 비밀번호를 정했습니다")
        return null
    }

    /** 비밀번호 지우기(잠금도 풀림). 성공이면 null, 아니면 오류 문구. */
    fun removeLockPassword(current: String): String? {
        settings.tryPassword(current)?.let { return it }
        settings.clearPassword()
        _controller.value?.let { applyVideoEnabled(it) }
        notify("영상 잠금을 없앴습니다")
        return null
    }

    fun toggleAudioOnly() {
        val next = !_state.value.audioOnly
        _state.update { it.copy(audioOnly = next) }
        _controller.value?.let { applyVideoEnabled(it) }
        notify(if (next) "소리만 재생" else "영상 재생")
    }

    private fun applyVideoEnabled(c: MediaController) {
        val disabled = _state.value.audioOnly || inBackground || settings.videoLocked.value
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    fun attachVrSurface(surface: Surface?) {
        val old = vrSurface
        vrSurface = surface
        val c = _controller.value ?: return
        // 360도를 끌 때 그냥 clearVideoSurface()를 부르면, 먼저 붙은 일반 화면(PlayerView)까지 떼어져 검은 화면이 된다
        if (surface != null) c.setVideoSurface(surface) else if (old != null) c.clearVideoSurface(old)
    }

    /** 화면이 꺼지거나 앱이 뒤로 가도 계속 재생. 영상 디코딩만 멈춰 배터리를 아낀다. */
    fun onAppBackground() {
        inBackground = true
        val c = _controller.value ?: return
        if (settings.backgroundPlay.value) applyVideoEnabled(c) else c.pause()
    }
    fun onAppForeground() { inBackground = false; _controller.value?.let { applyVideoEnabled(it) } }

    /** 서비스 쪽 재생 목록(예약 재생 등으로 바뀐 것)을 화면 상태에 맞춘다. */
    private fun syncQueueFromController() {
        val c = _controller.value ?: return
        val n = c.mediaItemCount
        val ids = (0 until n).map { c.getMediaItemAt(it).mediaId }
        if (ids == _state.value.queue.map { it.id }) return
        val entries = (0 until n).map { i ->
            val m = c.getMediaItemAt(i)
            val src = m.requestMetadata.mediaUri?.toString() ?: m.mediaId
            MediaEntry(
                id = m.mediaId, title = m.mediaMetadata.title?.toString() ?: "영상", source = src,
                isLink = src.startsWith("http"), thumbnail = m.mediaMetadata.artworkUri?.toString(),
            )
        }
        val name = if (n > 0) c.getMediaItemAt(0).mediaMetadata.artist?.toString() ?: "지금 재생" else ""
        _state.update { it.copy(queue = entries, queueName = name, queueIndex = c.currentMediaItemIndex) }
    }

    /** 다음 곡(유튜브)을 미리 풀어 둔다. */
    private fun prefetchAfter(index: Int, list: List<MediaEntry> = _state.value.queue) {
        list.getOrNull(index + 1)?.let { StreamResolver.prefetch(getApplication(), it.source) }
    }

    fun prefetchPlaylist(p: Playlist) { p.items.firstOrNull()?.let { StreamResolver.prefetch(getApplication(), it.source) } }

    fun goSettings() = _state.update { it.copy(screen = UiState.Screen.Settings) }

    // ---- 유튜브 검색 ----
    private var searchJob: kotlinx.coroutines.Job? = null

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        _state.update { it.copy(screen = UiState.Screen.Search, searchQuery = q, searching = true, searchError = null, searchResults = emptyList()) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val r = runCatching { YoutubeSearch.search(getApplication(), q) }
            if (_state.value.searchQuery != q) return@launch
            r.onSuccess { list ->
                _state.update { it.copy(searching = false, searchResults = list, searchError = if (list.isEmpty()) "검색 결과가 없습니다" else null) }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e(ClassPlayerApp.TAG, "검색 실패", e)
                _state.update { it.copy(searching = false, searchError = "검색하지 못했습니다. 인터넷 연결을 확인해 주세요.") }
            }
        }
    }

    fun goSearch() = _state.update { it.copy(screen = UiState.Screen.Search) }

    /** 검색 결과를 큐로 삼아 고른 곡부터 재생(다음 곡 버튼으로 이어 듣기). */
    fun playSearchResult(index: Int) {
        val s = _state.value
        playEntries(s.searchResults, index, "검색: ${s.searchQuery}", fromSearch = true)
    }

    fun addEntryToPlaylist(playlistId: String, e: MediaEntry) {
        library.addToPlaylist(playlistId, listOf(e))
        notify("'${library.playlist(playlistId)?.name ?: "재생목록"}'에 추가했습니다")
    }

    fun createPlaylistWith(name: String, e: MediaEntry) {
        val p = library.createPlaylist(name.ifBlank { "새 재생목록" })
        addEntryToPlaylist(p.id, e)
    }

    // ---- 녹화·녹음 ----
    fun startRecord(audioOnly: Boolean, startMs: Long?, endMs: Long?) {
        val e = _state.value.current ?: run { notify("저장할 영상이 없습니다"); return }
        recorder.start(e, audioOnly, startMs, endMs)
        notify(if (audioOnly) "녹음 저장을 시작했습니다" else "녹화 저장을 시작했습니다")
    }
    fun cancelRecord(id: String) { recorder.cancel(id); notify("저장을 취소했습니다") }
    fun deleteSaved(f: SavedFile) { recorder.delete(f); notify("지웠습니다: ${f.title}") }
    fun playSaved(index: Int) = playEntries(savedFiles.value.map { it.toEntry() }, index, "저장됨")
    val savedDir: String get() = recorder.dir.absolutePath

    // ---- 예약 ----
    private val ctx get() = getApplication<Application>()
    fun stopIn(minutes: Int) { Scheduler.stopIn(ctx, minutes); notify("${minutes}분 뒤에 자동으로 멈춥니다") }
    fun stopAt(hour: Int, minute: Int) { Scheduler.stopAt(ctx, Scheduler.nextTime(hour, minute)); notify("예약한 시각에 자동으로 멈춥니다") }
    fun cancelStop() { Scheduler.cancelStop(ctx); notify("자동 종료를 취소했습니다") }
    fun startAt(hour: Int, minute: Int, p: Playlist, daily: Boolean) {
        Scheduler.startAt(ctx, Scheduler.nextTime(hour, minute), p.id, p.name, daily)
        notify("'${p.name}' 자동 시작을 예약했습니다")
    }
    fun cancelStart() { Scheduler.cancelStart(ctx); notify("자동 시작을 취소했습니다") }

    // ---- 업데이트 ----
    private fun autoCheckUpdate() {
        if (!settings.autoUpdate.value) return
        if (System.currentTimeMillis() - settings.lastUpdateCheck < 6 * 60 * 60 * 1000L) return
        viewModelScope.launch {
            settings.lastUpdateCheck = System.currentTimeMillis()
            val r = Updater.check()
            // 와이파이면 알아서 내려받아 두고, 다 받으면 설치를 묻는다
            if (r is Updater.State.Available && Updater.isUnmetered(ctx)) Updater.download(ctx)
        }
    }
    fun checkUpdate() {
        viewModelScope.launch {
            settings.lastUpdateCheck = System.currentTimeMillis()
            when (val r = Updater.check()) {
                is Updater.State.UpToDate -> notify("최신 버전입니다 (${Updater.currentVersion})")
                is Updater.State.Failed -> notify(r.message)
                else -> _state.update { it.copy(updateDialogDismissed = false) }
            }
        }
    }
    fun downloadUpdate() { viewModelScope.launch { Updater.download(ctx); _state.update { it.copy(updateDialogDismissed = false) } } }
    fun installUpdate() { if (!Updater.install(ctx)) notify("'이 출처의 앱 허용'을 켠 뒤 다시 눌러 주세요") }
    fun dismissUpdateDialog() = _state.update { it.copy(updateDialogDismissed = true) }

    // ---- 다른 폰에 설치 링크 보내기 ----
    /** 카카오톡·문자 등으로 보낼 설치 안내 글(항상 최신 APK를 받는 링크). */
    val installMessage: String
        get() = "수업용 영상 플레이어 '클래스플레이어' (안드로이드)\n" +
            "광고 없이 유튜브 영상만, 재생목록·360도·녹화 기능이 있어요.\n\n" +
            "1. 아래 링크를 눌러 앱 파일을 받으세요(약 120MB, 와이파이 권장).\n" +
            "2. 받은 파일을 열고, 처음 한 번 '출처를 알 수 없는 앱 설치'를 허용한 뒤 설치하세요.\n" +
            "3. 카카오톡에서 안 받아지면 링크를 길게 눌러 크롬으로 여세요.\n\n" + INSTALL_URL

    fun shareApp() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "클래스플레이어 설치 링크")
            .putExtra(Intent.EXTRA_TEXT, installMessage)
        runCatching {
            val chooser = Intent.createChooser(send, "설치 링크 보내기")
                // 공유 목록에 클래스플레이어 자신은 빼기
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, com.classplayer.android.MainActivity::class.java)))
            ctx.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { notify("공유 창을 열 수 없습니다") }
    }

    fun copyInstallLink() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("클래스플레이어 설치 링크", installMessage))
        notify("설치 안내와 링크를 복사했습니다")
    }

    // ---- 오류 신고 ----
    /** 오류 취합 센터(구글 앱스 스크립트) 신고 화면을 기본 브라우저로 연다. 개인정보는 보내지 않는다. */
    fun openBugReport(where: String) {
        val url = BUG_REPORT_URL + "?app=class-player-app" +
            "&v=" + URLEncoder.encode(Updater.currentVersion, "UTF-8") +
            "&where=" + URLEncoder.encode(where, "UTF-8")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { notify("브라우저를 열 수 없습니다") }
    }

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
    fun renamePlaylist(id: String, name: String) = library.renamePlaylist(id, name)
    fun deletePlaylist(id: String) {
        library.deletePlaylist(id)
        // 이 재생목록으로 걸어 둔 자동 시작 예약은 같이 지운다(안 지우면 그 시각에 아무것도 안 나옴)
        val s = Scheduler.state.value
        if (s.startAt > 0 && s.startPlaylistId == id) { Scheduler.cancelStart(ctx); notify("이 재생목록의 자동 시작 예약도 지웠습니다") }
        goHome()
    }
    fun removeFromPlaylist(id: String, entryId: String) = library.removeFromPlaylist(id, entryId)
    fun moveInPlaylist(id: String, from: Int, to: Int) = library.moveInPlaylist(id, from, to)

    /** 유튜브 링크(단일 또는 재생목록 링크)를 재생목록에 추가. */
    fun addLinkToPlaylist(playlistId: String, text: String) {
        val url = StreamResolver.extractUrl(text) ?: run { notify("링크를 찾지 못했습니다"); return }
        viewModelScope.launch {
            try {
                val entries = if (StreamResolver.isYoutubeUrl(url) && YoutubeMeta.playlistId(url) != null && YoutubeMeta.videoId(url) == null) {
                    if (!_state.value.ytReady) { notify("엔진 준비 중입니다. 잠시 후 다시 시도해 주세요."); return@launch }
                    notify("재생목록을 읽는 중…")
                    YoutubeMeta.expandPlaylist(getApplication(), url)
                } else listOf(if (StreamResolver.isYoutubeUrl(url)) YoutubeMeta.quickEntry(url) else MediaEntry(title = url, source = url, isLink = true))
                val before = library.playlist(playlistId)?.items?.size ?: 0
                library.addToPlaylist(playlistId, entries)
                val added = (library.playlist(playlistId)?.items?.size ?: 0) - before
                notify(if (added > 0) "${added}개 추가했습니다" else "이미 들어 있는 영상입니다")
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
            // 재생·검색 중인 yt-dlp 파일을 도중에 바꾸지 않게, 하던 일이 끝난 뒤에 바꾼다
            val result = runCatching {
                StreamResolver.gate.withPermit { YoutubeDL.getInstance().updateYoutubeDL(getApplication(), YoutubeDL.UpdateChannel.STABLE) }
            }
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

    companion object {
        /** 깃허브 최신 릴리스의 APK(새 버전을 올리면 같은 링크로 최신판이 받아진다) */
        const val INSTALL_URL = "https://github.com/janghwansangai/classplayer-android/releases/latest/download/app-release.apk"
        private const val BUG_REPORT_URL =
            "https://script.google.com/macros/s/AKfycbxsRD7vRlEP9oKlvlDDPww8cYaDadTTIp1KrqYWSVU5qE5_gUh3bNUskBhHDlXH6FskvA/exec"
    }

    override fun onCleared() {
        _controller.value?.let { it.removeListener(playerListener); it.release() }
        super.onCleared()
    }
}
