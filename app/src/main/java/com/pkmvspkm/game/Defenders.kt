package com.pkmvspkm.game

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Base projectile speed in columns per second, before upgrades. */
const val PROJECTILE_SPEED = 4f

/** Total upgrade levels a Pokemon can reach. */
const val MAX_LEVEL = 10

/** Upgrade level at which an evolvable Pokemon can evolve. */
const val EVOLVE_LEVEL = 5

/** Look of a projectile. Each attack type is drawn with its own color and shape in battle. */
enum class ShotType(val color: Int) {
    NORMAL(0xFFF5F5F5.toInt()),
    FIRE(0xFFFF7043.toInt()),
    WATER(0xFF42A5F5.toInt()),
    GRASS(0xFF66BB6A.toInt()),
    ELECTRIC(0xFFFFEB3B.toInt()),
    ROCK(0xFF8D6E63.toInt()),
    GROUND(0xFFC9A46A.toInt()),
    POISON(0xFFAB47BC.toInt()),
    GHOST(0xFF7E57C2.toInt()),
    BUG(0xFFAED581.toInt()),
    ICE(0xFF80DEEA.toInt()),
    SHADOW(0xFF4A3A5A.toInt()),
}

/** Status effect a shot applies to the enemy it hits. */
enum class ShotEffect { NONE, BURN, POISON, SLOW }

/**
 * Upgrade levels spent on one Pokemon. Every level adds a small boost to one of four stats:
 * [hp], [power], [tempo], and [special]. The stat names shown in the game depend on the Pokemon's role.
 */
class Upgrades(var hp: Int = 0, var power: Int = 0, var tempo: Int = 0, var special: Int = 0) {
    val total: Int get() = hp + power + tempo + special
}

/** Final numbers for one defender once its upgrades are applied. */
class DefStats(
    val maxHp: Float,
    val damage: Float,
    /** Seconds between shots, 0 if it does not shoot. */
    val shotInterval: Float,
    /** Seconds between sun drops, 0 if it does not make sun. */
    val sunInterval: Float,
    /** HP per second given to allies in range, 0 if it does not heal. */
    val heal: Float,
    val healRange: Int,
    /** Fraction of incoming damage that is blocked, capped at 0.6. */
    val armor: Float,
    /** Columns per second for this unit's projectiles. */
    val projSpeed: Float,
    /** Sun value of each drop it makes. */
    val sunValue: Int,
)

/**
 * Every Pokemon that can stand on the grid. Starters are owned from the start,
 * the rest are recruited by clearing the stage they come from, or by evolving.
 */
enum class DefenderType(
    val label: String,
    val cost: Int,
    val spriteId: Int,
    val hp: Float,
    val shot: ShotType = ShotType.NORMAL,
    val damage: Float = 0f,
    val shotInterval: Float = 0f,
    val effect: ShotEffect = ShotEffect.NONE,
    val pierce: Int = 0,
    val sunInterval: Float = 0f,
    val healPerSecond: Float = 0f,
    val healRange: Int = 0,
    val armor: Float = 0f,
    val info: String,
) {
    // Starters
    BULBASAUR("Bulbasaur", 50, 1, 140f, ShotType.GRASS, 10f, 1.0f, ShotEffect.POISON,
        info = "Vine Whip: 10-damage vine shots that poison the enemy for 5 damage per second over 4 seconds."),
    CHARMANDER("Charmander", 125, 4, 150f, ShotType.FIRE, 30f, 1.8f, ShotEffect.BURN,
        info = "Ember: 30-damage fire shots that set the enemy alight for 8 damage per second over 3 seconds."),
    SQUIRTLE("Squirtle", 75, 7, 150f, ShotType.WATER, 15f, 1.6f, ShotEffect.SLOW,
        info = "Water Gun: 15-damage water jets that halve enemy speed for 2 seconds."),
    PIKACHU("Pikachu", 100, 25, 150f, ShotType.ELECTRIC, 20f, 1.4f, pierce = 1,
        info = "Thunder Shock: 20-damage bolts that pass through one more enemy."),

    // Recruited by clearing Safari Zone (Floette) and Route 5 (Snorlax)
    FLORETTE("Floette", 50, 670, 120f, sunInterval = 9f, healPerSecond = 12f, healRange = 2,
        info = "Flower Power: makes a rare candy every 9 seconds worth 25 sun, and heals defenders within 2 columns."),
    SNORLAX("Snorlax", 150, 143, 900f, healPerSecond = 10f,
        info = "Thick Fat and Rest: 900 HP, regenerates 10 HP/s, and blocks enemies to protect the shooters behind it."),

    // Evolved forms, unlocked by evolving (see EVOLUTIONS)
    IVYSAUR("Ivysaur", 90, 2, 200f, ShotType.GRASS, 16f, 1.3f, ShotEffect.POISON,
        info = "Razor Leaf: sharper vine shots that poison the enemy."),
    CHARMELEON("Charmeleon", 130, 5, 190f, ShotType.FIRE, 36f, 1.6f, ShotEffect.BURN,
        info = "Hotter Ember: 36-damage fire shots that burn for 8 damage per second."),
    WARTORTLE("Wartortle", 110, 8, 200f, ShotType.WATER, 20f, 1.5f, ShotEffect.SLOW,
        info = "Water Pulse: heavier water shots that slow enemies."),
    RAICHU("Raichu", 170, 26, 180f, ShotType.ELECTRIC, 32f, 1.5f, pierce = 2,
        info = "Thunderbolt: 32-damage bolts that pass through two more enemies."),
    FLORGES("Florges", 120, 671, 180f, sunInterval = 8f, healPerSecond = 16f, healRange = 2,
        info = "Flower Power: a rare candy every 8 seconds, and stronger healing."),
    KAKUNA("Kakuna", 60, 14, 240f, armor = 0.3f,
        info = "Harden: a sturdy wall that takes 30% less damage. Does not attack."),
    GOLBAT("Golbat", 90, 42, 170f, ShotType.POISON, 14f, 1.0f, ShotEffect.POISON,
        info = "Poison Fang: poisons enemies for 5 damage per second."),
    GRAVELER("Graveler", 120, 75, 320f, ShotType.ROCK, 26f, 1.6f, pierce = 1, armor = 0.15f,
        info = "Rock Blast: passes through one more enemy. Takes 15% less damage."),
    DUGTRIO("Dugtrio", 100, 51, 160f, ShotType.GROUND, 16f, 1.0f, ShotEffect.SLOW, pierce = 1,
        info = "Mud Bomb: passes through one more enemy and slows."),
    HAUNTER("Haunter", 120, 93, 150f, ShotType.GHOST, 22f, 1.5f, ShotEffect.POISON,
        info = "Shadow Ball: dark orbs that poison."),

    // Recruited by clearing their stage
    CATERPIE("Caterpie", 40, 10, 90f, ShotType.BUG, 8f, 1.0f, ShotEffect.SLOW,
        info = "String Shot: sticky bug shots that slow enemies."),
    WEEDLE("Weedle", 50, 13, 100f, ShotType.POISON, 10f, 1.1f, ShotEffect.POISON,
        info = "Poison Sting: poisons enemies for 5 damage per second over 4 seconds."),
    BEEDRILL("Beedrill", 110, 15, 140f, ShotType.BUG, 9f, 0.5f,
        info = "Fury Attack: very fast bug jabs."),
    ZUBAT("Zubat", 60, 41, 100f, ShotType.NORMAL, 8f, 0.8f,
        info = "Wing Attack: quick, weak wing blows."),
    GEODUDE("Geodude", 60, 74, 200f, ShotType.ROCK, 16f, 1.7f, armor = 0.1f,
        info = "Rock Throw: heavy rocks. Takes 10% less damage."),
    DIGLETT("Diglett", 45, 50, 80f, ShotType.GROUND, 8f, 0.9f, ShotEffect.SLOW,
        info = "Mud Slap: muddy shots that slow enemies."),
    GASTLY("Gastly", 70, 92, 90f, ShotType.GHOST, 9f, 1.1f, ShotEffect.POISON,
        info = "Lick: ghostly shots that poison enemies."),
    RATTATA("Rattata", 40, 19, 100f, ShotType.NORMAL, 9f, 0.9f,
        info = "Tackle: quick, cheap blows."),
    NIDORAN("Nidoran", 60, 29, 130f, ShotType.POISON, 11f, 1.2f, ShotEffect.POISON,
        info = "Poison Sting: poisons enemies for 5 damage per second."),
    RHYHORN("Rhyhorn", 120, 111, 350f, ShotType.ROCK, 14f, 2.0f, armor = 0.2f,
        info = "Rock Blast: slow but very tough. Takes 20% less damage."),
    VOLTORB("Voltorb", 60, 100, 60f, ShotType.ELECTRIC, 14f, 1.2f,
        info = "Spark: quick electric shots."),
    MAGNEMITE("Magnemite", 75, 81, 120f, ShotType.ELECTRIC, 12f, 1.2f, ShotEffect.SLOW, armor = 0.2f,
        info = "Thunder Wave: electric waves that slow enemies. Takes 20% less damage."),
    ONIX("Onix", 130, 95, 450f, ShotType.ROCK, 18f, 1.8f, pierce = 1, armor = 0.25f,
        info = "Rock Throw: rocks that pass through one more enemy. Very tough."),
    TENTACOOL("Tentacool", 60, 72, 110f, ShotType.WATER, 10f, 1.0f, ShotEffect.SLOW,
        info = "Bubble: bubbles that slow enemies."),
    SEEL("Seel", 80, 86, 180f, ShotType.ICE, 14f, 1.5f, ShotEffect.SLOW, armor = 0.2f,
        info = "Ice Beam: cold shots that slow enemies. Takes 20% less damage."),
    SABLEYE("Sableye", 100, 302, 160f, ShotType.SHADOW, 18f, 1.4f,
        info = "Shadow Ball: dark orbs."),
    GROWLITHE("Growlithe", 70, 58, 110f, ShotType.FIRE, 14f, 1.2f, ShotEffect.BURN,
        info = "Ember: lighter fire shots that burn."),
    MAGMAR("Magmar", 130, 126, 220f, ShotType.FIRE, 30f, 2.2f, ShotEffect.BURN,
        info = "Fire Blast: big fireballs that burn for 8 damage per second."),
    MURKROW("Murkrow", 70, 198, 110f, ShotType.SHADOW, 12f, 1.0f,
        info = "Peck: quick dark feathers."),
    WEAVILE("Weavile", 110, 461, 150f, ShotType.SHADOW, 22f, 0.9f,
        info = "Night Slash: fast dark slashes."),
}

/** Which Pokemon each one evolves into once it reaches [EVOLVE_LEVEL] upgrade levels. */
val EVOLUTIONS: Map<DefenderType, DefenderType> = mapOf(
    DefenderType.BULBASAUR to DefenderType.IVYSAUR,
    DefenderType.CHARMANDER to DefenderType.CHARMELEON,
    DefenderType.SQUIRTLE to DefenderType.WARTORTLE,
    DefenderType.PIKACHU to DefenderType.RAICHU,
    DefenderType.FLORETTE to DefenderType.FLORGES,
    DefenderType.WEEDLE to DefenderType.KAKUNA,
    DefenderType.ZUBAT to DefenderType.GOLBAT,
    DefenderType.GEODUDE to DefenderType.GRAVELER,
    DefenderType.DIGLETT to DefenderType.DUGTRIO,
    DefenderType.GASTLY to DefenderType.HAUNTER,
)

/** Short role name shown in the roster and Pokedex. */
fun roleName(type: DefenderType): String = when {
    type.shotInterval > 0f -> "Shooter"
    type.sunInterval > 0f -> "Sun maker"
    type.healPerSecond > 0f -> "Healer"
    else -> "Wall"
}

/**
 * Names of the four upgrade buttons for this Pokemon, in order: HP, power, tempo, special.
 * The names match what each upgrade actually changes for that role.
 */
fun statLabels(type: DefenderType): List<String> = when {
    type.shotInterval > 0f -> listOf("HP", "DAMAGE", "SPEED", "PROJ")
    type.sunInterval > 0f -> listOf("HP", "SUN", "SPEED", "ARMOR")
    type.healPerSecond > 0f -> listOf("HP", "HEAL", "SPEED", "ARMOR")
    else -> listOf("HP", "ARMOR", "ARMOR", "ARMOR")
}

/**
 * Turns base stats plus upgrade levels into the numbers used in battle.
 *
 * - HP: +8% max HP per level.
 * - Power: +8% damage, heal, or sun value per level.
 * - Tempo: 5% shorter shot or sun cooldown per level (healers heal 5% faster instead).
 * - Special: shooters fire 12% faster projectiles per level; everything else gets +4% armor per level.
 * Pure walls (no shot, sun, or heal) put Power and Tempo into armor as well, so they are never wasted.
 */
fun computeStats(type: DefenderType, up: Upgrades): DefStats {
    val shooter = type.shotInterval > 0f
    val producer = type.sunInterval > 0f
    val healer = type.healPerSecond > 0f
    val pureWall = !shooter && !producer && !healer
    val power = 1f + 0.08f * up.power
    val tempo = 0.95f.pow(up.tempo)
    val armorBonus = (if (!shooter) 0.04f * up.special else 0f) +
        (if (pureWall) 0.04f * (up.power + up.tempo) else 0f)
    return DefStats(
        maxHp = type.hp * (1f + 0.08f * up.hp),
        damage = type.damage * power,
        shotInterval = if (shooter) type.shotInterval * tempo else 0f,
        sunInterval = if (producer) type.sunInterval * tempo else 0f,
        heal = if (healer) type.healPerSecond * power * (1f + 0.05f * up.tempo) else 0f,
        healRange = type.healRange,
        armor = min(0.6f, type.armor + armorBonus),
        projSpeed = PROJECTILE_SPEED * (1f + 0.12f * up.special),
        sunValue = (SUN_VALUE * power).roundToInt(),
    )
}
