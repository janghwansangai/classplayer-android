package com.classplayer.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 사용자 설정(SharedPreferences). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 화면이 꺼지거나 다른 앱으로 가도 소리를 계속 재생할지. 기본 켜짐. */
    private val _backgroundPlay = MutableStateFlow(prefs.getBoolean("backgroundPlay", true))
    val backgroundPlay: StateFlow<Boolean> = _backgroundPlay
    fun setBackgroundPlay(on: Boolean) { _backgroundPlay.value = on; prefs.edit().putBoolean("backgroundPlay", on).apply() }

    /** 앱을 열 때 새 버전을 자동으로 확인할지. 기본 켜짐. */
    private val _autoUpdate = MutableStateFlow(prefs.getBoolean("autoUpdate", true))
    val autoUpdate: StateFlow<Boolean> = _autoUpdate
    fun setAutoUpdate(on: Boolean) { _autoUpdate.value = on; prefs.edit().putBoolean("autoUpdate", on).apply() }

    // ---- 미리 받기 ----
    /** 와이파이(요금이 안 나가는 연결)에서만 받을지. 기본 켜짐. */
    private val _offlineWifiOnly = MutableStateFlow(prefs.getBoolean("offlineWifiOnly", true))
    val offlineWifiOnly: StateFlow<Boolean> = _offlineWifiOnly
    fun setOfflineWifiOnly(on: Boolean) { _offlineWifiOnly.value = on; prefs.edit().putBoolean("offlineWifiOnly", on).apply() }

    /** 받을 화질(세로 픽셀). 720이면 10분에 약 100MB, 1080이면 그 두 배쯤. */
    private val _offlineQuality = MutableStateFlow(prefs.getInt("offlineQuality", 720))
    val offlineQuality: StateFlow<Int> = _offlineQuality
    fun setOfflineQuality(h: Int) { _offlineQuality.value = h; prefs.edit().putInt("offlineQuality", h).apply() }

    // ---- 영상 잠금: 비밀번호를 넣어야 화면(영상)이 보인다. 잠겨 있으면 소리만 나온다 ----
    // 비밀번호는 그대로 저장하지 않고 소금(salt)을 섞은 SHA-256 해시만 저장한다.
    private val _hasLockPassword = MutableStateFlow(prefs.contains("lockHash"))
    val hasLockPassword: StateFlow<Boolean> = _hasLockPassword
    private val _videoLocked = MutableStateFlow(prefs.getBoolean("videoLocked", false) && prefs.contains("lockHash"))
    val videoLocked: StateFlow<Boolean> = _videoLocked

    private fun hash(pw: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest((salt + pw).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun matches(pw: String): Boolean {
        val salt = prefs.getString("lockSalt", null) ?: return false
        return hash(pw, salt) == prefs.getString("lockHash", null)
    }

    /**
     * 비밀번호 확인. 맞으면 null, 아니면 화면에 보일 문구.
     * 학생이 숫자를 하나씩 넣어 보며 맞히지 못하게, 5번 틀리면 30초 동안 막고
     * 그 뒤로 틀릴 때마다 대기 시간을 두 배로 늘린다(최대 10분). 앱을 껐다 켜도 유지된다.
     */
    fun tryPassword(pw: String): String? {
        val now = System.currentTimeMillis()
        val until = prefs.getLong("pwBlockedUntil", 0L)
        // 시계를 뒤로 돌려 대기를 늘리거나 줄이는 경우를 막으려고 최대 10분으로 자른다
        val wait = (until - now).coerceIn(0L, MAX_BLOCK_MS)
        if (wait > 0) return "여러 번 틀렸습니다. ${(wait + 999) / 1000}초 뒤에 다시 해 주세요"
        if (matches(pw)) {
            prefs.edit().remove("pwFails").remove("pwBlockedUntil").apply()
            return null
        }
        val fails = prefs.getInt("pwFails", 0) + 1
        val edit = prefs.edit().putInt("pwFails", fails)
        if (fails >= 5) {
            val block = (30_000L shl (fails - 5).coerceAtMost(5)).coerceAtMost(MAX_BLOCK_MS)
            edit.putLong("pwBlockedUntil", now + block)
            edit.apply()
            return "비밀번호가 틀렸습니다. ${block / 1000}초 동안 다시 할 수 없습니다"
        }
        edit.apply()
        return "비밀번호가 틀렸습니다"
    }

    fun setPassword(pw: String) {
        val salt = java.util.UUID.randomUUID().toString()
        // 비밀번호는 바로 디스크에 쓴다(apply는 앱이 곧바로 꺼지면 못 쓸 수 있다)
        prefs.edit().putString("lockSalt", salt).putString("lockHash", hash(pw, salt)).remove("pwFails").remove("pwBlockedUntil").commit()
        _hasLockPassword.value = true
    }

    fun clearPassword() {
        prefs.edit().remove("lockSalt").remove("lockHash").remove("pwFails").remove("pwBlockedUntil").putBoolean("videoLocked", false).commit()
        _hasLockPassword.value = false
        _videoLocked.value = false
    }

    fun setVideoLocked(on: Boolean) {
        val v = on && _hasLockPassword.value
        _videoLocked.value = v
        prefs.edit().putBoolean("videoLocked", v).commit()
    }

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }

    private companion object {
        const val MAX_BLOCK_MS = 10 * 60_000L
    }
}
