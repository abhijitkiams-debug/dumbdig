# Astra: Ram vs Ravan 🏹🔥

**A side-view artillery duel for Android — Pocket Tanks meets the Ramayana. Built from scratch, 100% original, zero copyrighted assets.**

You are **Ram**, the divine archer, on the left. Across a varied, destructible
battlefield stands **Ravan**, the ten-headed Demon-King of Lanka. Take turns
lobbing weapons over the terrain: pick your **weapon**, drag to set your **aim
angle and power** (a live trajectory arc previews the shot), account for the
**wind**, and release to fire. Whittle his health to zero before he does yours.

```
                          .  ^  .            <- wind + arcing shot
                     .            .
   \O/  Ram      .                   .   Ravan (10 heads)
   /|\ >==      pillar   [temple]        \=<  /|\
  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~   <- destructible terrain
   [Baan][Agni][Tri][Gada][Brahma]   <- pick weapon · drag to aim · release
```

> Landscape orientation. Turn your phone sideways for the full battlefield.

---

## Why this design is engineered to go viral

Modern hits (Archero, Survivor.io) pair a **one-thumb core** with **roguelite
build depth** and **social loops**. The research is consistent: a player must
*get* the game in 3–5 seconds, every retry must be faster than the last, and the
game must manufacture *shareable, braggable moments*. Astra is built around
every one of those levers:

| Viral trait | How Astra delivers it |
|-------------|-----------------------|
| **Seconds to grasp** | The universally-understood "aim-and-lob" artillery loop — everyone has played a Pocket Tanks / Angry Birds shot. |
| **Easy to learn, hard to master** | Anyone can fire; wind, terrain, weapon choice and blast timing are the mastery curve. |
| **Instant restart** | Win/lose → tap → new terrain, fighting again. No menus between duels. |
| **"One more duel" loop** | Duels are short and swingy; a lucky Brahmastra or a wind read flips the fight. |
| **Weapon variety & choice** | Five weapons with distinct arcs, blasts and ammo — expressive, screenshot-worthy shots. |
| **Destructible, varied terrain** | Fresh battlefield every duel with craters and cover — endless situational variety. |
| **A braggable metric** | "I slayed Ravan in **6 rounds**" is instantly legible and competitive. |
| **Readable at a glance** | Bold side-view silhouettes, signature gold/saffron vs demon-red — looks great in a clip. |
| **Share loop** | One-tap **SHARE** renders a branded result card for the system share sheet. |
| **Cultural resonance** | The Ramayana is one of the most beloved stories on Earth — built-in emotional pull and a massive audience. |
| **Smooth juice** | Explosion bursts, screen craters, hit-flashes, synth SFX, haptics. |
| **Tiny + offline** | ~3 MB debug / **0.66 MB release**, no network, no accounts; the only permission is the benign auto-granted `VIBRATE`. |

## Gameplay

It's a **turn-based artillery duel** (think Pocket Tanks / Gunbound):

1. **Pick a weapon** from the bottom selector — each has its own damage, blast, weight and ammo.
2. **Aim**: drag from Ram to set the shot's **angle and power**. A dashed **trajectory arc** previews exactly where it'll go, and the angle/power read out live.
3. **Mind the wind**: the wind indicator (top-centre) nudges every shot left or right.
4. **Release to fire.** The arrow/astra arcs over the terrain, craters the ground, and damages Ravan if it lands close enough.
5. **Ravan fires back** — his aim sharpens each round, so finish him quickly.
6. First to drop the other's **health bar** to zero wins the duel.

Every battle generates **fresh, destructible terrain** with pillars for cover, so no two duels play the same.

### The arsenal (weapons)

| Weapon | Behaviour |
|--------|-----------|
| **Baan** | The basic arrow. Small blast, unlimited ammo. |
| **Agni Baan** | Fire arrow with a solid explosive blast. |
| **Tri-Baan** | Fires three arrows in a spread — great for a moving/uncertain aim. |
| **Gada** (mace) | Heavy, arcs steeply, big damage and blast. |
| **Brahmastra** | The ultimate — huge damage and blast, but only **one** per duel. |

### Progression

- **Win duels** against Ravan — your total **wins** and **best damage** are saved locally.
- Earn **coins** for every hit landed (a foundation for future weapon unlocks / a shop).
- **Share** your result card after each duel to challenge friends.

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
├── java/com/originalgames/astra/
│   ├── MainActivity.kt      # immersive single-activity host (landscape)
│   ├── GameView.kt          # turn loop, aiming, AI, rendering, HUD
│   ├── Terrain.kt           # generated destructible heightmap + pillars
│   ├── Fighter.kt           # Ram & Ravan combatants (side-view, HP)
│   ├── Projectile.kt        # ballistic shot under gravity + wind, trail
│   ├── Weapon.kt            # the weapon loadout (damage/blast/ammo)
│   ├── Palette.kt           # shared colour palette
│   ├── ParticleSystem.kt    # pooled burst particles (no per-frame allocation)
│   ├── SoundManager.kt      # runtime PCM sound synthesis
│   ├── Haptics.kt           # crash-proof vibration feedback
│   ├── ShareCard.kt         # renders & shares the result-card image
│   └── Prefs.kt             # wins, best damage, FX toggles
└── res/                     # original vector launcher icon (bow & arrow), theme
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
cleanly against Android SDK 34 / Gradle 8.7 / AGP 8.5.2 (release APK ≈ 0.65 MB).

## Copyright

Astra ships **no third-party art, audio, fonts, or game code**. Everything
visual and audible is generated at runtime from primitives. The Ramayana is an
ancient, public-domain epic; the names *Ram*, *Ravan*, and the astras are part
of that shared cultural heritage. The specific code, art, audio, and
presentation here are all original. See [`ORIGINALITY.md`](ORIGINALITY.md) for a
full breakdown and [`LICENSE`](LICENSE) for the MIT grant.

## Ideas to push it further

- Google Play Games leaderboard for the Daily Battle
- A "flawless" badge for severing a head without taking a hit
- More astras (Pashupatastra, Varunastra) and a boss-rage bullet-hell phase
- Animated boon-reveal and a build-summary on the share card
