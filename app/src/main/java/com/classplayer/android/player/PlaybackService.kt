package com.classplayer.android.player

import android.content.Intent
import android.os.Bundle
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
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 재생 엔진(ExoPlayer)을 들고 있는 포그라운드 서비스.
 * 화면(액티비티)은 MediaController로 여기에 붙어서 조작하고, 앱이 뒤로 가도
 * 소리만 재생 모드면 알림 컨트롤과 함께 계속 재생된다.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true) // 하드웨어 디코더가 실패하면 다른 디코더로 재시도
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(ClassMediaSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true, // 다른 앱이 소리를 내면 자동으로 멈춤(오디오 포커스)
            )
            .setHandleAudioBecomingNoisy(true) // 이어폰이 빠지면 일시정지
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
    }

    /**
     * 컨트롤러가 보낸 MediaItem은 재생 주소(localConfiguration)가 빠진 채로 오므로
     * requestMetadata에 실어 보낸 주소와 부가 정보로 다시 조립한다.
     */
    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val rebuilt = mediaItems.map { item ->
                val meta = item.requestMetadata
                val uri = meta.mediaUri ?: return@map item
                item.buildUpon()
                    .setUri(uri)
                    .setMimeType(meta.extras?.getString(KEY_MIME))
                    .build()
            }
            return Futures.immediateFuture(rebuilt.toMutableList())
        }
    }

    companion object {
        const val KEY_AUDIO_URL = "classplayer.audioUrl"
        const val KEY_MIME = "classplayer.mime"
        const val KEY_HEADERS = "classplayer.headers" // Bundle(String -> String)
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) ClassPlayer/0.1"
    }
}

/**
 * 유튜브처럼 영상과 소리 주소가 따로 오면 둘을 MergingMediaSource로 묶고,
 * 그 밖에는 기본 팩토리(로컬 파일, HLS, DASH)에 맡긴다.
 */
@OptIn(UnstableApi::class)
class ClassMediaSourceFactory(context: android.content.Context) : MediaSource.Factory {
    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(PlaybackService.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
    private val dataSource = DefaultDataSource.Factory(context, http)
    private val default = DefaultMediaSourceFactory(dataSource)

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun setDrmSessionManagerProvider(p: androidx.media3.exoplayer.drm.DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(p); return this
    }

    override fun setLoadErrorHandlingPolicy(p: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(p); return this
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras: Bundle? = mediaItem.requestMetadata.extras
        val headerBundle = extras?.getBundle(PlaybackService.KEY_HEADERS)
        val headers = HashMap<String, String>()
        headerBundle?.keySet()?.forEach { k -> headerBundle.getString(k)?.let { headers[k] = it } }
        http.setDefaultRequestProperties(headers)

        val audioUrl = extras?.getString(PlaybackService.KEY_AUDIO_URL)
        if (audioUrl.isNullOrEmpty()) return default.createMediaSource(mediaItem)

        val progressive = ProgressiveMediaSource.Factory(dataSource)
        val video = progressive.createMediaSource(mediaItem)
        val audio = progressive.createMediaSource(MediaItem.fromUri(audioUrl))
        return MergingMediaSource(true, video, audio)
    }
}
