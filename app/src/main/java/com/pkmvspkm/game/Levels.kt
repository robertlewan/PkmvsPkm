package com.pkmvspkm.game

class LevelDef(
    val id: Int,
    val name: String,
    /** Enemy types that can spawn in this level. */
    val pool: List<EnemyType>,
    /** Total enemies in the level, including the boss if there is one. */
    val count: Int,
    /** Average seconds between spawns. */
    val interval: Float,
    val startSun: Int,
    /** When true, the last enemy spawned is Gengar. */
    val bossAtEnd: Boolean,
)

val LEVELS = listOf(
    LevelDef(
        id = 1,
        name = "Rocket Hideout",
        pool = listOf(EnemyType.ZUBAT, EnemyType.MURKROW),
        count = 12,
        interval = 6f,
        startSun = 150,
        bossAtEnd = false,
    ),
    LevelDef(
        id = 2,
        name = "Dark Forest",
        pool = listOf(EnemyType.ZUBAT, EnemyType.MURKROW, EnemyType.SABLEYE, EnemyType.WEAVILE),
        count = 20,
        interval = 4.5f,
        startSun = 150,
        bossAtEnd = false,
    ),
    LevelDef(
        id = 3,
        name = "Gengar's Haunt",
        pool = listOf(EnemyType.MURKROW, EnemyType.SABLEYE, EnemyType.WEAVILE),
        count = 25,
        interval = 4f,
        startSun = 175,
        bossAtEnd = true,
    ),
)
