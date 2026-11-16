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
    // 아래 값들은 모두 ExoPlayer 재생 스레드에서만 읽고 쓴다(해석 결과도 그 스레드로 넘겨서 붙인다)
    private var inner: MediaSource? = null
    private var error: IOException? = null
    private var generation = 0 // 해제·재준비 때마다 올려서 예전 해석 결과가 늦게 와도 무시

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        // 미디어소스는 재생 스레드에서만 다뤄야 한다. 메인 스레드에서 자식 소스를 준비하면
        // 스레드가 섞여 가끔 엉뚱한 오류나 멈춤이 생긴다.
        val playback = Handler(Looper.myLooper() ?: Looper.getMainLooper())
        val gen = ++generation
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
                playback.post {
                    if (gen != generation) return@post // 그 사이 해제됐거나 다시 준비됨
                    inner = source
                    prepareChildSource(Unit, source)
                }
            } catch (e: Exception) {
                Log.e(ClassPlayerApp.TAG, "유튜브 해석 실패: $youtubeUrl", e)
                val msg = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString()
                playback.post { if (gen == generation) error = IOException("유튜브 링크를 열 수 없습니다: $msg", e) }
            }
        }
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Unit, mediaSource: MediaSource, newTimeline: Timeline) {
        // 실제 스트림 주소의 MediaItem 대신 원래 항목(제목·썸네일·id)을 보이게 한다.
        // 안 그러면 알림과 화면에 제목이 빠지고 재생 큐 동기화가 어긋난다.
        refreshSourceInfo(androidx.media3.exoplayer.source.TimelineWithUpdatedMediaItem(newTimeline, item))
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
        generation++
        inner = null
        error = null
        super.releaseSourceInternal()
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
