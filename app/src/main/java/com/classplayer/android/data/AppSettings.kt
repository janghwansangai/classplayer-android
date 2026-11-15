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

    fun checkPassword(pw: String): Boolean {
        val salt = prefs.getString("lockSalt", null) ?: return false
        return hash(pw, salt) == prefs.getString("lockHash", null)
    }

    fun setPassword(pw: String) {
        val salt = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("lockSalt", salt).putString("lockHash", hash(pw, salt)).apply()
        _hasLockPassword.value = true
    }

    fun clearPassword() {
        prefs.edit().remove("lockSalt").remove("lockHash").putBoolean("videoLocked", false).apply()
        _hasLockPassword.value = false
        _videoLocked.value = false
    }

    fun setVideoLocked(on: Boolean) {
        val v = on && _hasLockPassword.value
        _videoLocked.value = v
        prefs.edit().putBoolean("videoLocked", v).apply()
    }

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }
}
