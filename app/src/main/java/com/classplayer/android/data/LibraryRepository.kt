package com.classplayer.android.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.classplayer.android.ClassPlayerApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 재생목록·즐겨찾기·최근 재생을 JSON 파일 하나에 저장한다.
 * 양이 작아서(수십~수백 항목) DB 없이 파일로 충분하다.
 *
 * 무결성:
 * - AtomicFile로 저장해서, 저장 중에 앱이 꺼져도(업데이트·배터리) 예전 파일이 그대로 남는다.
 * - 파일이 깨져서 못 읽으면 덮어쓰지 않고 library.broken-시각.json 으로 따로 보관한다.
 * - 화면(메인 스레드)과 유튜브 해석(백그라운드)이 동시에 고쳐도 서로 지우지 않게 한 번에 하나씩 고친다.
 */
class LibraryRepository(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val atomic = AtomicFile(file)
    private val lock = Any()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists
    private val _favorites = MutableStateFlow<List<MediaEntry>>(emptyList())
    val favorites: StateFlow<List<MediaEntry>> = _favorites
    private val _recents = MutableStateFlow<List<MediaEntry>>(emptyList())
    val recents: StateFlow<List<MediaEntry>> = _recents

    init { load() }

    private fun load() {
        // AtomicFile은 저장이 중간에 끊겼으면 백업본으로 되돌린 뒤 읽는다
        val text = runCatching { atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return
        try {
            val root = JSONObject(text)
            _playlists.value = root.optJSONArray("playlists").toList { Playlist.fromJson(it) }
            _favorites.value = root.optJSONArray("favorites").toList { MediaEntry.fromJson(it) }
            _recents.value = root.optJSONArray("recents").toList { MediaEntry.fromJson(it) }
        } catch (e: Exception) {
            Log.e(ClassPlayerApp.TAG, "library.json을 읽지 못해 따로 보관합니다", e)
            runCatching { file.copyTo(File(file.parentFile, "library.broken-${System.currentTimeMillis()}.json"), overwrite = true) }
        }
    }

    private fun save() = synchronized(lock) {
        val root = JSONObject()
            .put("playlists", JSONArray().apply { _playlists.value.forEach { put(it.toJson()) } })
            .put("favorites", JSONArray().apply { _favorites.value.forEach { put(it.toJson()) } })
            .put("recents", JSONArray().apply { _recents.value.forEach { put(it.toJson()) } })
        var out: FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            out?.let { atomic.failWrite(it) }
            Log.e(ClassPlayerApp.TAG, "재생목록 저장 실패", e)
        }
    }

    /** 읽고-고치고-저장을 한 번에(다른 스레드가 끼어들어 바뀐 내용을 덮어쓰지 않게). */
    private inline fun <T> edit(block: () -> T): T = synchronized(lock) { block().also { save() } }

    private fun <T> JSONArray?.toList(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { runCatching { f(getJSONObject(it)) }.getOrNull() }

    // ---- 재생목록 ----
    fun createPlaylist(name: String): Playlist = edit {
        val p = Playlist(name = name, colorIndex = _playlists.value.size % 6)
        _playlists.value = _playlists.value + p
        p
    }

    fun playlist(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun updatePlaylist(p: Playlist) = edit {
        _playlists.value = _playlists.value.map { if (it.id == p.id) p else it }
    }

    fun deletePlaylist(id: String) = edit { _playlists.value = _playlists.value.filter { it.id != id } }

    // ---- 미리 받기·유튜브 재생목록 연결 ----
    fun setAutoDownload(id: String, on: Boolean) = changePlaylist(id) { it.copy(autoDownload = on) }

    fun setSyncUrl(id: String, url: String?) = changePlaylist(id) { it.copy(syncUrl = url, syncedAt = 0L, syncError = null) }

    /** 연결한 재생목록을 확인한 결과. 새 항목은 뒤에 붙이고(겹치면 건너뜀), 몇 개 늘었는지 돌려준다. */
    fun applySync(id: String, entries: List<MediaEntry>?, error: String?): Int = edit {
        var added = 0
        _playlists.value = _playlists.value.map { p ->
            if (p.id != id) p else {
                val existing = p.items.map { it.source }.toSet()
                val fresh = entries.orEmpty().distinctBy { it.source }.filter { it.source !in existing }
                added = fresh.size
                p.copy(items = p.items + fresh, syncedAt = System.currentTimeMillis(), syncError = error)
            }
        }
        added
    }

    /** 빈 이름이면 그대로 둔다. */
    fun renamePlaylist(id: String, name: String) = changePlaylist(id) { if (name.isBlank()) it else it.copy(name = name.trim()) }

    private inline fun changePlaylist(id: String, f: (Playlist) -> Playlist) = edit {
        _playlists.value = _playlists.value.map { if (it.id == id) f(it) else it }
    }

    /** 이미 있는 주소는 건너뛴다(넣는 목록 안에서 겹치는 것도 하나만). */
    fun addToPlaylist(id: String, entries: List<MediaEntry>) = changePlaylist(id) { p ->
        val existing = p.items.map { it.source }.toSet()
        p.copy(items = p.items + entries.distinctBy { it.source }.filter { it.source !in existing })
    }

    fun removeFromPlaylist(id: String, entryId: String) = changePlaylist(id) { p -> p.copy(items = p.items.filter { it.id != entryId }) }

    fun moveInPlaylist(id: String, from: Int, to: Int) = changePlaylist(id) { p ->
        if (from !in p.items.indices || to !in p.items.indices) p
        else p.copy(items = p.items.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** 제목·길이·썸네일을 나중에 알게 되면 모든 목록의 같은 항목을 갱신한다. 바뀐 게 없으면 저장하지 않는다. */
    fun updateMeta(source: String, title: String?, durationSec: Long?, thumbnail: String?) = synchronized(lock) {
        fun fix(e: MediaEntry) = if (e.source == source) e.copy(
            title = title ?: e.title, durationSec = durationSec ?: e.durationSec, thumbnail = thumbnail ?: e.thumbnail,
        ) else e
        val pl = _playlists.value.map { p -> p.copy(items = p.items.map(::fix)) }
        val fav = _favorites.value.map(::fix)
        val rec = _recents.value.map(::fix)
        if (pl == _playlists.value && fav == _favorites.value && rec == _recents.value) return@synchronized
        _playlists.value = pl; _favorites.value = fav; _recents.value = rec
        save()
    }

    /** 지운 파일 등, 주소가 같은 항목을 모든 목록에서 뺀다. */
    fun removeSource(source: String) = edit {
        _playlists.value = _playlists.value.map { p -> p.copy(items = p.items.filter { it.source != source }) }
        _favorites.value = _favorites.value.filter { it.source != source }
        _recents.value = _recents.value.filter { it.source != source }
    }

    // ---- 즐겨찾기 ----
    fun isFavorite(source: String) = _favorites.value.any { it.source == source }

    fun toggleFavorite(e: MediaEntry): Boolean = edit {
        val now = !isFavorite(e.source)
        _favorites.value = if (now) listOf(e) + _favorites.value else _favorites.value.filter { it.source != e.source }
        now
    }

    // ---- 최근 ----
    fun addRecent(e: MediaEntry) = edit {
        _recents.value = (listOf(e) + _recents.value.filter { it.source != e.source }).take(30)
    }
}
