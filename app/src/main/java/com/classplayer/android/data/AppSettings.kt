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

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }
}
