package com.classplayer.android.offline

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.data.LibraryRepository
import com.classplayer.android.data.MediaEntry
import com.classplayer.android.youtube.StreamResolver
import com.classplayer.android.youtube.YoutubeMeta
import com.classplayer.android.youtube.withDefaults
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 미리 받기(오프라인 저장).
 * - '미리 받기'를 켠 재생목록의 유튜브 영상을 하나씩 차례로 내려받아 둔다(OfflineDownloadWorker).
 * - 재생목록에 연결한 유튜브 재생목록(일부 공개)을 주기적으로 확인해 새 영상을 붙인다(OfflineSyncWorker).
 * - 받은 파일은 재생할 때 유튜브 대신 쓴다(ClassMediaSourceFactory) → 로딩 없이 바로, 인터넷 없이도 재생.
 *
 * 파일은 앱 전용 폴더 Android/data/…/files/Offline/영상ID.mp4 에 둔다(앱을 지우면 같이 지워짐).
 * 이름이 '영상ID.확장자'인 파일만 완성본이다. yt-dlp는 받는 동안 .part·.f137 같은 다른 이름을 쓰고
 * 다 받은 뒤에야 이 이름으로 바꾸므로, 이 이름이 있으면 끝까지 받은 것이다.
 */
object Offline {
    const val WORK_DOWNLOAD = "offline-download"
    const val WORK_SYNC_PERIODIC = "offline-sync-periodic"
    const val WORK_SYNC_NOW = "offline-sync-now"
    /** 이만큼은 비워 두고 받는다(폰이 꽉 차면 다른 앱·사진 저장이 안 된다). */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    data class Progress(val videoId: String, val title: String, val percent: Float, val index: Int, val total: Int)

    /** 받기 실패 기록. 삭제·비공개 영상(permanent)이나 3번 실패한 영상은 '다시 시도'를 누를 때까지 건너뛴다. */
    data class Failure(val attempts: Int, val message: String, val permanent: Boolean) {
        val skip: Boolean get() = permanent || attempts >= 3
    }

    enum class Outcome { OK, SKIPPED, FAILED }

    private val _files = MutableStateFlow<Map<String, File>>(emptyMap())
    /** 받아 둔 영상(영상 ID → 파일). */
    val files: StateFlow<Map<String, File>> = _files.asStateFlow()
    private val _bytes = MutableStateFlow(0L)
    val bytes: StateFlow<Long> = _bytes.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    /** 지금 받는 영상(없으면 null). */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()
    private val _failed = MutableStateFlow<Map<String, Failure>>(emptyMap())
    val failed: StateFlow<Map<String, Failure>> = _failed.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** 받기 전체를 멈추게 한 문제(저장 공간 부족 등). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val EXTS = setOf("mp4", "webm", "mkv", "m4a")
    private val ID_RE = Regex("[A-Za-z0-9_-]{6,20}")
    /** 다시 해도 안 되는 오류(삭제·비공개·연령 제한·지역 제한 등). 그 밖(인터넷 끊김 등)은 다음에 다시 시도. */
    private val PERMANENT_RE = Regex(
        // "Service Unavailable(503)"처럼 서버가 잠깐 아픈 것은 빼려고 'video/is unavailable'만 본다
        "(video|is) unavailable|private video|has been removed|terminated|copyright|confirm your age|not available in your country|members[- ]only",
        RegexOption.IGNORE_CASE,
    )

    @Volatile private var dirCache: File? = null
    fun dir(ctx: Context): File = dirCache ?: (ctx.getExternalFilesDir("Offline") ?: File(ctx.filesDir, "Offline"))
        .apply { mkdirs() }.also { dirCache = it }

    fun init(ctx: Context) {
        rescan(ctx)
        loadFailed(ctx)
    }

    fun rescan(ctx: Context) {
        val map = dir(ctx).listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in EXTS && ID_RE.matches(it.nameWithoutExtension) }
            .associateBy { it.nameWithoutExtension }
        _files.value = map
        _bytes.value = map.values.sumOf { it.length() }
    }

    /** 유튜브 링크면 영상 ID, 아니면 null. */
    fun idOf(source: String): String? =
        if (StreamResolver.isYoutubeUrl(source)) YoutubeMeta.videoId(source)?.takeIf { ID_RE.matches(it) } else null

    /** 받아 둔 파일(없으면 null). 재생할 때 쓴다. */
    fun fileFor(source: String): File? = idOf(source)?.let { _files.value[it] }?.takeIf { it.exists() }

    /** 받아야 할 것: '미리 받기'를 켠 재생목록의 유튜브 영상 중 아직 없는 것(건너뛰기로 한 실패는 빼고), 재생목록 순서대로. */
    fun pending(lib: LibraryRepository): List<Pair<String, MediaEntry>> {
        val have = _files.value
        val failed = _failed.value
        return lib.playlists.value.filter { it.autoDownload }.flatMap { it.items }
            .mapNotNull { e -> idOf(e.source)?.let { it to e } }
            .distinctBy { it.first }
            .filter { (id, _) -> id !in have && failed[id]?.skip != true }
    }

    fun setNotice(msg: String?) { _notice.value = msg }

    // ---- 작업 예약 ----
    /** 받을 게 있으면 내려받기 작업을 건다. replace=true면 하던 것을 멈추고 조건(와이파이 등)을 새로 적용한다. */
    fun schedule(ctx: Context, replace: Boolean = false) {
        val app = ctx.applicationContext as ClassPlayerApp
        if (pending(app.library).isEmpty()) {
            if (replace) WorkManager.getInstance(app).cancelUniqueWork(WORK_DOWNLOAD)
            return
        }
        val wifiOnly = app.settings.offlineWifiOnly.value
        val req = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // 받는 중이면 끝난 뒤에 한 번 더 돌게 붙인다(그 사이 추가된 영상도 받게)
        WorkManager.getInstance(app).enqueueUniqueWork(
            WORK_DOWNLOAD, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, req,
        )
    }

    /** 연결한 재생목록이나 미리 받기가 있으면 6시간마다 확인하는 작업을 건다(없으면 끈다). */
    fun ensurePeriodicSync(ctx: Context) {
        val app = ctx.applicationContext as ClassPlayerApp
        val wm = WorkManager.getInstance(app)
        val needed = app.library.playlists.value.any { it.syncUrl != null || it.autoDownload }
        if (!needed) { wm.cancelUniqueWork(WORK_SYNC_PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<OfflineSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** 앱을 열 때: 마지막 확인이 1시간보다 오래됐으면 연결한 재생목록을 한 번 확인한다. */
    fun syncIfStale(ctx: Context) {
        val app = ctx.applicationContext as ClassPlayerApp
        val now = System.currentTimeMillis()
        if (app.library.playlists.value.none { it.syncUrl != null && now - it.syncedAt > 60 * 60 * 1000L }) return
        val req = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_SYNC_NOW, ExistingWorkPolicy.KEEP, req)
    }

    // ---- 유튜브 재생목록 연결 ----
    /** 연결한 유튜브 재생목록을 확인해 새 영상을 붙인다. 늘어난 개수, 실패하면 null(이유는 playlist.syncError). */
    suspend fun syncPlaylist(app: ClassPlayerApp, playlistId: String): Int? {
        val url = app.library.playlist(playlistId)?.syncUrl ?: return null
        return try {
            val entries = YoutubeMeta.expandPlaylist(app, url)
            app.library.applySync(playlistId, entries, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ClassPlayerApp.TAG, "재생목록 확인 실패: $url", e)
            app.library.applySync(playlistId, null, "재생목록을 읽지 못했습니다. 링크가 맞는지, '비공개'가 아니라 '일부 공개'인지 확인해 주세요.")
            null
        }
    }

    suspend fun syncAll(app: ClassPlayerApp) {
        app.library.playlists.value.filter { it.syncUrl != null }.forEach { syncPlaylist(app, it.id) }
    }

    // ---- 내려받기 ----
    /** 영상 하나를 받는다. 작업이 멈추면(취소·시간 제한) yt-dlp도 같이 멈추고, 받던 .part는 남겨 다음에 이어 받는다. */
    suspend fun download(app: ClassPlayerApp, id: String, e: MediaEntry, index: Int, total: Int): Outcome {
        val dir = dir(app)
        val h = app.settings.offlineQuality.value
        val pid = "offline-$id"
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id").withDefaults(app).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--newline")
            addOption("--no-mtime")
            // 재생 때와 같이 하드웨어 디코딩이 잘 되는 H.264(avc1)+AAC를 우선
            addOption("-f", "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b")
            addOption("--merge-output-format", "mp4")
            addOption("-o", File(dir, "$id.%(ext)s").absolutePath)
        }
        _progress.value = Progress(id, e.title, 0f, index, total)
        val yt = YoutubeDL.getInstance()
        try {
            coroutineScope {
                val run = async(Dispatchers.IO) {
                    yt.execute(req, pid) { p, _, _ -> if (p >= 0f) _progress.update { it?.copy(percent = p / 100f) } }
                }
                try {
                    run.await()
                } catch (c: CancellationException) {
                    runCatching { yt.destroyProcessById(pid) } // 프로세스를 멈춰야 기다림이 끝난다
                    throw c
                }
            }
            rescan(app)
            if (id !in _files.value) error("받은 파일을 찾지 못했습니다")
            clearFailed(app, listOf(id))
            Log.i(ClassPlayerApp.TAG, "미리 받기 완료: $id ${e.title}")
            return Outcome.OK
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            val msg = ex.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: ex.toString()
            val permanent = PERMANENT_RE.containsMatchIn(msg)
            _failed.update { m -> m + (id to Failure((m[id]?.attempts ?: 0) + 1, msg, permanent)) }
            saveFailed(app)
            if (permanent) deleteFiles(app, listOf(id)) // 다시 받지 않을 것이니 받다 만 조각도 지운다
            Log.w(ClassPlayerApp.TAG, "미리 받기 실패: $id $msg")
            return if (permanent) Outcome.SKIPPED else Outcome.FAILED
        } finally {
            _progress.value = null
        }
    }

    // ---- 지우기·정리 ----
    /** 영상 ID들의 파일(받다 만 조각 포함)을 지운다. */
    fun deleteFiles(ctx: Context, ids: Collection<String>) {
        val set = ids.toSet()
        dir(ctx).listFiles().orEmpty().filter { f -> f.name.substringBefore('.') in set }.forEach { it.delete() }
        rescan(ctx)
    }

    /** 재생목록의 받은 파일을 지운다. 다른 '미리 받기' 재생목록에도 있는 영상은 남긴다. 지운 개수. */
    fun deleteForPlaylist(ctx: Context, lib: LibraryRepository, playlistId: String): Int {
        val p = lib.playlist(playlistId) ?: return 0
        val keep = lib.playlists.value.filter { it.id != playlistId && it.autoDownload }.flatMap { it.items }.mapNotNull { idOf(it.source) }.toSet()
        val ids = p.items.mapNotNull { idOf(it.source) }.filter { it !in keep && it in _files.value }
        deleteFiles(ctx, ids)
        return ids.size
    }

    /** 어느 재생목록에도 없는 영상 파일을 지운다(재생목록에서 뺐거나 재생목록을 지운 것). 지운 개수. */
    fun prune(ctx: Context, lib: LibraryRepository): Int {
        val used = lib.playlists.value.flatMap { it.items }.mapNotNull { idOf(it.source) }.toSet()
        val unused = _files.value.keys - used
        deleteFiles(ctx, unused)
        return unused.size
    }

    /** 받은 파일을 모두 지운다(받는 중인 것도 멈춘다). */
    fun clearAll(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_DOWNLOAD)
        dir(ctx).listFiles().orEmpty().forEach { it.delete() }
        rescan(ctx)
        clearFailed(ctx, null)
        _notice.value = null
    }

    // ---- 실패 기록(앱을 껐다 켜도 남게 파일로) ----
    private fun failedFile(ctx: Context) = AtomicFile(File(ctx.filesDir, "offline_failed.json"))

    @Synchronized
    private fun loadFailed(ctx: Context) {
        val text = runCatching { failedFile(ctx).openRead().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return
        runCatching {
            val o = JSONObject(text)
            _failed.value = o.keys().asSequence().associateWith { k ->
                val f = o.getJSONObject(k)
                Failure(f.optInt("attempts", 1), f.optString("message"), f.optBoolean("permanent"))
            }
        }
    }

    @Synchronized
    private fun saveFailed(ctx: Context) {
        val o = JSONObject()
        _failed.value.forEach { (k, f) -> o.put(k, JSONObject().put("attempts", f.attempts).put("message", f.message).put("permanent", f.permanent)) }
        val af = failedFile(ctx)
        var out: FileOutputStream? = null
        try {
            out = af.startWrite()
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (e: Exception) {
            out?.let { af.failWrite(it) }
        }
    }

    /** 실패 기록을 지워 다시 받게 한다(ids가 null이면 전부). */
    fun clearFailed(ctx: Context, ids: Collection<String>?) {
        _failed.update { if (ids == null) emptyMap() else it - ids.toSet() }
        saveFailed(ctx)
    }
}
