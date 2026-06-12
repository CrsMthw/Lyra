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
import com.crsmthw.lyra.data.local.LibraryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyraForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stopJob: Job? = null
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var sleepTimerNotificationPosted = false

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(null, null, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        observeState()
        startLikedSongsFetcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = (application as LyraApplication).container.playerStateManager
        when (intent?.action) {
            ACTION_CANCEL_TIMER -> player.setSleepTimer(0)
            ACTION_PLAY_PAUSE   -> player.playPause()
            ACTION_NEXT         -> player.skipNext()
            ACTION_PREV         -> player.skipPrevious()
            ACTION_SHUFFLE      -> player.toggleShuffle()
            ACTION_REPEAT       -> player.cycleRepeat()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        notificationManager.cancel(SLEEP_TIMER_NOTIFICATION_ID)
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
                    if (state.sleepTimerMinutes > 0) {
                        val endTimeMs = System.currentTimeMillis() + state.sleepTimerMinutes * 60_000L
                        val notif = buildSleepTimerNotification(endTimeMs)
                        notificationManager.notify(SLEEP_TIMER_NOTIFICATION_ID, notif)
                        sleepTimerNotificationPosted = true
                    } else if (sleepTimerNotificationPosted) {
                        notificationManager.cancel(SLEEP_TIMER_NOTIFICATION_ID)
                        sleepTimerNotificationPosted = false
                    }
                    val shouldRun = state.isPlaying || state.sleepTimerMinutes > 0
                    if (!shouldRun) scheduleStop()
                    else { stopJob?.cancel(); stopJob = null }
                }
        }
    }

    private fun startLikedSongsFetcher() {
        scope.launch {
            val container = (application as LyraApplication).container
            // RAW (pre-filter) offset into Spotify's saved-tracks list, tracked across loop iterations.
            // Liked-songs items can have a null track (removed from Spotify) which we drop, so the
            // stored track count lags the raw offset — paginating by the stored size re-fetches the
            // gap and duplicates rows. We advance the raw offset by the raw page size instead, and
            // stop once a short/empty page signals the end (re-arming if the cache is reset under us).
            var rawOffset = -1     // -1 = seed from the cache on first run
            var prevSize  = -1     // last seen cache size, to detect an external reset (shrink)
            while (isActive) {
                delay(30_000L)
                if (container.playerStateManager.isRateLimited()) continue
                withContext(Dispatchers.IO) {
                    val cached = container.libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)
                        ?: return@withContext
                    val total = cached.snapshotId.toIntOrNull() ?: return@withContext
                    val size  = cached.tracks.size
                    // Seed on first run; re-seed when the cache shrank under us (a UI-side full
                    // replace/refresh resets the deep cache) so backfill restarts from the new prefix.
                    if (rawOffset < 0 || size < prevSize) rawOffset = size
                    prevSize = size
                    if (rawOffset >= total) return@withContext  // fully backfilled (re-arms if total grows)
                    container.spotifyRepository.getLikedSongs(limit = 50, offset = rawOffset).fold(
                        onSuccess = { resp ->
                            val rawCount = resp.items?.size ?: 0
                            if (rawCount == 0) { rawOffset = total; return@fold }  // past the end
                            rawOffset += rawCount
                            val newTracks = (resp.items ?: emptyList())
                                .mapNotNull { it.track }
                                .filter { it.isPlayable != false }
                            if (newTracks.isNotEmpty()) {
                                // distinctBy heals overlap when the seed offset started below the true
                                // raw position (e.g. cache built by an older build); steady state it's a no-op.
                                container.libraryCache.saveTrackList(
                                    LibraryCache.LIKED_SONGS_KEY,
                                    total.toString(),
                                    (cached.tracks + newTracks).distinctBy { it.id },
                                )
                            }
                            if (rawCount < 50) rawOffset = total  // short page = last page reached
                        },
                        onFailure = { e ->
                            if (e.message?.contains("429") == true) {
                                container.playerStateManager.noteRateLimited()
                            }
                        },
                    )
                }
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

    private fun buildSleepTimerNotification(endTimeMs: Long): android.app.Notification {
        val timeStr = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(endTimeMs))
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, LyraForegroundService::class.java).apply { action = ACTION_CANCEL_TIMER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SLEEP_TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_sleep_timer_title))
            .setContentText(getString(R.string.notification_sleep_timer_stopping_at, timeStr))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(endTimeMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setSilent(true)
            .setRequestPromotedOngoing(true)
            .addAction(0, getString(R.string.notification_sleep_timer_cancel), cancelIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID                  = "lyra_player"
        const val SLEEP_TIMER_CHANNEL_ID      = "lyra_sleep_timer"
        const val NOTIFICATION_ID             = 1
        const val SLEEP_TIMER_NOTIFICATION_ID = 2
        const val ACTION_CANCEL_TIMER         = "com.crsmthw.lyra.CANCEL_TIMER"
        const val ACTION_PLAY_PAUSE           = "com.crsmthw.lyra.PLAY_PAUSE"
        const val ACTION_NEXT                 = "com.crsmthw.lyra.NEXT"
        const val ACTION_PREV                 = "com.crsmthw.lyra.PREV"
        const val ACTION_SHUFFLE              = "com.crsmthw.lyra.SHUFFLE"
        const val ACTION_REPEAT               = "com.crsmthw.lyra.REPEAT"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    SLEEP_TIMER_CHANNEL_ID,
                    context.getString(R.string.notification_sleep_timer_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_sleep_timer_channel_desc)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }
}
