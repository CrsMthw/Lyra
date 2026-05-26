package com.crsmthw.lyra.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.crsmthw.lyra.LyraApplication
import com.crsmthw.lyra.MainActivity
import com.crsmthw.lyra.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class LyraForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stopJob: Job? = null
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(null, null, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TIMER) {
            (application as LyraApplication).container.playerStateManager.setSleepTimer(0)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        (application as LyraApplication).container.playerStateManager.notifyServiceStopped()
        super.onDestroy()
    }

    private fun observeState() {
        val manager = (application as LyraApplication).container.playerStateManager
        scope.launch {
            manager.state
                .distinctUntilChangedBy { Triple(it.currentTrack?.id, it.sleepTimerMinutes, it.isPlaying) }
                .collect { state ->
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            trackName         = state.currentTrack?.name,
                            artistName        = state.currentTrack?.artists?.firstOrNull()?.name,
                            sleepTimerMinutes = state.sleepTimerMinutes,
                        ),
                    )
                    val shouldRun = state.isPlaying || state.sleepTimerMinutes > 0
                    if (!shouldRun) scheduleStop()
                    else { stopJob?.cancel(); stopJob = null }
                }
        }
    }

    private fun scheduleStop() {
        if (stopJob?.isActive == true) return
        stopJob = scope.launch {
            delay(10_000L)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(
        trackName: String?,
        artistName: String?,
        sleepTimerMinutes: Int,
    ): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            trackName != null && artistName != null -> "$trackName · $artistName"
            trackName != null -> trackName
            else -> getString(R.string.app_name)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (sleepTimerMinutes > 0) {
            val cancelIntent = PendingIntent.getService(
                this, 1,
                Intent(this, LyraForegroundService::class.java).apply {
                    action = ACTION_CANCEL_TIMER
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                0,
                getString(R.string.notification_cancel_timer, sleepTimerMinutes),
                cancelIntent,
            )
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID          = "lyra_player"
        const val NOTIFICATION_ID     = 1
        const val ACTION_CANCEL_TIMER = "com.crsmthw.lyra.CANCEL_TIMER"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
