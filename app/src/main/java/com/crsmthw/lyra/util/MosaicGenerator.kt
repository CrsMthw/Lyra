package com.crsmthw.lyra.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import java.io.File

class MosaicGenerator(
    private val imageLoader: ImageLoader,
    private val context    : Context,
) {
    val dir: File = File(context.filesDir, "mosaics").also { it.mkdirs() }

    fun getFile(playlistId: String): File = File(dir, "$playlistId.png")

    fun existingIds(): Set<String> =
        dir.listFiles()?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    suspend fun generate(playlistId: String, tracks: List<SpotifyTrack>): Boolean {
        val urls = tracks.mapNotNull { it.artUrl.takeIf { u -> u.isNotBlank() } }.distinct().take(4)
        if (urls.isEmpty()) return false

        val sizePx = 400
        val half   = sizePx / 2

        // Fetch bitmaps via Coil (uses disk cache, rarely hits network)
        val sourceBitmaps = urls.mapNotNull { url ->
            runCatching {
                val req = ImageRequest.Builder(context).data(url).size(half, half).build()
                (imageLoader.execute(req) as? SuccessResult)?.image?.let { it as? BitmapImage }?.bitmap
            }.getOrNull()
        }
        if (sourceBitmaps.isEmpty()) return false

        // Canvas requires software bitmaps; copy hardware bitmaps if needed
        val softBitmaps = sourceBitmaps.map { src ->
            if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        }

        val cells = listOf(
            RectF(0f,             0f,             half.toFloat(), half.toFloat()),
            RectF(half.toFloat(), 0f,             sizePx.toFloat(), half.toFloat()),
            RectF(0f,             half.toFloat(), half.toFloat(), sizePx.toFloat()),
            RectF(half.toFloat(), half.toFloat(), sizePx.toFloat(), sizePx.toFloat()),
        )

        val result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        if (softBitmaps.size == 1) {
            canvas.drawBitmap(softBitmaps[0], null, RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), null)
        } else {
            cells.forEachIndexed { i, rect ->
                canvas.drawBitmap(softBitmaps[i % softBitmaps.size], null, rect, null)
            }
        }

        runCatching {
            getFile(playlistId).outputStream().use { out ->
                result.compress(Bitmap.CompressFormat.PNG, 85, out)
            }
        }

        result.recycle()
        // Recycle only copies we made; never recycle Coil-owned bitmaps
        softBitmaps.forEachIndexed { i, bmp ->
            if (bmp !== sourceBitmaps[i]) bmp.recycle()
        }

        return getFile(playlistId).exists()
    }
}
