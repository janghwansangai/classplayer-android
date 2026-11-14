package com.classplayer.android

import android.app.Application
import android.util.Log
import com.classplayer.android.data.AppSettings
import com.classplayer.android.data.LibraryRepository
import com.classplayer.android.record.RecordManager
import com.classplayer.android.schedule.Scheduler
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 앱 전역 초기화. youtubedl-android(yt-dlp 포팅)는 첫 실행 때 파이썬 런타임을 풀어야 해서
 * 몇 초 걸리므로 백그라운드에서 준비하고, 준비 상태를 StateFlow로 알린다.
 */
class ClassPlayerApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 재생목록·즐겨찾기 저장소(앱 전체에서 하나). */
    val library: LibraryRepository by lazy { LibraryRepository(this) }

    /** 사용자 설정. */
    val settings: AppSettings by lazy { AppSettings(this) }

    /** 녹화·녹음(저장) 관리. */
    val recorder: RecordManager by lazy { RecordManager(this) }

    private val _ytReady = MutableStateFlow<YtState>(YtState.Loading)
    val ytReady: StateFlow<YtState> = _ytReady

    sealed class YtState {
        object Loading : YtState()
        object Ready : YtState()
        data class Failed(val message: String) : YtState()
    }

    override fun onCreate() {
        super.onCreate()
        Scheduler.restore(this) // 앱 업데이트 등으로 지워진 예약 알람을 다시 건다
        scope.launch {
            try {
                YoutubeDL.getInstance().init(this@ClassPlayerApp)
                FFmpeg.getInstance().init(this@ClassPlayerApp)
                autoUpdateYtDlp() // 구버전으로 먼저 시도하면 403이 나므로 준비 완료 전에 업데이트한다
                _ytReady.value = YtState.Ready
                Log.i(TAG, "yt-dlp 준비 완료: " + YoutubeDL.getInstance().version(this@ClassPlayerApp))
                // 파이썬을 한 번 띄워 두면(파일이 메모리에 올라와) 첫 유튜브 열기가 몇 초 빨라진다
                runCatching { YoutubeDL.getInstance().execute(com.yausername.youtubedl_android.YoutubeDLRequest(emptyList()).apply { addOption("--version") }) }
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp 초기화 실패", e)
                _ytReady.value = YtState.Failed(e.message ?: e.toString())
            }
        }
    }

    /**
     * 라이브러리에 번들된 yt-dlp는 오래돼서 유튜브가 403을 내는 경우가 있다(2026-11-13 확인).
     * 그래서 하루에 한 번 최신 안정판으로 자동 업데이트한다. 실패해도 조용히 넘어간다.
     */
    private fun autoUpdateYtDlp() {
        val prefs = getSharedPreferences("classplayer", MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_checked", 0L)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        runCatching {
            val st = YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
            Log.i(TAG, "yt-dlp 자동 업데이트: $st -> " + YoutubeDL.getInstance().version(this))
            prefs.edit().putLong("ytdlp_checked", System.currentTimeMillis()).apply()
        }.onFailure { Log.w(TAG, "yt-dlp 자동 업데이트 실패(다음에 다시 시도)", it) }
    }

    companion object {
        const val TAG = "ClassPlayer"
    }
}
