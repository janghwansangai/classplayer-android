package com.classplayer.android.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.classplayer.android.BuildConfig
import com.classplayer.android.ClassPlayerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 깃허브 릴리스에서 새 버전을 확인하고 APK를 내려받아 설치 화면을 연다.
 * 안드로이드는 직접 설치한(사이드로드) 앱을 사용자 확인 없이 바꿀 수 없으므로,
 * 마지막 "설치" 한 번은 사용자가 눌러야 한다.
 */
object Updater {
    private const val REPO = "janghwansangai/classplayer-android"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val notes: String, val url: String, val size: Long) : State
        data class Downloading(val version: String, val progress: Float) : State
        data class Ready(val version: String, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** "v0.3.0" > "0.2.0" ? */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(): State = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val result = runCatching {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "ClassPlayer-Android")
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            if (!isNewer(tag, currentVersion)) return@runCatching State.UpToDate
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: error("릴리스에 APK가 없습니다")
            State.Available(tag.removePrefix("v"), json.optString("body", ""), apk.getString("browser_download_url"), apk.optLong("size", 0))
        }.getOrElse { e -> Log.w(ClassPlayerApp.TAG, "업데이트 확인 실패", e); State.Failed("업데이트 확인 실패: ${e.message}") }
        _state.value = result
        result
    }

    /** 와이파이처럼 요금이 안 나가는 연결인지. */
    fun isUnmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return !cm.isActiveNetworkMetered
    }

    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        val a = _state.value as? State.Available ?: return@withContext
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 예전 파일 정리
        val out = File(dir, "ClassPlayer-${a.version}.apk")
        val part = File(dir, out.name + ".part")
        runCatching {
            _state.value = State.Downloading(a.version, 0f)
            val c = URL(a.url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "ClassPlayer-Android")
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val total = c.contentLengthLong.takeIf { it > 0 } ?: a.size
            c.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 512 * 1024) {
                            _state.value = State.Downloading(a.version, done.toFloat() / total)
                            lastReport = done
                        }
                    }
                }
            }
            part.renameTo(out)
            _state.value = State.Ready(a.version, out)
        }.onFailure { e ->
            part.delete()
            _state.value = State.Failed("내려받기 실패: ${e.message}")
        }
    }

    /**
     * 설치 화면을 연다. "이 출처의 앱 설치 허용"이 꺼져 있으면 그 설정 화면을 먼저 연다.
     * @return 설치 화면을 열었으면 true
     */
    fun install(ctx: Context): Boolean {
        val r = _state.value as? State.Ready ?: return false
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", r.file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
