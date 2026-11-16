package com.classplayer.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 재생할 수 있는 한 항목: 유튜브 링크 또는 내 기기 영상(content:// 주소). */
data class MediaEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val source: String,          // 유튜브 URL 또는 content URI
    val isLink: Boolean,
    val durationSec: Long = 0,
    val thumbnail: String? = null, // 유튜브 썸네일 URL(로컬은 null → 영상에서 추출)
    val channel: String? = null,   // 유튜브 채널 이름(검색 결과에서)
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("source", source).put("isLink", isLink)
        .put("durationSec", durationSec).put("thumbnail", thumbnail ?: JSONObject.NULL)
        .put("channel", channel ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = MediaEntry(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "영상"),
            source = o.getString("source"),
            isLink = o.optBoolean("isLink", true),
            durationSec = o.optLong("durationSec", 0),
            thumbnail = o.optString("thumbnail").takeIf { it.isNotEmpty() && !o.isNull("thumbnail") },
            channel = o.optString("channel").takeIf { it.isNotEmpty() && !o.isNull("channel") },
        )
    }
}

/** 사용자가 만든 재생목록. */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val items: List<MediaEntry> = emptyList(),
    val colorIndex: Int = 0, // 커버 색(썸네일이 없을 때)
    val autoDownload: Boolean = false, // 미리 받기: 켜면 유튜브 영상을 차례로 내려받아 둔다
    val syncUrl: String? = null,       // 연결한 유튜브 재생목록(일부 공개). 새로 저장한 영상을 자동으로 가져온다
    val syncedAt: Long = 0L,           // 마지막으로 연결한 재생목록을 확인한 시각
    val syncError: String? = null,     // 마지막 확인이 실패했으면 그 이유
) {
    val durationSec: Long get() = items.sumOf { it.durationSec }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("colorIndex", colorIndex)
        .put("autoDownload", autoDownload).put("syncUrl", syncUrl ?: JSONObject.NULL)
        .put("syncedAt", syncedAt).put("syncError", syncError ?: JSONObject.NULL)
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val arr = o.optJSONArray("items") ?: JSONArray()
            return Playlist(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "재생목록"),
                colorIndex = o.optInt("colorIndex", 0),
                autoDownload = o.optBoolean("autoDownload", false),
                syncUrl = o.optString("syncUrl").takeIf { it.isNotEmpty() && !o.isNull("syncUrl") },
                syncedAt = o.optLong("syncedAt", 0L),
                syncError = o.optString("syncError").takeIf { it.isNotEmpty() && !o.isNull("syncError") },
                // 항목 하나가 깨져도 재생목록 전체를 잃지 않게 그 항목만 건너뛴다
                items = (0 until arr.length()).mapNotNull { runCatching { MediaEntry.fromJson(arr.getJSONObject(it)) }.getOrNull() },
            )
        }
    }
}

/** 시간을 "3:12" / "1:02:03" 모양으로. */
fun formatDuration(sec: Long): String {
    if (sec <= 0) return "--:--"
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 재생용 MediaItem으로 바꾼다. 컨트롤러에서 보낼 때는 uri가 빠지므로 requestMetadata에도
 * 주소를 넣고, 서비스 안에서 바로 쓸 때는 withUri=true로 uri도 채운다.
 */
fun MediaEntry.toMediaItem(queueName: String, withUri: Boolean = false): androidx.media3.common.MediaItem {
    val uri = android.net.Uri.parse(source)
    return androidx.media3.common.MediaItem.Builder()
        .setMediaId(id)
        .apply { if (withUri) setUri(uri) }
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title).setArtist(queueName)
                .setArtworkUri(thumbnail?.let(android.net.Uri::parse))
                .build(),
        )
        .setRequestMetadata(androidx.media3.common.MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .build()
}

/** 소리 파일인지(썸네일 아이콘 구분용). */
val MediaEntry.isAudioFile: Boolean
    get() = !isLink && source.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "aac", "opus", "ogg", "wav", "flac")
