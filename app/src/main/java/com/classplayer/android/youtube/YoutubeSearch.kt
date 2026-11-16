package com.classplayer.android.youtube

import android.content.Context
import android.util.Log
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.data.MediaEntry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 유튜브 검색. 데스크톱과 같은 방식으로 yt-dlp의 "ytsearchN:검색어"를 목록만(--flat-playlist) 받는다.
 * 영상 주소를 풀지 않으므로 자바스크립트 시험 문제가 없어 주소 풀기보다 빠르다. 로그인·광고 없음.
 */
object YoutubeSearch {
    suspend fun search(context: Context, query: String, limit: Int = 20): List<MediaEntry> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        StreamResolver.awaitEngine(app)
        StreamResolver.gate.withPermit {
            val t0 = System.currentTimeMillis()
            val req = YoutubeDLRequest("ytsearch$limit:$query").withDefaults(app).apply {
                addOption("--flat-playlist")
                addOption("--no-warnings")
                addOption("--print", "%(id)s\t%(title)s\t%(channel,uploader)s\t%(duration)s")
            }
            val out = YoutubeDL.getInstance().execute(req).out
            Log.i(ClassPlayerApp.TAG, "유튜브 검색 ${System.currentTimeMillis() - t0}ms: $query")
            out.lineSequence().mapNotNull { line ->
                val p = line.split('\t')
                val id = p.getOrNull(0)?.trim().orEmpty()
                if (id.isEmpty() || id == "NA") return@mapNotNull null
                MediaEntry(
                    title = p.getOrNull(1)?.takeIf { it != "NA" } ?: "유튜브 영상",
                    source = "https://www.youtube.com/watch?v=$id",
                    isLink = true,
                    durationSec = p.getOrNull(3)?.toDoubleOrNull()?.toLong() ?: 0L,
                    thumbnail = YoutubeMeta.thumbnailFor(id),
                    channel = p.getOrNull(2)?.takeIf { it != "NA" && it.isNotBlank() },
                )
            }.distinctBy { it.source }.toList() // 같은 영상이 두 번 오면 목록 키가 겹쳐 앱이 죽는다
        }
    }
}
