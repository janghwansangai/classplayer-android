package com.classplayer.android.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.youtube.StreamResolver
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 유튜브 링크를 재생 직전에(yt-dlp로) 풀어서 영상+소리 스트림으로 바꾸는 지연 미디어소스.
 * 재생목록에 유튜브 링크가 여러 개 있어도 미리 다 풀지 않고, ExoPlayer가 다음 곡을
 * 준비할 때 하나씩 푼다. 그래서 곡 넘김이 빠르고 처음 시작도 가볍다.
 */
@OptIn(UnstableApi::class)
class LazyYoutubeMediaSource(
    private val context: Context,
    private val item: MediaItem,
    private val youtubeUrl: String,
    private val onResolved: ((title: String, durationSec: Long) -> Unit)? = null,
) : CompositeMediaSource<Unit>() {
    private var inner: MediaSource? = null
    private var error: IOException? = null
    private val main = Handler(Looper.getMainLooper())
    private var released = false

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        released = false
        executor.execute {
            try {
                val stream = runBlocking { StreamResolver.resolve(context, youtubeUrl) }
                onResolved?.invoke(stream.title, stream.durationSec)
                val http = DefaultHttpDataSource.Factory()
                    .setUserAgent(stream.headers["User-Agent"] ?: PlaybackService.USER_AGENT)
                    .setDefaultRequestProperties(stream.headers.filterKeys { it != "User-Agent" })
                    .setAllowCrossProtocolRedirects(true)
                val ds: DataSource.Factory = http
                val source: MediaSource = if (stream.audioUrl != null) {
                    val pf = ProgressiveMediaSource.Factory(ds)
                    MergingMediaSource(
                        true,
                        pf.createMediaSource(MediaItem.fromUri(stream.videoUrl)),
                        pf.createMediaSource(MediaItem.fromUri(stream.audioUrl)),
                    )
                } else {
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds)
                        .createMediaSource(
                            MediaItem.Builder().setUri(stream.videoUrl).setMimeType(stream.mimeType).build(),
                        )
                }
                main.post {
                    if (released) return@post
                    inner = source
                    prepareChildSource(Unit, source)
                }
            } catch (e: Exception) {
                Log.e(ClassPlayerApp.TAG, "유튜브 해석 실패: $youtubeUrl", e)
                main.post { error = IOException("유튜브 링크를 열 수 없습니다: ${e.message}", e) }
            }
        }
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Unit, mediaSource: MediaSource, newTimeline: Timeline) {
        refreshSourceInfo(newTimeline)
    }

    override fun maybeThrowSourceInfoRefreshError() {
        error?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        inner!!.createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        inner?.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        released = true
        inner = null
        error = null
        super.releaseSourceInternal()
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
