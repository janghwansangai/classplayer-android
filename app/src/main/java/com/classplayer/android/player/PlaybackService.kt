package com.classplayer.android.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import com.classplayer.android.R
import com.classplayer.android.data.toMediaItem
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.youtube.StreamResolver
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 재생 엔진(ExoPlayer)을 들고 있는 포그라운드 서비스.
 * 화면(액티비티)은 MediaController로 여기에 붙어서 조작하고, 앱이 뒤로 가거나
 * 화면이 꺼져도 알림 컨트롤과 함께 계속 재생된다.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val audioAttrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    private var focusOff = false
    private var scheduledStartAt = 0L

    override fun onCreate() {
        super.onCreate()
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true) // 하드웨어 디코더가 실패하면 다른 디코더로 재시도
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(ClassMediaSourceFactory(this))
            .setAudioAttributes(audioAttrs, true) // 다른 앱이 소리를 내면 자동으로 멈춤(오디오 포커스)
            .setHandleAudioBecomingNoisy(true) // 이어폰이 빠지면 일시정지
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                android.util.Log.i(ClassPlayerApp.TAG, "playWhenReady=$playWhenReady reason=$reason")
                // 예약 재생 뒤 사용자가 직접 재생/일시정지를 누르면 오디오 포커스 처리를 다시 켠다
                if (focusOff && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                    System.currentTimeMillis() - scheduledStartAt > 5_000
                ) {
                    focusOff = false
                    player.setAudioAttributes(audioAttrs, true)
                }
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                android.util.Log.i(ClassPlayerApp.TAG, "suppression=$reason")
            }
        })
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCHEDULED_START) {
            // 포그라운드 서비스로 시작됐으니 바로 알림을 띄운다(이후 Media3 재생 알림이 같은 번호로 대신한다)
            startForegroundNow()
            intent.getStringExtra(EXTRA_PLAYLIST_ID)?.let(::startScheduledPlaylist)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(SCHEDULE_CHANNEL, "예약 재생", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, SCHEDULE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("클래스플레이어")
            .setContentText("예약한 재생목록을 시작합니다")
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        // 1분 안에 재생이 안 되면(링크 오류 등) 알림을 내린다
        main.postDelayed({
            if (session?.player?.isPlaying != true) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }, 60_000)
    }

    /** 예약 시각이 되면 고른 재생목록을 처음부터 재생한다. */
    private fun startScheduledPlaylist(playlistId: String) {
        val app = application as ClassPlayerApp
        val p = app.library.playlist(playlistId)
        val player = session?.player
        if (p == null || p.items.isEmpty() || player == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        android.util.Log.i(ClassPlayerApp.TAG, "예약 재생 시작: ${p.name} (${p.items.size}곡)")
        player.setMediaItems(p.items.map { it.toMediaItem(p.name, withUri = true) })
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = 1f
        scheduledStartAt = System.currentTimeMillis()
        // 안드로이드 15는 백그라운드(알람)에서 시작한 앱의 오디오 포커스 요청을 막는다.
        // 포커스 없이도 소리는 나므로, 예약 재생 때만 포커스 요청을 끈다.
        (player as? ExoPlayer)?.setAudioAttributes(audioAttrs, false)
        focusOff = true
        player.playWhenReady = true
        player.prepare()
    }

    /** 예약 종료: 5초 동안 소리를 줄인 뒤 멈춘다. */
    fun stopBySchedule() {
        val player = session?.player ?: return
        if (!player.isPlaying) { player.stop(); return }
        val steps = 10
        for (i in 1..steps) {
            main.postDelayed({
                player.volume = 1f - i.toFloat() / steps
                if (i == steps) { player.pause(); player.stop(); player.volume = 1f }
            }, i * 500L)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
    }

    /**
     * 컨트롤러가 보낸 MediaItem은 재생 주소(localConfiguration)가 빠진 채로 오므로
     * requestMetadata.mediaUri 로 다시 조립한다.
     */
    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val rebuilt = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: return@map item
                item.buildUpon().setUri(uri).build()
            }
            return Futures.immediateFuture(rebuilt.toMutableList())
        }
    }

    companion object {
        /** 같은 프로세스 안에서 예약 종료를 전달하기 위한 참조. */
        @Volatile var instance: PlaybackService? = null
        const val ACTION_SCHEDULED_START = "com.classplayer.android.SCHEDULED_START"
        const val EXTRA_PLAYLIST_ID = "playlistId"
        private const val SCHEDULE_CHANNEL = "classplayer_schedule"
        private const val NOTIFICATION_ID = 1001 // Media3 기본 재생 알림 번호와 같게
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) ClassPlayer/0.2"
    }
}

/**
 * 유튜브 링크면 지연 해석 소스(LazyYoutubeMediaSource)를, 그 밖(로컬 파일, 일반 http)은
 * 기본 팩토리를 쓴다.
 */
@OptIn(UnstableApi::class)
class ClassMediaSourceFactory(private val context: Context) : MediaSource.Factory {
    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(PlaybackService.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
    private val default = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun setDrmSessionManagerProvider(p: androidx.media3.exoplayer.drm.DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(p); return this
    }

    override fun setLoadErrorHandlingPolicy(p: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(p); return this
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return default.createMediaSource(mediaItem)
        if (StreamResolver.isYoutubeUrl(uri)) {
            val app = context.applicationContext as ClassPlayerApp
            return LazyYoutubeMediaSource(context, mediaItem, uri) { title, dur ->
                app.library.updateMeta(uri, title, dur.takeIf { it > 0 }, null)
            }
        }
        return default.createMediaSource(mediaItem)
    }
}
