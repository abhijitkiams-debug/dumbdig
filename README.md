# Flipside 🟢🔴

**A one-tap, gravity-flipping endless runner for Android — built from scratch, 100% original, zero copyrighted assets.**

Tap to flip gravity. Fall to the floor or the ceiling. Thread the spikes, grab
the gems, and chase your best score. It's a five-second skill ceiling and an
infinite mastery curve — the formula behind the most shareable mobile games.

```
        ▲  ceiling spikes
   ◐ ───────────────────────►   tap = flip
        ▼  floor spikes
```

---

## Why this design goes viral

Viral hyper-casual games share a small set of traits. Flipside is engineered
around every one of them:

| Viral trait | How Flipside delivers it |
|-------------|--------------------------|
| **One-thumb control** | The entire game is a single tap. Anyone can play in 2 seconds. |
| **Instant restart** | Crash → tap → playing again. No menus between attempts. |
| **"One more try" loop** | Runs are short, deaths feel like your fault, the next run is one tap away. |
| **Readable at a glance** | Bold shapes, two signature colours, no clutter — looks great in a clip. |
| **Score chasing** | Persistent best score + a "NEW BEST!" moment to brag about. |
| **Smooth juice** | Particle bursts, motion trails, screen-flip colour feedback, synth SFX. |
| **Tiny + offline** | ~3 MB, no network, no accounts, no permissions. Installs and runs anywhere. |

## Gameplay

- **Tap** anywhere to invert gravity. The orb falls toward the surface it's heading to.
- **Avoid** the red spikes jutting from the floor and ceiling.
- **Collect** gold gems for +10 points each.
- **Speed ramps** the longer you survive; spacing tightens but always stays fair.
- **Score** = distance travelled + gem bonus. Your best is saved locally.

## Tech at a glance

- **Language:** 100% Kotlin
- **Rendering:** plain `SurfaceView` + a hand-written 60 fps render thread (no game engine)
- **Graphics:** every pixel drawn procedurally with `Canvas` primitives — no image files
- **Audio:** sound effects synthesized at runtime as PCM tones — no audio files
- **Dependencies:** only Google's AndroidX `core-ktx` + `appcompat` (Apache-2.0)
- **Min SDK:** 21 (Android 5.0) · **Target SDK:** 34

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/originalgames/flipside/
│   ├── MainActivity.kt      # immersive single-activity host
│   ├── GameView.kt          # game loop, state machine, rendering, spawning
│   ├── Player.kt            # the orb: gravity-flip physics + trail
│   ├── Obstacle.kt          # floor/ceiling spikes + collision
│   ├── Gem.kt               # collectible diamonds
│   ├── ParticleSystem.kt    # pooled burst particles (no per-frame allocation)
│   ├── SoundManager.kt      # runtime PCM sound synthesis
│   └── Prefs.kt             # high-score persistence
└── res/                     # original vector launcher icon, theme, strings
```

## Build & run

You need **Android Studio** (or a configured Android SDK).

```bash
# Debug APK
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release APK (R8 minified + resource shrinking)
./gradlew :app:assembleRelease
```

Install on a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> `local.properties` (your machine's `sdk.dir`) is intentionally gitignored.
> Android Studio creates it for you on first open.

Both `assembleDebug` and `assembleRelease` are verified to compile and package
cleanly against Android SDK 34 / Gradle 8.7 / AGP 8.5.2.

## Copyright

Flipside ships **no third-party art, audio, fonts, or game code**. Everything
visual and audible is generated at runtime from primitives. See
[`ORIGINALITY.md`](ORIGINALITY.md) for a full breakdown and
[`LICENSE`](LICENSE) for the MIT grant. You can publish, reskin, or sell this
freely.

## Ideas to push it further

- Daily challenge seed + shareable result card (the screenshot-to-social loop)
- Unlockable orb skins earned with collected gems
- Haptic tick on flip and gem pickup
- Google Play Games leaderboard hook
