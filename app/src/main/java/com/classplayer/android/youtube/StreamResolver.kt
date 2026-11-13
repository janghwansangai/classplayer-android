package com.classplayer.android.youtube

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** yt-dlp가 풀어 준 실제 스트림 주소. 유튜브는 보통 영상/소리가 따로 와서 둘을 합쳐 재생한다. */
data class ResolvedStream(
    val title: String,
    val videoUrl: String,
    val audioUrl: String?,
    val mimeType: String?,
    val headers: Map<String, String>,
    val durationSec: Long,
    val isLive: Boolean,
    val is360: Boolean,
)

object StreamResolver {
    private val youtubeHosts = listOf("youtube.com", "youtu.be", "m.youtube.com", "www.youtube.com", "youtube-nocookie.com")

    /** 문자열 안에서 http(s) 링크 하나를 꺼낸다(공유 텍스트에 제목이 섞여 올 수 있다). */
    fun extractUrl(text: String): String? =
        Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')')

    fun isYoutubeUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return youtubeHosts.any { host == it || host.endsWith(".$it") }
    }

    /**
     * yt-dlp로 스트림 주소를 푼다. 1080p 이하, 호환성이 좋은 H.264+AAC를 우선하고
     * 없으면 아무 조합이나 쓴다. 네트워크 작업이라 IO 스레드에서 돈다.
     */
    suspend fun resolve(context: Context, url: String, maxHeight: Int = 1080): ResolvedStream =
        withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption(
                    "-f",
                    "bestvideo[height<=$maxHeight][vcodec^=avc1]+bestaudio[ext=m4a]/" +
                        "bestvideo[height<=$maxHeight]+bestaudio/" +
                        "best[height<=$maxHeight]/best",
                )
            }
            val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
            toStream(info)
        }

    private fun toStream(info: VideoInfo): ResolvedStream {
        val requested = info.requestedFormats
        val headers = info.httpHeaders ?: emptyMap()
        val is360 = (info.title ?: "").contains("360", ignoreCase = true) && (info.title ?: "").contains("VR", ignoreCase = true)
        val isLive = (info.url ?: "").contains("m3u8") || (info.manifestUrl ?: "").contains("m3u8")

        if (!requested.isNullOrEmpty()) {
            val video = requested.firstOrNull { (it.vcodec ?: "none") != "none" } ?: requested.first()
            val audio = requested.firstOrNull { it !== video && (it.acodec ?: "none") != "none" }
            return ResolvedStream(
                title = info.title ?: "유튜브 영상",
                videoUrl = video.url ?: error("영상 주소가 비어 있습니다"),
                audioUrl = audio?.url,
                mimeType = mimeFor(video.url),
                headers = video.httpHeaders ?: headers,
                durationSec = (info.duration).toLong(),
                isLive = isLive,
                is360 = is360,
            )
        }
        return ResolvedStream(
            title = info.title ?: "유튜브 영상",
            videoUrl = info.url ?: error("재생 가능한 스트림을 찾지 못했습니다"),
            audioUrl = null,
            mimeType = mimeFor(info.url),
            headers = headers,
            durationSec = (info.duration).toLong(),
            isLive = isLive,
            is360 = is360,
        )
    }

    /** 주소 모양으로 HLS/DASH를 구분한다(유튜브 생방송은 m3u8). */
    private fun mimeFor(url: String?): String? = when {
        url == null -> null
        url.contains("m3u8") -> "application/x-mpegURL"
        url.contains(".mpd") || url.contains("/dash/") -> "application/dash+xml"
        else -> null
    }
}
