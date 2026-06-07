package com.crsmthw.lyra

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.service.LyraForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LyraApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        LyraForegroundService.createChannels(this)
        container = AppContainer(this)
        observePlayerForWidget()
    }

    /**
     * App-scoped collector that keeps the home-screen widget in sync. Keyed on only the fields the
     * widget renders (track / play / shuffle / repeat) so the 1 s progress tick doesn't churn it.
     * Lives for the process lifetime — covers both the foreground service and any UI being open;
     * when the process is dead the widget simply shows the last persisted snapshot.
     */
    private fun observePlayerForWidget() {
        val player = container.playerStateManager
        appScope.launch {
            player.state
                .map { Triple(it.currentTrack?.id, it.isPlaying, it.shuffleEnabled to it.repeatState) }
                .distinctUntilChanged()
                .collect {
                    // A failure here must not kill the collector — otherwise one bad update freezes
                    // the widget at its last state for the rest of the process lifetime.
                    runCatching { container.nowPlayingWidgetUpdater.update(player.state.value) }
                        .onFailure { android.util.Log.e("LyraWidget", "update failed", it) }
                }
        }
    }

    /** One-shot widget refresh from the current player state (used when a widget is first added). */
    fun refreshWidget() {
        appScope.launch {
            container.nowPlayingWidgetUpdater.update(container.playerStateManager.state.value)
        }
    }

    override fun newImageLoader(context: Context): ImageLoader = container.imageLoader
}
