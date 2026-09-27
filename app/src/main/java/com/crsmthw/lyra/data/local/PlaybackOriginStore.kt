package com.crsmthw.lyra.data.local

import android.content.Context
import com.crsmthw.lyra.data.player.PlaybackOrigin
import com.crsmthw.lyra.data.player.PlaybackOriginRecord
import com.google.gson.Gson
import java.io.File

/**
 * The last [PlaybackOrigin] — one small JSON file in filesDir, so the play button can rebuild the
 * queue after Spotify died, even across a Lyra restart. Same style as [LibraryCache]'s writers:
 * every read and write holds one lock, and a failure is swallowed (a missing or unreadable file
 * means "no origin known", which the restore plan handles). Callers run it on `Dispatchers.IO`.
 */
class PlaybackOriginStore(context: Context) {

    private val file = File(context.filesDir, "playback_origin.json")
    private val gson = Gson()
    private val lock = Any()

    fun save(origin: PlaybackOrigin) = synchronized(lock) {
        runCatching { file.writeText(gson.toJson(origin.toRecord())) }
    }

    fun load(): PlaybackOrigin? = synchronized(lock) {
        runCatching {
            if (!file.exists()) return@runCatching null
            gson.fromJson(file.readText(), PlaybackOriginRecord::class.java)?.toOrigin()
        }.getOrNull()
    }
}
