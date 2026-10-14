# PkmvsPkm

A Plants-vs-Zombies style tower defense game for Android, written in Kotlin.
Place Pokemon defenders on the grid, collect sun to pay for them, and stop the
evil Pokemon (Zubat, Murkrow, Sableye, Weavile, and finally Gengar) before they
reach your side. Each lane has one Poke Ball trap that clears it once.

## Build the APK

1. Install [Android Studio](https://developer.android.com/studio) (includes the JDK and Android SDK).
2. Open Android Studio, choose **Open**, and select this `PkmvsPkm` folder.
3. Wait for the Gradle sync to finish.
4. Go to **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
5. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and install it (allow installs from unknown sources).

## Sprites

Pokemon art is loaded at runtime from the [PokeAPI sprite repository](https://github.com/PokeAPI/sprites) (`SpriteCache.kt`), so the images are not stored in this project. The app needs an internet connection the first time each sprite is shown. If a download fails, the game shows a colored placeholder instead.

The sprites are Nintendo / Game Freak artwork. This is fine for personal use, but do not publish the APK on a store without permission from The Pokemon Company.

## Project layout

- `app/src/main/java/com/pkmvspkm/game/Battle.kt`: game rules, enemies, defenders, sun, and trap logic (no Android code)
- `app/src/main/java/com/pkmvspkm/game/Levels.kt`: level definitions (enemy pool, count, spacing)
- `app/src/main/java/com/pkmvspkm/game/GameView.kt`: drawing and touch input
- `app/src/main/java/com/pkmvspkm/game/SaveData.kt`: unlocked-level progress saved with SharedPreferences
