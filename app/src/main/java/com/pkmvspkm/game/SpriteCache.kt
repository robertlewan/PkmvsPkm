package com.pkmvspkm.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads official sprites from the PokeAPI sprite repository.
 *
 * Sprites are downloaded on a background thread the first time they are needed,
 * kept in memory, and [onLoaded] is called on the main thread so the view can redraw.
 * Until a sprite arrives, the getter returns null and the caller draws a placeholder.
 */
object SpriteCache {
    private const val POKEMON_URL =
        "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/"
    private const val ITEM_URL =
        "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/items/"

    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Official Pokemon artwork by National Pokedex number. */
    fun get(spriteId: Int, onLoaded: () -> Unit): Bitmap? =
        load("pokemon/$spriteId", "$POKEMON_URL$spriteId.png", onLoaded)

    /** Official item artwork by PokeAPI item name, e.g. "rare-candy". */
    fun getItem(name: String, onLoaded: () -> Unit): Bitmap? =
        load("item/$name", "$ITEM_URL$name.png", onLoaded)

    private fun load(key: String, url: String, onLoaded: () -> Unit): Bitmap? {
        bitmaps[key]?.let { return it }
        if (pending.add(key)) {
            executor.execute {
                try {
                    val stream = URL(url).openStream()
                    val bitmap = stream.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        bitmaps[key] = bitmap
                        mainHandler.post { onLoaded() }
                    }
                } catch (e: Exception) {
                    // Network failed: allow a retry on the next request.
                    pending.remove(key)
                }
            }
        }
        return null
    }
}
