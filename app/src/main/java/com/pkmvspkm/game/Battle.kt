package com.pkmvspkm.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// Grid units: a column/row is 1.0 wide. Enemies use fractional x in column units.
const val ROWS = 5
const val COLS = 9
const val SUN_VALUE = 25
private const val PROJECTILE_SPEED = 4f   // columns per second
private const val ENEMY_SHOT_SPEED = 2.5f // columns per second
private const val FALL_SPEED = 1.2f       // rows per second
private const val HIT_FLASH = 0.12f       // seconds
private const val SPAWN_SLOWDOWN = 1.3f   // multiplies level spawn intervals; higher = fewer enemies
private const val GAME_SPEED = 0.75f      // global speed of the battle; lower = slower movement, shots, and timers
private const val SUN_HOVER_TIME = 0.5f   // seconds a candy waits on the ground before flying to the HUD
private const val PICKUP_TIME = 0.6f      // seconds for a candy to fly from the board to the HUD
private const val BURN_DPS = 8f           // damage per second while burning

/** Anything the player can pick from the card bar. */
interface Card {
    val label: String
    val cost: Int
    val spriteId: Int
    val info: String
}

/** Status effect a shot applies to the enemy it hits. */
enum class ShotEffect { NONE, BURN, SLOW }

enum class DefenderType(
    override val label: String,
    override val cost: Int,
    val hp: Float,
    val color: Int,
    /** Seconds between shots. 0 = does not shoot. */
    val shotInterval: Float,
    val damage: Float,
    /** Seconds between sun drops. 0 = does not produce sun. */
    val sunInterval: Float,
    /** National Pokedex number, used to load the official sprite. */
    override val spriteId: Int,
    /** Shown in the Pokedex and in the battle banner when the card is selected. */
    override val info: String,
    val shotEffect: ShotEffect = ShotEffect.NONE,
    /** Extra enemies each shot can pass through. */
    val pierce: Int = 0,
    /** HP per second healed to defenders within [healRange] columns in the same row, itself included. */
    val healPerSecond: Float = 0f,
    val healRange: Int = 0,
) : Card {
    FLOETTE(
        "Floette", 50, 120f, 0xFFF48FB1.toInt(), 0f, 0f, 9f, 670,
        "Flower Power: makes 25 rare candy every 9 seconds. Heals defenders within 2 columns in its row for 12 HP/s. Does not attack.",
        healPerSecond = 12f, healRange = 2,
    ),
    PIKACHU(
        "Pikachu", 100, 150f, 0xFFFDD835.toInt(), 1.4f, 20f, 0f, 25,
        "Thunder Chain: 20-damage shots pierce through one extra enemy in the row.",
        pierce = 1,
    ),
    CHARMANDER(
        "Charmander", 125, 150f, 0xFFFF7043.toInt(), 1.8f, 30f, 0f, 4,
        "Ember: 30-damage shots set the enemy on fire, dealing 8 damage per second for 3 seconds.",
        shotEffect = ShotEffect.BURN,
    ),
    SQUIRTLE(
        "Squirtle", 75, 150f, 0xFF4FC3F7.toInt(), 1.6f, 15f, 0f, 7,
        "Water Gun: 15-damage shots halve the enemy's speed for 2 seconds. Cheap, and great against fast enemies.",
        shotEffect = ShotEffect.SLOW,
    ),
    SNORLAX(
        "Snorlax", 150, 900f, 0xFF607D8B.toInt(), 0f, 0f, 0f, 143,
        "Thick Fat and Rest: 900 HP, regenerates 10 HP/s, and blocks enemies to protect the shooters behind it.",
        healPerSecond = 10f, healRange = 0,
    ),
}

/** One-use legendary helpers. Tapping the card casts the ability on the board. */
enum class LegendType(
    override val label: String,
    override val cost: Int,
    override val spriteId: Int,
    override val info: String,
    /** Screen tint shown when the ability fires. */
    val tint: Int,
) : Card {
    ARTICUNO(
        "Articuno", 250, 144,
        "Legendary, one use per battle. Blizzard freezes every enemy on the board for 5 seconds. Frozen enemies cannot move, bite, or attack.",
        0xFF7FD8FF.toInt(),
    ),
    HO_OH(
        "Ho-Oh", 300, 250,
        "Legendary, one use per battle. Sacred Fire deals 150 damage to every enemy on the board.",
        0xFFFFC107.toInt(),
    ),
    MEWTWO(
        "Mewtwo", 350, 150,
        "Legendary, one use per battle. Psystrike deals 500 damage to the strongest enemy on the board.",
        0xFFFF6FB5.toInt(),
    ),
}

/** Cards shown in the card bar: defenders first, then legendary helpers. */
val ALL_CARDS: List<Card> = listOf(*DefenderType.entries.toTypedArray(), *LegendType.entries.toTypedArray())

enum class EnemyType(
    val label: String,
    val hp: Float,
    /** Columns per second. */
    val speed: Float,
    /** Damage per second to a defender it is blocked by. */
    val dps: Float,
    val color: Int,
    /** National Pokedex number, used to load the official sprite. */
    val spriteId: Int,
    /** Shown in the Pokedex. */
    val info: String,
    val boss: Boolean = false,
    /** Flies over defenders instead of being blocked by them. */
    val flies: Boolean = false,
    /** Fraction of incoming shot damage that is blocked, 0..1. */
    val armor: Float = 0f,
    /** Fraction of bite damage that heals this enemy. */
    val lifesteal: Float = 0f,
    /** Extra damage on the first hit against each defender it blocks. */
    val burst: Float = 0f,
    /** Seconds between sun thefts. 0 = never steals. */
    val stealInterval: Float = 0f,
    val stealAmount: Int = 0,
    /** Seconds between ranged shadow balls. 0 = melee only. */
    val rangedInterval: Float = 0f,
    val rangedDamage: Float = 0f,
) {
    ZUBAT(
        "Zubat", 100f, 0.7f, 20f, 0xFF7E57C2.toInt(), 41,
        "Leech Life: heals itself for half the damage it bites. Fast and fragile, but tough to finish once it heals.",
        lifesteal = 0.5f,
    ),
    MURKROW(
        "Murkrow", 180f, 0.45f, 30f, 0xFF37474F.toInt(), 198,
        "Flying: it flies over the first defender in its lane without stopping, so walls cannot block it.",
        flies = true,
    ),
    SABLEYE(
        "Sableye", 320f, 0.35f, 40f, 0xFF8E24AA.toInt(), 302,
        "Shadow Armor: takes 50% less damage from shots. Gem Thief: steals 25 rare candy from you every 10 seconds.",
        armor = 0.5f, stealInterval = 10f, stealAmount = 25,
    ),
    WEAVILE(
        "Weavile", 260f, 0.9f, 35f, 0xFF1565C0.toInt(), 461,
        "Night Slash: the first time it reaches each defender it slashes for 45 extra damage. Very fast.",
        burst = 45f,
    ),
    GENGAR(
        "Gengar", 1200f, 0.22f, 90f, 0xFF4A148C.toInt(), 94,
        "Boss. Shadow Ball: fires a 50-damage shadow ball every 4 seconds at defenders in its lane. Slow but deadly.",
        boss = true, rangedInterval = 4f, rangedDamage = 50f,
    ),
}

class Defender(val type: DefenderType, val row: Int, val col: Int) {
    var hp = type.hp
    var timer = 0f
    var dead = false
    /** Seconds left on the white hit flash when this defender takes damage. */
    var hitFlash = 0f
}

class Enemy(val type: EnemyType, val row: Int, var x: Float) {
    var hp = type.hp
    var dead = false
    var hitFlash = 0f
    /** Timer for the enemy's ability (sun theft or ranged attack). */
    var timer = 0f
    /** True once the enemy has used its first-contact burst. */
    var pounced = false
    /** Seconds left frozen by Articuno. Frozen enemies do nothing. */
    var freezeTimer = 0f
    /** Seconds left at half speed (Squirtle). */
    var slowTimer = 0f
    /** Seconds left burning (Charmander). */
    var burnTimer = 0f
}

class Projectile(
    val row: Int,
    var x: Float,
    val damage: Float,
    val effect: ShotEffect = ShotEffect.NONE,
    /** Extra enemies this shot can pass through. */
    var pierce: Int = 0,
) {
    var dead = false
    /** Enemies already hit by this shot, so a piercing shot never hits one twice. */
    val hit = mutableListOf<Enemy>()
}

/** A shadow ball fired by an enemy. It travels left and hits the first defender in its row. */
class EnemyShot(val row: Int, var x: Float, val damage: Float) {
    var dead = false
}

/**
 * A rare candy drop on the board. [fallTo] is the row it lands on, or null if it was dropped by a defender.
 * It is collected on its own after landing, so the player does not have to tap it.
 */
class Sun(var x: Float, var y: Float, val fallTo: Float?) {
    var dead = false
    /** Seconds spent on the ground before it is collected. */
    var hover = 0f
}

/** Candy flying from the board to the HUD counter. The count goes up when it arrives. */
class Pickup(val x: Float, val y: Float) {
    /** 0 at the board, 1 at the HUD. */
    var progress = 0f
    var dead = false
}

enum class Outcome { RUNNING, WON, LOST }

class Battle(val level: LevelDef, private val rng: Random = Random.Default) {
    val defenders = mutableListOf<Defender>()
    val enemies = mutableListOf<Enemy>()
    val projectiles = mutableListOf<Projectile>()
    val enemyShots = mutableListOf<EnemyShot>()
    val suns = mutableListOf<Sun>()
    val pickups = mutableListOf<Pickup>()

    /** One-use lane clearer per row (the lawnmower equivalent). */
    val trapReady = BooleanArray(ROWS) { true }
    /** Seconds left on the trap flash animation per row. */
    val trapFlash = FloatArray(ROWS)

    /** Legendary helpers already cast in this battle. */
    val usedLegends = mutableSetOf<LegendType>()
    /** Legendary effect playing right now, for the screen flash. */
    var legendEffect: LegendType? = null
        private set
    var legendTimer = 0f
        private set

    /** Short event text shown in the battle banner, e.g. "Sableye stole 25 rare candy!". */
    var message = ""
        private set
    var messageTimer = 0f
        private set

    var sun = level.startSun
        private set
    var outcome = Outcome.RUNNING
        private set
    var spawnedCount = 0
        private set

    private var spawnTimer = 3f
    private var sunTimer = 3f

    /** Advances the battle by [frameDt] real seconds, scaled by [GAME_SPEED] so everything runs a bit slower. */
    fun update(frameDt: Float) {
        if (outcome != Outcome.RUNNING) return
        val dt = frameDt * GAME_SPEED
        updateSuns(dt)
        updatePickups(dt)
        updateDefenders(dt)
        updateProjectiles(dt)
        updateEnemyShots(dt)
        updateEnemies(dt)
        updateSpawns(dt)
        for (r in 0 until ROWS) trapFlash[r] = max(0f, trapFlash[r] - dt)
        for (d in defenders) d.hitFlash = max(0f, d.hitFlash - dt)
        for (e in enemies) e.hitFlash = max(0f, e.hitFlash - dt)
        messageTimer = max(0f, messageTimer - dt)
        legendTimer = max(0f, legendTimer - dt)

        defenders.removeAll { it.dead }
        enemies.removeAll { it.dead }
        projectiles.removeAll { it.dead }
        enemyShots.removeAll { it.dead }
        suns.removeAll { it.dead }
        pickups.removeAll { it.dead }

        if (outcome == Outcome.RUNNING && spawnedCount >= level.count && enemies.isEmpty()) {
            outcome = Outcome.WON
        }
    }

    /** Returns the sun under the given grid-unit point, if any. */
    fun sunAt(x: Float, y: Float): Sun? =
        suns.firstOrNull { !it.dead && abs(it.x - x) < 0.8f && abs(it.y - y) < 0.8f }

    /** Taps a candy on the board to grab it early. */
    fun collect(s: Sun) {
        if (s.dead) return
        s.dead = true
        pickups.add(Pickup(s.x, s.y))
    }

    /** True when the card can be used right now (enough sun, and not a spent legend). */
    fun canUse(card: Card): Boolean = when (card) {
        is LegendType -> outcome == Outcome.RUNNING && card !in usedLegends && sun >= card.cost
        is DefenderType -> outcome == Outcome.RUNNING && sun >= card.cost
        else -> false
    }

    fun place(type: DefenderType, row: Int, col: Int): Boolean {
        if (!canUse(type)) return false
        if (defenders.any { it.row == row && it.col == col }) return false
        sun -= type.cost
        defenders.add(Defender(type, row, col))
        return true
    }

    /** Casts a legendary helper on the whole board. Returns false if it cannot be used now. */
    fun summon(type: LegendType): Boolean {
        if (!canUse(type)) return false
        sun -= type.cost
        usedLegends.add(type)
        legendEffect = type
        legendTimer = 1.2f

        when (type) {
            LegendType.ARTICUNO -> {
                enemies.forEach { if (!it.dead) it.freezeTimer = 5f }
                say("Articuno froze every enemy!")
            }
            LegendType.HO_OH -> {
                enemies.toList().forEach { damageEnemy(it, 150f) }
                say("Ho-Oh's Sacred Fire scorched every foe!")
            }
            LegendType.MEWTWO -> {
                enemies.filter { !it.dead }.maxByOrNull { it.hp }?.let { damageEnemy(it, 500f) }
                say("Mewtwo's Psystrike hit the strongest foe!")
            }
        }
        return true
    }

    private fun damageEnemy(e: Enemy, amount: Float) {
        if (e.dead) return
        e.hp -= amount
        e.hitFlash = HIT_FLASH
        if (e.hp <= 0f) e.dead = true
    }

    private fun say(text: String) {
        message = text
        messageTimer = 2.5f
    }

    private fun updateSuns(dt: Float) {
        sunTimer -= dt
        if (sunTimer <= 0f) {
            sunTimer = 4.5f + rng.nextFloat() * 2f
            suns.add(Sun(rng.nextInt(COLS) + 0.5f, -0.5f, rng.nextInt(ROWS) + 0.5f))
        }
        for (s in suns) {
            if (s.dead) continue
            val target = s.fallTo
            if (target != null && s.y < target) s.y = min(target, s.y + FALL_SPEED * dt)
            // Once it has landed (or was dropped by a defender), it waits briefly and then flies to the HUD
            if (target == null || s.y >= target) {
                s.hover += dt
                if (s.hover >= SUN_HOVER_TIME) collect(s)
            }
        }
    }

    private fun updatePickups(dt: Float) {
        for (p in pickups) {
            if (p.dead) continue
            p.progress += dt / PICKUP_TIME
            if (p.progress >= 1f) {
                p.progress = 1f
                p.dead = true
                sun += SUN_VALUE
            }
        }
    }

    private fun updateDefenders(dt: Float) {
        for (d in defenders) {
            if (d.dead) continue
            d.timer += dt

            // Healing (Chansey, Snorlax)
            if (d.type.healPerSecond > 0f) {
                for (ally in defenders) {
                    if (!ally.dead && ally.row == d.row && abs(ally.col - d.col) <= d.type.healRange) {
                        ally.hp = min(ally.type.hp, ally.hp + d.type.healPerSecond * dt)
                    }
                }
            }

            if (d.type.sunInterval > 0f && d.timer >= d.type.sunInterval) {
                d.timer = 0f
                suns.add(Sun(d.col + 0.5f, d.row + 0.5f, null))
            }

            val shooting = d.type.shotInterval > 0f && d.timer >= d.type.shotInterval
            if (shooting && enemies.any { it.row == d.row && it.x > d.col }) {
                d.timer = 0f
                projectiles.add(Projectile(d.row, d.col + 0.8f, d.type.damage, d.type.shotEffect, d.type.pierce))
            }
        }
    }

    private fun updateProjectiles(dt: Float) {
        for (p in projectiles) {
            p.x += PROJECTILE_SPEED * dt
            if (p.x > COLS + 1f) {
                p.dead = true
                continue
            }
            val target = enemies.firstOrNull {
                !it.dead && it.row == p.row && it !in p.hit && abs(it.x + 0.4f - p.x) < 0.5f
            }
            if (target != null) {
                // Sableye's armor reduces shot damage
                target.hp -= p.damage * (1f - target.type.armor)
                target.hitFlash = HIT_FLASH
                when (p.effect) {
                    ShotEffect.BURN -> target.burnTimer = 3f
                    ShotEffect.SLOW -> target.slowTimer = 2f
                    ShotEffect.NONE -> Unit
                }
                if (target.hp <= 0f) target.dead = true
                p.hit.add(target)
                if (p.pierce > 0) p.pierce-- else p.dead = true
            }
        }
    }

    private fun updateEnemyShots(dt: Float) {
        for (s in enemyShots) {
            s.x -= ENEMY_SHOT_SPEED * dt
            if (s.x < -0.5f) {
                s.dead = true
                continue
            }
            val col = floor(s.x).toInt()
            val target = defenders.firstOrNull { !it.dead && it.row == s.row && it.col == col }
            if (target != null) {
                target.hp -= s.damage
                target.hitFlash = HIT_FLASH
                if (target.hp <= 0f) target.dead = true
                s.dead = true
            }
        }
    }

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (e.dead) continue
            val t = e.type

            // Burning hurts every frame until it runs out
            if (e.burnTimer > 0f) {
                e.burnTimer -= dt
                e.hp -= BURN_DPS * dt
                if (e.hp <= 0f) {
                    e.dead = true
                    continue
                }
            }

            if (e.x < 0f) {
                // Reached the house: the lane's trap clears the whole lane once, otherwise you lose.
                if (!trapReady[e.row]) {
                    outcome = Outcome.LOST
                    return
                }
                trapReady[e.row] = false
                trapFlash[e.row] = 0.8f
                enemies.forEach { if (it.row == e.row) it.dead = true }
                continue
            }

            // Frozen (Articuno): no movement, no bites, no abilities
            if (e.freezeTimer > 0f) {
                e.freezeTimer -= dt
                continue
            }

            val speedMul = if (e.slowTimer > 0f) 0.5f else 1f
            if (e.slowTimer > 0f) e.slowTimer -= dt

            // Sun theft (Sableye)
            if (t.stealInterval > 0f) {
                e.timer += dt
                if (e.timer >= t.stealInterval) {
                    e.timer = 0f
                    val stolen = min(sun, t.stealAmount)
                    sun -= stolen
                    say("${t.label} stole $stolen rare candy!")
                }
            }

            // Ranged shadow ball (Gengar), only when a defender is in its lane to the left
            if (t.rangedInterval > 0f) {
                e.timer += dt
                if (e.timer >= t.rangedInterval && defenders.any { !it.dead && it.row == e.row && it.col < e.x }) {
                    e.timer = 0f
                    enemyShots.add(EnemyShot(e.row, e.x, t.rangedDamage))
                }
            }

            // Flying enemies pass over defenders without stopping
            if (t.flies) {
                e.x -= t.speed * speedMul * dt
                continue
            }

            val col = floor(e.x).toInt()
            val blocker = defenders.firstOrNull { !it.dead && it.row == e.row && it.col == col }
            if (blocker != null) {
                val bite = t.dps * dt
                var damage = bite
                if (!e.pounced) {
                    damage += t.burst // Night Slash on first contact (Weavile)
                    e.pounced = true
                }
                blocker.hp -= damage
                blocker.hitFlash = HIT_FLASH
                if (t.lifesteal > 0f) e.hp = min(t.hp, e.hp + bite * t.lifesteal) // Leech Life (Zubat)
                if (blocker.hp <= 0f) blocker.dead = true
            } else {
                e.x -= t.speed * speedMul * dt
            }
        }
    }

    private fun updateSpawns(dt: Float) {
        if (spawnedCount >= level.count) return
        spawnTimer -= dt
        if (spawnTimer > 0f) return

        val isBoss = level.bossAtEnd && spawnedCount == level.count - 1
        val type = if (isBoss) EnemyType.GENGAR else level.pool.random(rng)
        enemies.add(Enemy(type, rng.nextInt(ROWS), COLS + 0.3f))
        spawnedCount++
        if (isBoss) say("Gengar appears!")

        // Waves get a little faster as the level goes on.
        val progress = spawnedCount.toFloat() / level.count
        spawnTimer = level.interval * SPAWN_SLOWDOWN * (0.75f + rng.nextFloat() * 0.5f) * (1f - 0.3f * progress)
    }
}
