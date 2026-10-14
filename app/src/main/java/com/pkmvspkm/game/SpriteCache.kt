package com.pkmvspkm.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads official Pokemon sprites from the PokeAPI sprite repository.
 *
 * Sprites are downloaded on a background thread the first time they are needed,
 * kept in memory, and [onLoaded] is called on the main thread so the view can redraw.
 * Until a sprite arrives, [get] returns null and the caller draws a placeholder.
 */
object SpriteCache {
    private const val BASE_URL =
        "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/"

    private val bitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun get(spriteId: Int, onLoaded: () -> Unit): Bitmap? {
        bitmaps[spriteId]?.let { return it }
        if (pending.add(spriteId)) {
            executor.execute {
                try {
                    val stream = URL("$BASE_URL$spriteId.png").openStream()
                    val bitmap = stream.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        bitmaps[spriteId] = bitmap
                        mainHandler.post { onLoaded() }
                    }
                } catch (e: Exception) {
                    // Network failed: allow a retry on the next request.
                    pending.remove(spriteId)
                }
            }
        }
        return null
    }
}
