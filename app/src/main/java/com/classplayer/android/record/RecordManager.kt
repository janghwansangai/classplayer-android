package com.classplayer.android.record

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.data.MediaEntry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.classplayer.android.youtube.StreamResolver
import com.classplayer.android.youtube.withDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

/** 진행 중인 녹화/녹음 작업. progress < 0 이면 진행률을 모름. */
data class RecordJob(
    val id: String,
    val title: String,
    val baseName: String,
    val audioOnly: Boolean,
    val isLink: Boolean,
    val progress: Float = -1f,
)

/** 저장된(오프라인 재생 가능한) 파일. */
data class SavedFile(val file: File, val title: String, val audioOnly: Boolean, val sizeBytes: Long, val durationSec: Long) {
    fun toEntry() = MediaEntry(id = "saved:" + file.name, title = title, source = Uri.fromFile(file).toString(), isLink = false, durationSec = durationSec)
}

/**
 * 지금 보는 영상을 전체 또는 구간으로 잘라 앱의 다운로드 폴더에 저장한다.
 * - 유튜브: yt-dlp로 내려받기(구간은 --download-sections, 소리만은 m4a로 추출)
 * - 내 영상: Media3 Transformer로 자르기(소리만은 영상 트랙 제거)
 * 저장 위치: Android/data/com.classplayer.android/files/Download (권한 없이 쓰고, 앱을 지우면 같이 지워짐)
 */
@OptIn(UnstableApi::class)
class RecordManager(private val app: ClassPlayerApp) {
    val dir: File
        get() = (app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "Download")).apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val transformers = HashMap<String, Transformer>()

    private val _jobs = MutableStateFlow<List<RecordJob>>(emptyList())
    val jobs: StateFlow<List<RecordJob>> = _jobs
    private val _saved = MutableStateFlow<List<SavedFile>>(emptyList())
    val saved: StateFlow<List<SavedFile>> = _saved

    /** 완료/실패 안내 문구. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    init { refresh() }

    fun refresh() { scope.launch { _saved.value = scan() } }

    private fun scan(): List<SavedFile> {
        val running = _jobs.value.map { it.baseName }
        return dir.listFiles().orEmpty()
            .filter { f ->
                f.isFile && f.length() > 0 && f.extension.lowercase() in MEDIA_EXTS &&
                    !Regex("""\.f\d+$""").containsMatchIn(f.nameWithoutExtension) && // yt-dlp 중간 파일
                    running.none { belongsTo(f, it) }
            }
            .sortedByDescending { it.lastModified() }
            .map { f -> SavedFile(f, f.nameWithoutExtension, f.extension.lowercase() in AUDIO_EXTS, f.length(), durationOf(f)) }
    }

    private fun durationOf(f: File): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
        } finally { r.release() }
    }.getOrDefault(0L)

    /** 녹화/녹음 시작. startMs/endMs가 null이면 전체. */
    fun start(entry: MediaEntry, audioOnly: Boolean, startMs: Long?, endMs: Long?) {
        val range = if (startMs != null && endMs != null) " (${clock(startMs)}-${clock(endMs)})" else ""
        val title = if (entry.isLink) entry.title else entry.title.replace(Regex("""\.(mp4|m4a|mkv|webm|mov|mp3|avi|ts)$""", RegexOption.IGNORE_CASE), "")
        val root = sanitize(title).take(60).trim().ifBlank { "영상" } + range + if (audioOnly) " 소리" else ""
        var base = root
        var n = 2
        while (dir.listFiles().orEmpty().any { belongsTo(it, base) } || _jobs.value.any { it.baseName == base }) {
            base = "$root $n"; n++
        }
        val job = RecordJob(UUID.randomUUID().toString(), entry.title, base, audioOnly, entry.isLink)
        _jobs.update { it + job }
        if (entry.isLink) recordYoutube(job, entry.source, startMs, endMs) else recordLocal(job, entry.source, startMs, endMs)
    }

    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.isLink) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            finish(job, null)
            deletePartials(job.baseName)
            // 프로세스가 멈추면서 마지막 조각을 쓸 수 있어 잠시 뒤 한 번 더 지운다(같은 이름으로 새로 시작했으면 건드리지 않음)
            main.postDelayed({
                if (_jobs.value.none { it.baseName == job.baseName }) scope.launch { deletePartials(job.baseName); _saved.value = scan() }
            }, 1500)
        } else main.post {
            // 변환을 먼저 멈춘 뒤에 지워야 반쯤 쓴 파일이 '저장됨'에 남지 않는다
            transformers.remove(id)?.cancel()
            deletePartials(job.baseName)
            finish(job, null)
        }
    }

    fun delete(f: SavedFile) {
        f.file.delete()
        app.library.removeSource(Uri.fromFile(f.file).toString())
        refresh()
    }

    private fun recordYoutube(job: RecordJob, url: String, startMs: Long?, endMs: Long?) {
        scope.launch {
            try {
                StreamResolver.awaitEngine(app)
                val req = YoutubeDLRequest(url).withDefaults(app).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--newline")
                    addOption("-o", File(dir, "${job.baseName}.%(ext)s").absolutePath)
                    if (job.audioOnly) {
                        addOption("-f", "ba[ext=m4a]/ba")
                        addOption("-x")
                        addOption("--audio-format", "m4a")
                    } else {
                        addOption("-f", "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/b[height<=1080][ext=mp4]/b")
                        addOption("--merge-output-format", "mp4")
                    }
                    if (startMs != null && endMs != null) {
                        addOption("--download-sections", String.format(Locale.US, "*%.1f-%.1f", startMs / 1000.0, endMs / 1000.0))
                    }
                }
                YoutubeDL.getInstance().execute(req, job.id) { p, _, _ ->
                    if (p > 0) _jobs.update { l -> l.map { if (it.id == job.id) it.copy(progress = p / 100f) else it } }
                }
                finish(job, "저장 완료: ${job.baseName}")
            } catch (e: YoutubeDL.CanceledException) {
                // cancel()에서 정리함
            } catch (e: Exception) {
                if (_jobs.value.none { it.id == job.id }) return@launch // 취소된 작업
                Log.e(ClassPlayerApp.TAG, "유튜브 저장 실패", e)
                deletePartials(job.baseName)
                finish(job, "저장 실패: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e}")
            }
        }
    }

    private fun recordLocal(job: RecordJob, source: String, startMs: Long?, endMs: Long?) {
        main.post {
            val out = File(dir, job.baseName + if (job.audioOnly) ".m4a" else ".mp4")
            val item = MediaItem.Builder().setUri(source).apply {
                if (startMs != null && endMs != null) {
                    setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                }
            }.build()
            val edited = EditedMediaItem.Builder(item).setRemoveVideo(job.audioOnly).build()
            val t = Transformer.Builder(app).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformers.remove(job.id)
                    finish(job, "저장 완료: ${job.baseName}")
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformers.remove(job.id)
                    Log.e(ClassPlayerApp.TAG, "영상 자르기 실패", exportException)
                    out.delete()
                    finish(job, "저장 실패: ${exportException.errorCodeName}")
                }
            }).build()
            transformers[job.id] = t
            t.start(edited, out.absolutePath)
            pollProgress(job.id)
        }
    }

    private fun pollProgress(id: String) {
        val t = transformers[id] ?: return
        val h = ProgressHolder()
        if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) {
            _jobs.update { l -> l.map { if (it.id == id) it.copy(progress = h.progress / 100f) else it } }
        }
        main.postDelayed({ pollProgress(id) }, 500)
    }

    private fun finish(job: RecordJob, message: String?) {
        _jobs.update { l -> l.filter { it.id != job.id } }
        message?.let { _events.tryEmit(it) }
        refresh()
    }

    private fun deletePartials(base: String) {
        dir.listFiles().orEmpty().filter { belongsTo(it, base) }.forEach { it.delete() }
    }

    /** 파일이 이 작업 이름의 결과물/중간 파일인지(이름.mp4, 이름.f137.mp4, 이름.m4a.part 등). */
    private fun belongsTo(f: File, base: String) =
        Regex("^" + Regex.escape(base) + """(\.[A-Za-z0-9-]+){1,3}$""").matches(f.name)

    companion object {
        private val AUDIO_EXTS = setOf("m4a", "mp3", "aac", "opus", "ogg", "wav")
        private val MEDIA_EXTS = AUDIO_EXTS + setOf("mp4", "webm", "mkv", "mov")

        /** 파일 이름에 못 쓰는 글자를 지운다. */
        fun sanitize(s: String) = s.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("""\s+"""), " ").trim()

        /** 파일 이름용 시간 표기(콜론 대신 m/s). */
        fun clock(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "${h}h${m}m${sec}s" else "${m}m${String.format(Locale.US, "%02d", sec)}s"
        }
    }
}
