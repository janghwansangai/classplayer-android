package com.classplayer.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 재생목록·즐겨찾기·최근 재생을 JSON 파일 하나에 저장한다.
 * 양이 작아서(수십~수백 항목) DB 없이 파일로 충분하다.
 */
class LibraryRepository(context: Context) {
    private val file = File(context.filesDir, "library.json")

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists
    private val _favorites = MutableStateFlow<List<MediaEntry>>(emptyList())
    val favorites: StateFlow<List<MediaEntry>> = _favorites
    private val _recents = MutableStateFlow<List<MediaEntry>>(emptyList())
    val recents: StateFlow<List<MediaEntry>> = _recents

    init { load() }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            _playlists.value = root.optJSONArray("playlists").toList { Playlist.fromJson(it) }
            _favorites.value = root.optJSONArray("favorites").toList { MediaEntry.fromJson(it) }
            _recents.value = root.optJSONArray("recents").toList { MediaEntry.fromJson(it) }
        }
    }

    @Synchronized
    private fun save() {
        val root = JSONObject()
            .put("playlists", JSONArray().apply { _playlists.value.forEach { put(it.toJson()) } })
            .put("favorites", JSONArray().apply { _favorites.value.forEach { put(it.toJson()) } })
            .put("recents", JSONArray().apply { _recents.value.forEach { put(it.toJson()) } })
        file.writeText(root.toString())
    }

    private fun <T> JSONArray?.toList(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { runCatching { f(getJSONObject(it)) }.getOrNull() }

    // ---- 재생목록 ----
    fun createPlaylist(name: String): Playlist {
        val p = Playlist(name = name, colorIndex = _playlists.value.size % 6)
        _playlists.update { it + p }; save(); return p
    }

    fun playlist(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun updatePlaylist(p: Playlist) {
        _playlists.update { list -> list.map { if (it.id == p.id) p else it } }; save()
    }

    fun deletePlaylist(id: String) { _playlists.update { l -> l.filter { it.id != id } }; save() }

    fun addToPlaylist(id: String, entries: List<MediaEntry>) {
        val p = playlist(id) ?: return
        val existing = p.items.map { it.source }.toSet()
        updatePlaylist(p.copy(items = p.items + entries.filter { it.source !in existing }))
    }

    fun removeFromPlaylist(id: String, entryId: String) {
        val p = playlist(id) ?: return
        updatePlaylist(p.copy(items = p.items.filter { it.id != entryId }))
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) {
        val p = playlist(id) ?: return
        if (from !in p.items.indices || to !in p.items.indices) return
        val m = p.items.toMutableList(); m.add(to, m.removeAt(from))
        updatePlaylist(p.copy(items = m))
    }

    /** 제목·길이·썸네일을 나중에 알게 되면 모든 목록의 같은 항목을 갱신한다. */
    fun updateMeta(source: String, title: String?, durationSec: Long?, thumbnail: String?) {
        fun fix(e: MediaEntry) = if (e.source == source) e.copy(
            title = title ?: e.title, durationSec = durationSec ?: e.durationSec, thumbnail = thumbnail ?: e.thumbnail,
        ) else e
        _playlists.update { l -> l.map { p -> p.copy(items = p.items.map(::fix)) } }
        _favorites.update { l -> l.map(::fix) }
        _recents.update { l -> l.map(::fix) }
        save()
    }

    /** 지운 파일 등, 주소가 같은 항목을 모든 목록에서 뺀다. */
    fun removeSource(source: String) {
        _playlists.update { l -> l.map { p -> p.copy(items = p.items.filter { it.source != source }) } }
        _favorites.update { l -> l.filter { it.source != source } }
        _recents.update { l -> l.filter { it.source != source } }
        save()
    }

    // ---- 즐겨찾기 ----
    fun isFavorite(source: String) = _favorites.value.any { it.source == source }

    fun toggleFavorite(e: MediaEntry): Boolean {
        val now = !isFavorite(e.source)
        _favorites.update { l -> if (now) listOf(e) + l else l.filter { it.source != e.source } }
        save(); return now
    }

    // ---- 최근 ----
    fun addRecent(e: MediaEntry) {
        _recents.update { l -> (listOf(e) + l.filter { it.source != e.source }).take(30) }; save()
    }
}
