package com.classplayer.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.player.PlaybackService

/** 예약 알람과 재부팅 신호를 받는다. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Scheduler.restore(context)
            Scheduler.ACTION_STOP -> {
                Scheduler.onFired(context, Scheduler.ACTION_STOP)
                PlaybackService.instance?.stopBySchedule()
            }
            Scheduler.ACTION_START -> {
                val playlistId = Scheduler.onFired(context, Scheduler.ACTION_START) ?: return
                // 정확한 알람으로 깨어난 경우 백그라운드에서도 포그라운드 서비스를 시작할 수 있다
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, PlaybackService::class.java)
                            .setAction(PlaybackService.ACTION_SCHEDULED_START)
                            .putExtra(PlaybackService.EXTRA_PLAYLIST_ID, playlistId),
                    )
                }.onFailure { Log.e(ClassPlayerApp.TAG, "예약 재생 시작 실패", it) }
            }
        }
    }
}
