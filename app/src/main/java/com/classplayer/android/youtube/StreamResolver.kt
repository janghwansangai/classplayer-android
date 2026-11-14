package com.classplayer.android.youtube

import android.content.Context
import android.util.Log
import com.classplayer.android.ClassPlayerApp
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

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

/**
 * 유튜브 링크 → 스트림 주소.
 * 폰에서 yt-dlp(파이썬)를 한 번 돌리는 데 몇 초가 걸리므로
 * - 푼 결과를 주소 만료 전까지 캐시하고,
 * - 같은 링크를 동시에 두 번 풀지 않으며(진행 중인 작업 공유),
 * - 재생목록의 다음 곡은 미리 풀어 둔다(prefetch).
 */
object StreamResolver {
    private val youtubeHosts = listOf("youtube.com", "youtu.be", "m.youtube.com", "www.youtube.com", "youtube-nocookie.com")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Pair<ResolvedStream, Long>>()
    @Volatile private var diskLoaded = false
    private val inflight = ConcurrentHashMap<String, Deferred<ResolvedStream>>()

    /** yt-dlp(파이썬)를 동시에 여러 개 돌리면 서로 CPU를 뺏어 모두 느려지므로 하나씩 돌린다. */
    internal val gate = Semaphore(1)

    /** 문자열 안에서 http(s) 링크 하나를 꺼낸다(공유 텍스트에 제목이 섞여 올 수 있다). */
    fun extractUrl(text: String): String? =
        Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')')

    fun isYoutubeUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return youtubeHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isCached(url: String, maxHeight: Int = 1080): Boolean =
        cache["$maxHeight|$url"]?.let { System.currentTimeMillis() < it.second } == true

    /** 유튜브가 저장된 주소를 거부했을 때(403 등) 다시 풀도록 지운다. */
    fun invalidate(context: Context, url: String) {
        cache.keys.filter { it.endsWith("|$url") }.forEach { cache.remove(it) }
        saveDisk(context.applicationContext)
    }

    // ---- 디스크 캐시: 앱을 다시 켜도 만료 전 주소는 바로 쓴다 ----
    private fun diskFile(ctx: Context) = File(ctx.cacheDir, "streams.json")

    @Synchronized
    private fun loadDisk(ctx: Context) {
        if (diskLoaded) return
        diskLoaded = true
        runCatching {
            val root = JSONObject(diskFile(ctx).readText())
            val now = System.currentTimeMillis()
            root.keys().forEach { key ->
                val o = root.getJSONObject(key)
                val exp = o.getLong("exp")
                if (exp <= now) return@forEach
                val h = o.getJSONObject("headers")
                cache[key] = ResolvedStream(
                    title = o.getString("title"), videoUrl = o.getString("videoUrl"),
                    audioUrl = o.optString("audioUrl").takeIf { it.isNotEmpty() },
                    mimeType = o.optString("mimeType").takeIf { it.isNotEmpty() },
                    headers = h.keys().asSequence().associateWith { h.getString(it) },
                    durationSec = o.optLong("durationSec"), isLive = o.optBoolean("isLive"), is360 = o.optBoolean("is360"),
                ) to exp
            }
        }
    }

    @Synchronized
    private fun saveDisk(ctx: Context) {
        runCatching {
            val root = JSONObject()
            val now = System.currentTimeMillis()
            cache.forEach { (key, v) ->
                val (st, exp) = v
                if (exp <= now || st.isLive) return@forEach
                root.put(key, JSONObject()
                    .put("title", st.title).put("videoUrl", st.videoUrl).put("audioUrl", st.audioUrl ?: "")
                    .put("mimeType", st.mimeType ?: "").put("headers", JSONObject(st.headers))
                    .put("durationSec", st.durationSec).put("isLive", st.isLive).put("is360", st.is360).put("exp", exp))
            }
            diskFile(ctx).writeText(root.toString())
        }
    }

    /** 다음 곡을 미리 풀어 둔다. 실패해도 조용히 넘어간다. */
    fun prefetch(context: Context, url: String) {
        loadDisk(context.applicationContext)
        if (!isYoutubeUrl(url) || isCached(url)) return
        scope.launch { runCatching { resolve(context, url) } }
    }

    suspend fun resolve(context: Context, url: String, maxHeight: Int = 1080): ResolvedStream {
        val key = "$maxHeight|$url"
        loadDisk(context.applicationContext)
        cache[key]?.let { (s, exp) -> if (System.currentTimeMillis() < exp) return s else cache.remove(key) }
        val app = context.applicationContext
        val job = inflight.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val s = doResolve(app, url, maxHeight)
                    cache[key] = s to expiryOf(s)
                    saveDisk(app)
                    s
                } finally {
                    inflight.remove(key)
                }
            }
        }
        job.start()
        return job.await()
    }

    /**
     * yt-dlp로 스트림 주소를 푼다. 1080p 이하, 호환성이 좋은 H.264+AAC를 우선하고
     * 없으면 아무 조합이나 쓴다.
     */
    private suspend fun doResolve(app: Context, url: String, maxHeight: Int): ResolvedStream {
        // 알람으로 앱이 막 켜진 경우 등, 엔진 준비가 끝날 때까지 기다린다
        (app as? ClassPlayerApp)?.let { a ->
            val st = a.ytReady.first { it !is ClassPlayerApp.YtState.Loading }
            if (st is ClassPlayerApp.YtState.Failed) error("유튜브 엔진 준비 실패: ${st.message}")
        }
        return gate.withPermit {
        val t0 = System.currentTimeMillis()
        val request = YoutubeDLRequest(url).withCache(app).apply {
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
        Log.i(ClassPlayerApp.TAG, "유튜브 주소 풀기 ${System.currentTimeMillis() - t0}ms: $url")
        toStream(info)
        }
    }

    /** googlevideo 주소의 expire 값(초)보다 10분 일찍, 최대 5시간 캐시. */
    private fun expiryOf(s: ResolvedStream): Long {
        val now = System.currentTimeMillis()
        val exp = Regex("""[?&/]expire[=/](\d+)""").find(s.videoUrl)?.groupValues?.get(1)?.toLongOrNull()
        val byUrl = exp?.let { it * 1000 - 10 * 60 * 1000 } ?: (now + 2 * 60 * 60 * 1000)
        return minOf(byUrl, now + 5 * 60 * 60 * 1000)
    }

    private fun toStream(info: VideoInfo): ResolvedStream {
        val requested = info.requestedFormats
        val headers = info.httpHeaders ?: emptyMap()
        val title = info.title ?: "유튜브 영상"
        val is360 = title.contains("360") && title.contains("VR", ignoreCase = true)
        val isLive = (info.url ?: "").contains("m3u8") || (info.manifestUrl ?: "").contains("m3u8")

        if (!requested.isNullOrEmpty()) {
            val video = requested.firstOrNull { (it.vcodec ?: "none") != "none" } ?: requested.first()
            val audio = requested.firstOrNull { it !== video && (it.acodec ?: "none") != "none" }
            return ResolvedStream(
                title = title,
                videoUrl = video.url ?: error("영상 주소가 비어 있습니다"),
                audioUrl = audio?.url,
                mimeType = mimeFor(video.url),
                headers = video.httpHeaders ?: headers,
                durationSec = info.duration.toLong(),
                isLive = isLive,
                is360 = is360,
            )
        }
        return ResolvedStream(
            title = title,
            videoUrl = info.url ?: error("재생 가능한 스트림을 찾지 못했습니다"),
            audioUrl = null,
            mimeType = mimeFor(info.url),
            headers = headers,
            durationSec = info.duration.toLong(),
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

/**
 * yt-dlp 캐시 폴더 지정. youtubedl-android는 이 옵션이 없으면 --no-cache-dir을 붙여서
 * 매번 유튜브 플레이어 스크립트를 새로 받고 서명 풀이를 다시 한다(곡마다 수십 초).
 */
fun YoutubeDLRequest.withCache(context: Context): YoutubeDLRequest = apply {
    addOption("--cache-dir", File(context.applicationContext.cacheDir, "yt-dlp").apply { mkdirs() }.absolutePath)
}
