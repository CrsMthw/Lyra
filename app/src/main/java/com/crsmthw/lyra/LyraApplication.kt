package com.crsmthw.lyra

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.service.LyraForegroundService

class LyraApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LyraForegroundService.createChannel(this)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = container.imageLoader
}
