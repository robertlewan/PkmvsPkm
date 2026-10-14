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
private const val PROJECTILE_SPEED = 4f // columns per second
private const val FALL_SPEED = 1.2f     // rows per second

enum class DefenderType(
    val label: String,
    val cost: Int,
    val hp: Float,
    val color: Int,
    /** Seconds between shots. 0 = does not shoot. */
    val shotInterval: Float,
    val damage: Float,
    /** Seconds between sun drops. 0 = does not produce sun. */
    val sunInterval: Float,
    /** National Pokedex number, used to load the official sprite. */
    val spriteId: Int,
) {
    CHANSEY("Chansey", 50, 120f, 0xFFF48FB1.toInt(), 0f, 0f, 12f, 113),
    PIKACHU("Pikachu", 100, 150f, 0xFFFDD835.toInt(), 1.4f, 20f, 0f, 25),
    CHARMANDER("Charmander", 125, 150f, 0xFFFF7043.toInt(), 1.8f, 30f, 0f, 4),
    SQUIRTLE("Squirtle", 75, 150f, 0xFF4FC3F7.toInt(), 1.6f, 15f, 0f, 7),
    SNORLAX("Snorlax", 150, 900f, 0xFF607D8B.toInt(), 0f, 0f, 0f, 143),
}

enum class EnemyType(
    val label: String,
    val hp: Float,
    /** Columns per second. */
    val speed: Float,
    /** Damage per second to a defender it is blocked by. */
    val dps: Float,
    val color: Int,
    val boss: Boolean = false,
    /** National Pokedex number, used to load the official sprite. */
    val spriteId: Int,
) {
    ZUBAT("Zubat", 100f, 0.6f, 20f, 0xFF7E57C2.toInt(), spriteId = 41),
    MURKROW("Murkrow", 180f, 0.45f, 30f, 0xFF37474F.toInt(), spriteId = 198),
    SABLEYE("Sableye", 320f, 0.35f, 40f, 0xFF8E24AA.toInt(), spriteId = 302),
    WEAVILE("Weavile", 260f, 0.85f, 35f, 0xFF1565C0.toInt(), spriteId = 461),
    GENGAR("Gengar", 1200f, 0.22f, 90f, 0xFF4A148C.toInt(), boss = true, spriteId = 94),
}

class Defender(val type: DefenderType, val row: Int, val col: Int) {
    var hp = type.hp
    var timer = 0f
    var dead = false
}

class Enemy(val type: EnemyType, val row: Int, var x: Float) {
    var hp = type.hp
    var dead = false
}

class Projectile(val row: Int, var x: Float, val damage: Float) {
    var dead = false
}

/** A collectible sun. [fallTo] is the row it lands on, or null if it was dropped by a defender. */
class Sun(var x: Float, var y: Float, val fallTo: Float?, var life: Float) {
    var dead = false
}

enum class Outcome { RUNNING, WON, LOST }

class Battle(val level: LevelDef, private val rng: Random = Random.Default) {
    val defenders = mutableListOf<Defender>()
    val enemies = mutableListOf<Enemy>()
    val projectiles = mutableListOf<Projectile>()
    val suns = mutableListOf<Sun>()

    /** One-use lane clearer per row (the lawnmower equivalent). */
    val trapReady = BooleanArray(ROWS) { true }
    /** Seconds left on the trap flash animation per row. */
    val trapFlash = FloatArray(ROWS)

    var sun = level.startSun
        private set
    var outcome = Outcome.RUNNING
        private set
    var spawnedCount = 0
        private set

    private var spawnTimer = 3f
    private var sunTimer = 6f

    fun update(dt: Float) {
        if (outcome != Outcome.RUNNING) return
        updateSuns(dt)
        updateDefenders(dt)
        updateProjectiles(dt)
        updateEnemies(dt)
        updateSpawns(dt)
        for (r in 0 until ROWS) trapFlash[r] = max(0f, trapFlash[r] - dt)

        defenders.removeAll { it.dead }
        enemies.removeAll { it.dead }
        projectiles.removeAll { it.dead }
        suns.removeAll { it.dead }

        if (outcome == Outcome.RUNNING && spawnedCount >= level.count && enemies.isEmpty()) {
            outcome = Outcome.WON
        }
    }

    /** Returns the sun under the given grid-unit point, if any. */
    fun sunAt(x: Float, y: Float): Sun? =
        suns.firstOrNull { !it.dead && abs(it.x - x) < 0.6f && abs(it.y - y) < 0.6f }

    fun collect(s: Sun) {
        if (s.dead) return
        s.dead = true
        sun += SUN_VALUE
    }

    fun place(type: DefenderType, row: Int, col: Int): Boolean {
        if (outcome != Outcome.RUNNING || sun < type.cost) return false
        if (defenders.any { it.row == row && it.col == col }) return false
        sun -= type.cost
        defenders.add(Defender(type, row, col))
        return true
    }

    private fun updateSuns(dt: Float) {
        sunTimer -= dt
        if (sunTimer <= 0f) {
            sunTimer = 7f + rng.nextFloat() * 3f
            suns.add(Sun(rng.nextInt(COLS) + 0.5f, -0.5f, rng.nextInt(ROWS) + 0.5f, 10f))
        }
        for (s in suns) {
            val target = s.fallTo
            if (target != null && s.y < target) s.y = min(target, s.y + FALL_SPEED * dt)
            s.life -= dt
            if (s.life <= 0f) s.dead = true
        }
    }

    private fun updateDefenders(dt: Float) {
        for (d in defenders) {
            if (d.dead) continue
            d.timer += dt

            if (d.type.sunInterval > 0f && d.timer >= d.type.sunInterval) {
                d.timer = 0f
                suns.add(Sun(d.col + 0.5f, d.row + 0.5f, null, 8f))
            }

            val shooting = d.type.shotInterval > 0f && d.timer >= d.type.shotInterval
            if (shooting && enemies.any { it.row == d.row && it.x > d.col }) {
                d.timer = 0f
                projectiles.add(Projectile(d.row, d.col + 0.8f, d.type.damage))
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
            val target = enemies.firstOrNull { !it.dead && it.row == p.row && abs(it.x + 0.4f - p.x) < 0.5f }
            if (target != null) {
                target.hp -= p.damage
                if (target.hp <= 0f) target.dead = true
                p.dead = true
            }
        }
    }

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (e.dead) continue

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

            val col = floor(e.x).toInt()
            val blocker = defenders.firstOrNull { !it.dead && it.row == e.row && it.col == col }
            if (blocker != null) {
                blocker.hp -= e.type.dps * dt
                if (blocker.hp <= 0f) blocker.dead = true
            } else {
                e.x -= e.type.speed * dt
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

        // Waves get a little faster as the level goes on.
        val progress = spawnedCount.toFloat() / level.count
        spawnTimer = level.interval * (0.75f + rng.nextFloat() * 0.5f) * (1f - 0.3f * progress)
    }
}
