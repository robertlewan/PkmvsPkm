package com.pkmvspkm.game

import android.content.Context

/** Remembers which levels the player has unlocked between sessions. */
object SaveData {
    private const val PREFS = "pkmvspkm_save"
    private const val KEY_UNLOCKED = "unlocked_level"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Highest level id the player may play (starts at 1). */
    fun unlockedLevel(context: Context): Int =
        prefs(context).getInt(KEY_UNLOCKED, 1)

    fun unlockNext(context: Context, finishedLevelId: Int) {
        val next = minOf(finishedLevelId + 1, LEVELS.size)
        if (next > unlockedLevel(context)) {
            prefs(context).edit().putInt(KEY_UNLOCKED, next).apply()
        }
    }
}
