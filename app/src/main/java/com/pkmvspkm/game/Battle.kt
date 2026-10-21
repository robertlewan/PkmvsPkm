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
private const val ENEMY_SHOT_SPEED = 2.5f // columns per second
private const val FALL_SPEED = 1.2f       // rows per second
private const val HIT_FLASH = 0.12f       // seconds
private const val BURN_DPS = 8f           // damage per second while burning
private const val POISON_DPS = 5f         // damage per second while poisoned
private const val SUN_HOVER_TIME = 0.5f   // seconds a drop waits on the ground before flying up
private const val PICKUP_TIME = 0.6f      // seconds for a drop to fly from the board to the HUD
private const val SPAWN_SLOWDOWN = 1.3f   // multiplies level spawn intervals; higher = fewer enemies
private const val GAME_SPEED = 0.75f      // global speed of the battle; lower = slower

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
    /** Seconds between ranged shots. 0 = melee only. */
    val rangedInterval: Float = 0f,
    val rangedDamage: Float = 0f,
    /** Explodes on the first defender it reaches (dealing [burst] damage) and is removed. */
    val kamikaze: Boolean = false,
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
        "Shadow Armor: takes 50% less damage from shots. Gem Thief: steals 25 sun from you every 10 seconds.",
        armor = 0.5f, stealInterval = 10f, stealAmount = 25,
    ),
    WEAVILE(
        "Weavile", 260f, 0.9f, 35f, 0xFF1565C0.toInt(), 461,
        "Night Slash: the first time it reaches each defender it slashes for 45 extra damage. Very fast.",
        burst = 45f,
    ),
    RATTATA(
        "Rattata", 70f, 0.9f, 12f, 0xFF8D6E63.toInt(), 19,
        "Quick Bite: fast and weak, and it always comes in packs.",
    ),
    NIDORAN(
        "Nidoran", 130f, 0.6f, 18f, 0xFF7E57C2.toInt(), 29,
        "Horn Attack: the first time it reaches each defender it jabs for 15 extra damage.",
        burst = 15f,
    ),
    RHYHORN(
        "Rhyhorn", 320f, 0.4f, 35f, 0xFF90A4AE.toInt(), 111,
        "Rock Charge: charges into the first defender it reaches for 40 extra damage. Very tough.",
        burst = 40f,
    ),
    VOLTORB(
        "Voltorb", 60f, 1.0f, 0f, 0xFFE53935.toInt(), 100,
        "Self-Destruct: explodes on the first defender it reaches for 150 damage, then disappears.",
        burst = 150f, kamikaze = true,
    ),
    MAGNEMITE(
        "Magnemite", 110f, 0.55f, 12f, 0xFF78909C.toInt(), 81,
        "Magnet Pull: flies over defenders and takes 20% less damage from shots.",
        flies = true, armor = 0.2f,
    ),
    TENTACOOL(
        "Tentacool", 130f, 0.5f, 15f, 0xFF4DD0E1.toInt(), 72,
        "Jellyfish: heals a third of the damage it bites, so it is hard to finish.",
        lifesteal = 0.33f,
    ),
    SEEL(
        "Seel", 220f, 0.35f, 20f, 0xFFB3E5FC.toInt(), 86,
        "Thick Fat: takes 20% less damage from shots. Slow but durable.",
        armor = 0.2f,
    ),
    GROWLITHE(
        "Growlithe", 110f, 0.9f, 25f, 0xFFFF7043.toInt(), 58,
        "Flame Fang: the first bite on each defender deals 15 extra fire damage.",
        burst = 15f,
    ),
    MAGMAR(
        "Magmar", 260f, 0.5f, 30f, 0xFFE64A19.toInt(), 126,
        "Fire Blast: fires a 35-damage fireball every 6 seconds at defenders in its lane.",
        rangedInterval = 6f, rangedDamage = 35f,
    ),
    CATERPIE(
        "Caterpie", 60f, 0.8f, 10f, 0xFF8BC34A.toInt(), 10,
        "Fast little bug that arrives in swarms. Weak on its own, but a crowd adds up.",
    ),
    WEEDLE(
        "Weedle", 80f, 0.75f, 14f, 0xFFFFB74D.toInt(), 13,
        "Poison Sting: its bites hurt more than most bugs, at 14 damage per second.",
    ),
    KAKUNA(
        "Kakuna", 200f, 0.15f, 15f, 0xFFFFE082.toInt(), 14,
        "Harden: takes 40% less damage from shots. Very slow, but hard to take down.",
        armor = 0.4f,
    ),
    BEEDRILL(
        "Beedrill", 150f, 0.95f, 30f, 0xFFFBC02D.toInt(), 15,
        "Twineedle: the fastest bug. Stabs a defender hard the first time it reaches it.",
        burst = 30f,
    ),
    GEODUDE(
        "Geodude", 240f, 0.3f, 25f, 0xFFA1887F.toInt(), 74,
        "Rock Head: takes 25% less damage from shots. Slow and sturdy.",
        armor = 0.25f,
    ),
    GRAVELER(
        "Graveler", 380f, 0.22f, 35f, 0xFF8D6E63.toInt(), 75,
        "Rolling Rock: very tough, takes 30% less damage, and bites hard when it reaches a defender.",
        armor = 0.3f,
    ),
    ONIX(
        "Onix", 500f, 0.2f, 40f, 0xFFBCAAA4.toInt(), 95,
        "Rock Throw: hurls a 30-damage rock at defenders in its lane every 5 seconds. Huge and slow.",
        rangedInterval = 5f, rangedDamage = 30f,
    ),
    DIGLETT(
        "Diglett", 70f, 1.0f, 15f, 0xFF795548.toInt(), 50,
        "Dig: burrows underground and passes under defenders without stopping. Very fast.",
        flies = true,
    ),
    DUGTRIO(
        "Dugtrio", 300f, 0.5f, 40f, 0xFF5D4037.toInt(), 51,
        "Triple Attack: hits three times when it reaches a defender. Digs under walls like Diglett.",
        flies = true, burst = 20f,
    ),
    GOLBAT(
        "Golbat", 170f, 0.75f, 25f, 0xFF6A1B9A.toInt(), 42,
        "Leech Life and flight: flies over defenders and heals for half the damage it bites.",
        flies = true, lifesteal = 0.5f,
    ),
    GASTLY(
        "Gastly", 90f, 0.5f, 15f, 0xFF4E342E.toInt(), 92,
        "Gaseous: floats over defenders without stopping. Fragile, but hard to hit in time.",
        flies = true,
    ),
    HAUNTER(
        "Haunter", 180f, 0.45f, 25f, 0xFF5E35B1.toInt(), 93,
        "Shadow Ball: flies over defenders and fires a 25-damage shadow ball every 5 seconds.",
        flies = true, rangedInterval = 5f, rangedDamage = 25f,
    ),
    GENGAR(
        "Gengar", 1200f, 0.22f, 90f, 0xFF4A148C.toInt(), 94,
        "Boss. Shadow Ball: fires a 50-damage shadow ball every 4 seconds at defenders in its lane. Slow but deadly.",
        boss = true, rangedInterval = 4f, rangedDamage = 50f,
    ),
}

class Defender(val type: DefenderType, val row: Int, val col: Int, val stats: DefStats) {
    var hp = stats.maxHp
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
    /** Seconds left at half speed (slowed by Squirtle and friends). */
    var slowTimer = 0f
    /** Seconds left burning. */
    var burnTimer = 0f
    /** Seconds left poisoned. */
    var poisonTimer = 0f
}

class Projectile(
    val row: Int,
    var x: Float,
    val damage: Float,
    val shot: ShotType,
    val effect: ShotEffect,
    /** Extra enemies this shot can pass through. */
    var pierce: Int,
    /** Columns per second. */
    val speed: Float,
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
 * A drop on the board. Drops from the sky are sunlight; drops from defenders (Floette, Florges)
 * are rare candy. [fallTo] is the row it lands on, or null if a defender dropped it.
 * It is collected on its own after landing, so the player does not have to tap it.
 */
class Sun(var x: Float, var y: Float, val fallTo: Float?, val value: Int) {
    var dead = false
    /** Seconds spent on the ground before it is collected. */
    var hover = 0f
    val candy: Boolean get() = fallTo == null
}

/** A drop flying from the board to the HUD counter. The count goes up when it arrives. */
class Pickup(val x: Float, val y: Float, val candy: Boolean, val value: Int) {
    /** 0 at the board, 1 at the HUD. */
    var progress = 0f
    var dead = false
}

enum class Outcome { RUNNING, WON, LOST }

class Battle(
    val level: LevelDef,
    /** The Pokemon brought into this stage. Only these can be placed. */
    val loadout: List<DefenderType>,
    /** Upgrade levels for each Pokemon, read once when it is placed. */
    private val upgradesFor: (DefenderType) -> Upgrades,
    private val rng: Random = Random.Default,
) {
    val defenders = mutableListOf<Defender>()
    val enemies = mutableListOf<Enemy>()
    val projectiles = mutableListOf<Projectile>()
    val enemyShots = mutableListOf<EnemyShot>()
    val suns = mutableListOf<Sun>()
    val pickups = mutableListOf<Pickup>()

    /**
     * One rare candy per lane at the house. An enemy that reaches the house steals the candy,
     * and the lane is cleared once. Reaching the house with no candy left loses the stage.
     */
    val trapReady = BooleanArray(ROWS) { true }
    /** Seconds left on the candy-stolen flash per row. */
    val trapFlash = FloatArray(ROWS)

    /** Candies taken by enemies so far. Drives the star rating. */
    var candyStolen = 0
        private set
    /** Short event text shown in the battle banner, e.g. "Sableye stole 25 sun!". */
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

    /** Advances the battle by [frameDt] real seconds, scaled by [GAME_SPEED] so everything runs slower. */
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

    /** Returns the drop under the given grid-unit point, if any. */
    fun sunAt(x: Float, y: Float): Sun? =
        suns.firstOrNull { !it.dead && abs(it.x - x) < 0.8f && abs(it.y - y) < 0.8f }

    /** Taps a drop on the board to grab it early. */
    fun collect(s: Sun) {
        if (s.dead) return
        s.dead = true
        pickups.add(Pickup(s.x, s.y, s.candy, s.value))
    }

    /** True when this Pokemon is in the loadout and there is enough sun to place it. */
    fun canUse(type: DefenderType): Boolean =
        outcome == Outcome.RUNNING && type in loadout && sun >= type.cost

    fun place(type: DefenderType, row: Int, col: Int): Boolean {
        if (!canUse(type)) return false
        if (defenders.any { it.row == row && it.col == col }) return false
        sun -= type.cost
        defenders.add(Defender(type, row, col, computeStats(type, upgradesFor(type))))
        return true
    }

    private fun say(text: String) {
        message = text
        messageTimer = 2.5f
    }

    private fun updateSuns(dt: Float) {
        sunTimer -= dt
        if (sunTimer <= 0f) {
            sunTimer = 4.5f + rng.nextFloat() * 2f
            suns.add(Sun(rng.nextInt(COLS) + 0.5f, -0.5f, rng.nextInt(ROWS) + 0.5f, SUN_VALUE))
        }
        for (s in suns) {
            if (s.dead) continue
            val target = s.fallTo
            if (target != null && s.y < target) s.y = min(target, s.y + FALL_SPEED * dt)
            // Once it has landed (or a defender dropped it), it waits briefly and then flies up to the HUD
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
                sun += p.value
            }
        }
    }

    private fun updateDefenders(dt: Float) {
        for (d in defenders) {
            if (d.dead) continue
            val s = d.stats
            d.timer += dt

            // Healing (Floette, Florges, Snorlax)
            if (s.heal > 0f) {
                for (ally in defenders) {
                    if (!ally.dead && ally.row == d.row && abs(ally.col - d.col) <= s.healRange) {
                        ally.hp = min(ally.stats.maxHp, ally.hp + s.heal * dt)
                    }
                }
            }

            if (s.sunInterval > 0f && d.timer >= s.sunInterval) {
                d.timer = 0f
                suns.add(Sun(d.col + 0.5f, d.row + 0.5f, null, s.sunValue))
            }

            val shooting = s.shotInterval > 0f && d.timer >= s.shotInterval
            if (shooting && enemies.any { !it.dead && it.row == d.row && it.x > d.col }) {
                d.timer = 0f
                projectiles.add(
                    Projectile(d.row, d.col + 0.8f, s.damage, d.type.shot, d.type.effect, d.type.pierce, s.projSpeed)
                )
            }
        }
    }

    private fun updateProjectiles(dt: Float) {
        for (p in projectiles) {
            p.x += p.speed * dt
            if (p.x > COLS + 1f) {
                p.dead = true
                continue
            }
            val target = enemies.firstOrNull {
                !it.dead && it.row == p.row && it !in p.hit && abs(it.x + 0.4f - p.x) < 0.5f
            } ?: continue
            // Sableye's armor reduces shot damage
            target.hp -= p.damage * (1f - target.type.armor)
            target.hitFlash = HIT_FLASH
            when (p.effect) {
                ShotEffect.BURN -> target.burnTimer = 3f
                ShotEffect.POISON -> target.poisonTimer = 4f
                ShotEffect.SLOW -> target.slowTimer = 2f
                ShotEffect.NONE -> Unit
            }
            if (target.hp <= 0f) target.dead = true
            p.hit.add(target)
            if (p.pierce > 0) p.pierce-- else p.dead = true
        }
    }

    private fun hurtDefender(d: Defender, amount: Float) {
        d.hp -= amount * (1f - d.stats.armor)
        d.hitFlash = HIT_FLASH
        if (d.hp <= 0f) d.dead = true
    }

    private fun updateEnemyShots(dt: Float) {
        for (s in enemyShots) {
            s.x -= ENEMY_SHOT_SPEED * dt
            if (s.x < -0.5f) {
                s.dead = true
                continue
            }
            val col = floor(s.x).toInt()
            val target = defenders.firstOrNull { !it.dead && it.row == s.row && it.col == col } ?: continue
            hurtDefender(target, s.damage)
            s.dead = true
        }
    }

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (e.dead) continue
            val t = e.type

            // Burn and poison tick every frame until they run out
            if (e.burnTimer > 0f || e.poisonTimer > 0f) {
                e.burnTimer = max(0f, e.burnTimer - dt)
                e.poisonTimer = max(0f, e.poisonTimer - dt)
                if (e.burnTimer > 0f) e.hp -= BURN_DPS * dt
                if (e.poisonTimer > 0f) e.hp -= POISON_DPS * dt
                if (e.hp <= 0f) {
                    e.dead = true
                    continue
                }
            }

            if (e.x < 0f) {
                // Reached the house: the lane's candy is stolen once, and the lane is cleared.
                // With no candy left in that lane, the stage is lost.
                if (!trapReady[e.row]) {
                    outcome = Outcome.LOST
                    return
                }
                trapReady[e.row] = false
                trapFlash[e.row] = 0.8f
                candyStolen++
                enemies.forEach { if (it.row == e.row) it.dead = true }
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
                    say("${t.label} stole $stolen sun!")
                }
            }

            // Ranged attack (Gengar, Onix, Magmar, Haunter), only when a defender is in its lane to the left
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
            if (blocker == null) {
                e.x -= t.speed * speedMul * dt
                continue
            }

            if (t.kamikaze) {
                hurtDefender(blocker, t.burst)
                e.dead = true
                continue
            }

            val bite = t.dps * dt
            var damage = bite
            if (!e.pounced) {
                damage += t.burst // first-contact attack, e.g. Night Slash
                e.pounced = true
            }
            hurtDefender(blocker, damage)
            if (t.lifesteal > 0f) e.hp = min(t.hp, e.hp + bite * t.lifesteal) // Leech Life (Zubat)
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
