package com.classplayer.android.offline

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.classplayer.android.ClassPlayerApp
import com.classplayer.android.MainActivity
import com.classplayer.android.R
import com.classplayer.android.youtube.StreamResolver
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * '미리 받기'를 켠 재생목록의 영상을 하나씩 차례로 받는 백그라운드 작업.
 * 앱을 닫거나 폰을 껐다 켜도 WorkManager가 다시 실행하고, 받다 만 영상은 이어 받는다.
 * 받는 동안은 알림을 띄워 포그라운드로 돌린다(안 되면 그냥 돌다가 10분 제한에 걸리면 다음에 이어 받음).
 */
class OfflineDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val app = applicationContext as ClassPlayerApp
        try {
            StreamResolver.awaitEngine(app)
        } catch (e: Exception) {
            return@coroutineScope Result.retry()
        }
        Offline.rescan(app)
        // 받는 동안 알림의 진행률을 갱신한다
        val notifier = launch {
            Offline.progress.map { p -> p?.let { Triple(it.index, it.total, (it.percent * 100).toInt()) } to p?.title }
                .distinctUntilChanged()
                .collectLatest { (t, title) -> if (t != null) notify(notification(applicationContext, title, t.first, t.second, t.third)) }
        }
        try {
            val tried = HashSet<String>()
            var done = 0
            var failStreak = 0
            var hadFailure = false
            while (!isStopped) {
                val list = Offline.pending(app.library).filter { it.first !in tried }
                val (id, entry) = list.firstOrNull() ?: break
                tried += id
                if (Offline.dir(app).usableSpace < Offline.MIN_FREE_BYTES) {
                    Offline.setNotice("저장 공간이 부족해 미리 받기를 멈췄습니다. 공간을 비우면 이어서 받습니다.")
                    return@coroutineScope Result.retry()
                }
                val index = done + 1
                val total = done + list.size
                promote(notification(applicationContext, entry.title, index, total, 0))
                when (Offline.download(app, id, entry, index, total)) {
                    Offline.Outcome.OK -> { done++; failStreak = 0; Offline.setNotice(null) }
                    Offline.Outcome.SKIPPED -> failStreak = 0
                    // 연달아 두 번 실패하면 인터넷 문제일 가능성이 커서 잠시 뒤에 다시 한다
                    Offline.Outcome.FAILED -> {
                        hadFailure = true
                        if (++failStreak >= 2) return@coroutineScope Result.retry()
                    }
                }
            }
            Log.i(ClassPlayerApp.TAG, "미리 받기 작업 끝: ${done}개 받음")
            // 잠깐 실패한 것(유튜브 403·연결 끊김 등)이 남았으면 조금 뒤 다시(1분, 2분, 4분…). 3번 실패하면 건너뛴다
            if (hadFailure && Offline.pending(app.library).isNotEmpty()) Result.retry() else Result.success()
        } finally {
            notifier.cancel()
            runCatching { NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID) }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(notification(applicationContext, null, 0, 0, 0))

    /** 포그라운드로 올린다. 안드로이드 12+에서 앱이 뒤에 있으면 막힐 수 있는데, 그래도 받기는 계속한다. */
    private suspend fun promote(n: Notification) {
        runCatching { setForeground(foregroundInfo(n)) }
            .onFailure { Log.i(ClassPlayerApp.TAG, "미리 받기를 포그라운드로 못 올림(뒤에서 계속): ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun notify(n: Notification) {
        runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, n) }
    }

    private fun foregroundInfo(n: Notification) =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)

    companion object {
        private const val CHANNEL = "classplayer_offline"
        private const val NOTIFICATION_ID = 2001

        fun notification(ctx: Context, title: String?, index: Int, total: Int, percent: Int): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "미리 받기", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 21, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (total > 0) "영상 미리 받는 중 ($index/$total)" else "영상 미리 받기")
                .setContentText(title ?: "준비 중…")
                .setProgress(100, percent, total == 0)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .build()
        }
    }
}

/** 연결한 유튜브 재생목록을 확인해 새 영상을 붙이고, 받을 게 있으면 내려받기를 건다(6시간마다 + 앱을 열 때). */
class OfflineSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClassPlayerApp
        try {
            StreamResolver.awaitEngine(app)
        } catch (e: Exception) {
            return Result.retry()
        }
        Offline.syncAll(app)
        Offline.schedule(app)
        return Result.success()
    }
}
