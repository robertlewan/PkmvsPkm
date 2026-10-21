package com.pkmvspkm.game

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** What a finished stage gave the player. */
class StageReward(val stars: Int, val gained: Int, val recruits: List<DefenderType>)

/**
 * Saved player progress: stars, best rating per stage, the roster with upgrade levels,
 * and the loadout picked for the next stage. Stored as one JSON string in SharedPreferences.
 */
object Progress {
    private const val PREFS = "pkmvspkm_save"
    private const val KEY = "progress_v2"
    private const val MAX_LOADOUT = 5

    val STARTERS = listOf(
        DefenderType.BULBASAUR,
        DefenderType.CHARMANDER,
        DefenderType.SQUIRTLE,
        DefenderType.PIKACHU,
    )

    /** Stars spent on upgrades. Earned by clearing stages. */
    var stars = 0
        private set

    /** Best star rating per stage id. 0 means not cleared yet. */
    private val best = HashMap<Int, Int>()
    private val roster = LinkedHashMap<DefenderType, Upgrades>()
    /** Every Pokemon ever obtained, so replaying a stage does not recruit it again. */
    private val discovered = HashSet<DefenderType>()

    /** The Pokemon chosen for the next stage. */
    var loadout: List<DefenderType> = STARTERS.toList()
        private set

    private var prefs: SharedPreferences? = null

    /** Loads saved progress once. Safe to call more than once. */
    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null)
        if (raw == null) {
            resetToStarters()
            return
        }
        try {
            val json = JSONObject(raw)
            stars = json.optInt("stars", 0)

            val bestJson = json.getJSONObject("best")
            bestJson.keys().forEach { best[it.toInt()] = bestJson.getInt(it) }

            val rosterJson = json.getJSONObject("roster")
            rosterJson.keys().forEach { name ->
                val a = rosterJson.getJSONArray(name)
                roster[DefenderType.valueOf(name)] = Upgrades(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
            }

            val discoveredJson = json.getJSONArray("discovered")
            for (i in 0 until discoveredJson.length()) {
                discovered.add(DefenderType.valueOf(discoveredJson.getString(i)))
            }

            val loadoutJson = json.getJSONArray("loadout")
            loadout = (0 until loadoutJson.length())
                .map { DefenderType.valueOf(loadoutJson.getString(it)) }
                .filter { roster.containsKey(it) }
                .distinct()
                .take(MAX_LOADOUT)
        } catch (e: Exception) {
            resetToStarters()
        }
        if (loadout.isEmpty()) loadout = STARTERS.filter { roster.containsKey(it) }
    }

    private fun resetToStarters() {
        stars = 0
        best.clear()
        roster.clear()
        discovered.clear()
        for (t in STARTERS) {
            roster[t] = Upgrades()
            discovered.add(t)
        }
        loadout = STARTERS.toList()
    }

    /** A stage is open once the stage before it has been cleared. The first stage is always open. */
    fun isUnlocked(stageId: Int): Boolean = stageId == 1 || bestStars(stageId - 1) > 0

    fun bestStars(stageId: Int): Int = best[stageId] ?: 0

    fun owned(): List<DefenderType> = roster.keys.toList()

    fun owns(type: DefenderType): Boolean = roster.containsKey(type)

    fun upgradesOf(type: DefenderType): Upgrades = roster[type] ?: Upgrades()

    /**
     * Stars needed for the next upgrade, given how many levels the Pokemon already has.
     * Levels 1-3 cost 1 star, 4-6 cost 2, and 7-10 cost 3, so maxing one Pokemon takes 21 stars.
     */
    fun upgradeCost(total: Int): Int = when {
        total < 3 -> 1
        total < 6 -> 2
        else -> 3
    }

    fun canUpgrade(type: DefenderType): Boolean {
        val up = roster[type] ?: return false
        return up.total < MAX_LEVEL && stars >= upgradeCost(up.total)
    }

    /** Spends stars on one upgrade. Stat index: 0 = HP, 1 = power, 2 = tempo, 3 = special. */
    fun upgrade(type: DefenderType, stat: Int): Boolean {
        if (!canUpgrade(type)) return false
        val up = roster.getValue(type)
        stars -= upgradeCost(up.total)
        when (stat) {
            0 -> up.hp++
            1 -> up.power++
            2 -> up.tempo++
            else -> up.special++
        }
        save()
        return true
    }

    fun canEvolve(type: DefenderType): Boolean {
        val up = roster[type] ?: return false
        return EVOLUTIONS.containsKey(type) && up.total >= EVOLVE_LEVEL
    }

    /** Swaps a Pokemon for its evolution. Upgrade levels carry over. */
    fun evolve(type: DefenderType): Boolean {
        if (!canEvolve(type)) return false
        val to = EVOLUTIONS.getValue(type)
        val up = roster.remove(type) ?: return false
        roster[to] = up
        discovered.add(to)
        loadout = loadout.map { if (it == type) to else it }
        save()
        return true
    }

    fun setLoadout(picks: List<DefenderType>) {
        loadout = picks.filter { roster.containsKey(it) }.distinct().take(MAX_LOADOUT)
        save()
    }

    /** 3 stars with no candy stolen, 2 stars for one or two, 1 star for three or more. A win is always at least 1 star. */
    fun starsFor(candyStolen: Int): Int = when {
        candyStolen == 0 -> 3
        candyStolen <= 2 -> 2
        else -> 1
    }

    /**
     * Applies the result of a stage. Only a better rating than before pays out stars,
     * so replaying a stage cannot be farmed. Winning recruits the stage's Pokemon the first time.
     */
    fun finishStage(level: LevelDef, won: Boolean, candyStolen: Int): StageReward {
        if (!won) return StageReward(0, 0, emptyList())
        val earned = starsFor(candyStolen)
        val previous = bestStars(level.id)
        val gained = max(0, earned - previous)
        if (earned > previous) best[level.id] = earned
        stars += gained
        val recruits = level.recruits.filter { !discovered.contains(it) }
        for (t in recruits) {
            discovered.add(t)
            roster[t] = Upgrades()
        }
        save()
        return StageReward(earned, gained, recruits)
    }

    private fun save() {
        val p = prefs ?: return
        val json = JSONObject()
        json.put("stars", stars)

        val bestJson = JSONObject()
        for ((k, v) in best) bestJson.put(k.toString(), v)
        json.put("best", bestJson)

        val rosterJson = JSONObject()
        for ((k, u) in roster) {
            rosterJson.put(k.name, JSONArray(listOf(u.hp, u.power, u.tempo, u.special)))
        }
        json.put("roster", rosterJson)

        json.put("discovered", JSONArray(discovered.map { it.name }))
        json.put("loadout", JSONArray(loadout.map { it.name }))
        p.edit().putString(KEY, json.toString()).apply()
    }
}
