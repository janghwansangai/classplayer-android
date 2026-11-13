package com.classplayer.android.youtube

import com.classplayer.android.data.MediaEntry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 유튜브 링크의 제목·썸네일 등 가벼운 정보.
 * - 단일 영상: oEmbed(빠름, yt-dlp 불필요)로 제목·썸네일을 가져온다.
 * - 재생목록 링크(list=...): yt-dlp --flat-playlist 로 항목을 펼친다.
 */
object YoutubeMeta {
    fun videoId(url: String): String? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        val host = u.host.lowercase()
        if (host == "youtu.be") return u.path.trim('/').takeIf { it.isNotEmpty() }
        if (!host.contains("youtube")) return null
        val q = u.query?.split('&')?.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }?.associate { it[0] to it[1] } ?: emptyMap()
        q["v"]?.let { return it }
        val seg = u.path.trim('/').split('/')
        if (seg.size >= 2 && seg[0] in setOf("shorts", "live", "embed")) return seg[1]
        return null
    }

    fun playlistId(url: String): String? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        return u.query?.split('&')?.firstOrNull { it.startsWith("list=") }?.removePrefix("list=")
    }

    fun thumbnailFor(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    /** 단일 영상의 제목·썸네일. 실패하면 제목은 링크 그대로. */
    suspend fun quickEntry(url: String): MediaEntry = withContext(Dispatchers.IO) {
        val id = videoId(url)
        val canonical = if (id != null) "https://www.youtube.com/watch?v=$id" else url
        var title = url
        runCatching {
            val api = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(canonical, "UTF-8")
            val c = URL(api).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            val body = c.inputStream.bufferedReader().readText()
            title = JSONObject(body).optString("title", url)
        }
        MediaEntry(title = title, source = canonical, isLink = true, thumbnail = id?.let(::thumbnailFor))
    }

    /** 재생목록 링크를 항목 목록으로 펼친다(yt-dlp 필요). */
    suspend fun expandPlaylist(url: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        val req = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--no-warnings")
            addOption("--print", "%(id)s\t%(title)s\t%(duration)s")
        }
        val out = YoutubeDL.getInstance().execute(req).out
        out.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 2 || p[0].isBlank()) return@mapNotNull null
            MediaEntry(
                title = p[1].ifBlank { "유튜브 영상" },
                source = "https://www.youtube.com/watch?v=${p[0]}",
                isLink = true,
                durationSec = p.getOrNull(2)?.toDoubleOrNull()?.toLong() ?: 0,
                thumbnail = thumbnailFor(p[0]),
            )
        }.toList()
    }
}
