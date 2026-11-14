package com.classplayer.android.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.classplayer.android.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/**
 * 자동 종료 / 자동 시작 예약. 화면이 꺼져 있어도 동작하도록 AlarmManager의 정확한 알람을 쓴다.
 * - 자동 종료: 정해진 시각 또는 N분 후에 소리를 줄이며 멈춘다.
 * - 자동 시작: 정해진 시각에 고른 재생목록을 재생한다(매일 반복 선택 가능).
 */
object Scheduler {
    data class State(
        val stopAt: Long = 0L,
        val startAt: Long = 0L,
        val startPlaylistId: String? = null,
        val startPlaylistName: String = "",
        val startDaily: Boolean = false,
    )

    const val ACTION_STOP = "com.classplayer.android.SCHEDULE_STOP"
    const val ACTION_START = "com.classplayer.android.SCHEDULE_START"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = prefs(ctx)
        _state.value = State(
            stopAt = p.getLong("stopAt", 0L),
            startAt = p.getLong("startAt", 0L),
            startPlaylistId = p.getString("startPlaylistId", null),
            startPlaylistName = p.getString("startPlaylistName", "") ?: "",
            startDaily = p.getBoolean("startDaily", false),
        )
        // 지난 예약 정리(앱이 꺼져 있는 동안 지나간 것)
        val now = System.currentTimeMillis()
        val s = _state.value
        if (s.stopAt in 1 until now) save(ctx, s.copy(stopAt = 0L))
        if (s.startAt in 1 until now) {
            if (s.startDaily) startAt(ctx, nextDaily(s.startAt), s.startPlaylistId ?: return, s.startPlaylistName, true)
            else save(ctx, _state.value.copy(startAt = 0L))
        }
    }

    private fun save(ctx: Context, s: State) {
        _state.value = s
        prefs(ctx).edit()
            .putLong("stopAt", s.stopAt).putLong("startAt", s.startAt)
            .putString("startPlaylistId", s.startPlaylistId).putString("startPlaylistName", s.startPlaylistName)
            .putBoolean("startDaily", s.startDaily).apply()
    }

    // ---- 자동 종료 ----
    fun stopIn(ctx: Context, minutes: Int) = stopAt(ctx, System.currentTimeMillis() + minutes * 60_000L)

    fun stopAt(ctx: Context, at: Long) {
        save(ctx, _state.value.copy(stopAt = at))
        setAlarm(ctx, ACTION_STOP, at, alarmClock = false)
    }

    fun cancelStop(ctx: Context) {
        save(ctx, _state.value.copy(stopAt = 0L))
        alarmManager(ctx).cancel(pending(ctx, ACTION_STOP))
    }

    // ---- 자동 시작 ----
    fun startAt(ctx: Context, at: Long, playlistId: String, playlistName: String, daily: Boolean) {
        save(ctx, _state.value.copy(startAt = at, startPlaylistId = playlistId, startPlaylistName = playlistName, startDaily = daily))
        setAlarm(ctx, ACTION_START, at, alarmClock = true)
    }

    fun cancelStart(ctx: Context) {
        save(ctx, _state.value.copy(startAt = 0L))
        alarmManager(ctx).cancel(pending(ctx, ACTION_START))
    }

    /** 알람이 울렸을 때(리시버에서). 자동 시작이면 재생할 재생목록 id를 돌려준다. */
    internal fun onFired(ctx: Context, action: String): String? {
        init(ctx)
        val s = _state.value
        return when (action) {
            ACTION_STOP -> { save(ctx, s.copy(stopAt = 0L)); null }
            ACTION_START -> {
                if (s.startDaily && s.startPlaylistId != null) startAt(ctx, nextDaily(s.startAt.coerceAtLeast(System.currentTimeMillis() - 60_000)), s.startPlaylistId, s.startPlaylistName, true)
                else save(ctx, s.copy(startAt = 0L))
                s.startPlaylistId
            }
            else -> null
        }
    }

    /** 재부팅 뒤 알람 다시 걸기. */
    fun restore(ctx: Context) {
        init(ctx)
        val s = _state.value
        if (s.stopAt > System.currentTimeMillis()) setAlarm(ctx, ACTION_STOP, s.stopAt, alarmClock = false)
        if (s.startAt > System.currentTimeMillis()) setAlarm(ctx, ACTION_START, s.startAt, alarmClock = true)
    }

    /** 오늘 그 시각이 지났으면 내일 그 시각. */
    fun nextTime(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun nextDaily(prev: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = prev }
        return nextTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun alarmManager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, if (action == ACTION_STOP) 11 else 12,
            Intent(ctx, ScheduleReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun setAlarm(ctx: Context, action: String, at: Long, alarmClock: Boolean) {
        val am = alarmManager(ctx)
        val pi = pending(ctx, action)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        when {
            !exact -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            alarmClock -> {
                val show = PendingIntent.getActivity(ctx, 13, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            }
            else -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
