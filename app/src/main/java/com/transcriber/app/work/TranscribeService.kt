package com.transcriber.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.transcriber.app.MainActivity
import com.transcriber.app.R
import com.transcriber.app.app
import com.transcriber.app.data.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Держит процесс живым, пока идёт распознавание, и показывает прогресс в уведомлении. */
class TranscribeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Ход распознавания", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Готовые расшифровки", NotificationManager.IMPORTANCE_DEFAULT))
        goForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transcriber:work")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progressNotification("Транскрибатор", "Подготовка…", -1f),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun observe() {
        if (started) return
        started = true
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            var prevActive = emptySet<String>()
            app.jobs.jobs.collect { list ->
                val active = list.filter { it.isActive }
                val nowIds = active.map { it.id }.toSet()
                for (id in prevActive - nowIds) {
                    val j = list.firstOrNull { it.id == id } ?: continue
                    val title = when (j.status) {
                        Status.DONE -> "Готово"
                        Status.AWAITING -> "Транскрипт готов — нужен ответ Claude"
                        Status.CANCELLED -> "Отменено"
                        else -> "Ошибка"
                    }
                    nm.notify(id.hashCode(), doneNotification(title, j.name, id))
                }
                prevActive = nowIds
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@TranscribeService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val j = active.firstOrNull { it.status != Status.QUEUED } ?: active.first()
                    val extra = if (active.size > 1) " (+${active.size - 1} в очереди)" else ""
                    nm.notify(ID_PROGRESS, progressNotification(j.name + extra, j.message, if (j.status == Status.QUEUED) -1f else j.progress))
                    delay(700)
                }
            }
        }
    }

    private fun openIntent(jobId: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (jobId != null) putExtra(MainActivity.EXTRA_JOB, jobId)
        }
        return PendingIntent.getActivity(this, jobId?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressNotification(title: String, text: String, progress: Float): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(null))
            .apply {
                if (progress < 0) setProgress(0, 0, true) else setProgress(1000, (progress * 1000).toInt(), false)
            }
            .build()

    private fun doneNotification(title: String, text: String, jobId: String): Notification =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent(jobId))
            .build()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val ID_PROGRESS = 1
    }
}
