# Astra: Ram vs Ravan 🏹🔥

**A one-thumb, boss-survival archery game for Android — built from scratch, 100% original, zero copyrighted assets.**

You are **Ram**, the divine archer. Above you looms **Ravan**, the ten-headed
Demon-King of Lanka, raining down a storm of *aayudha* — arrows, maces,
tridents, war-discs and homing fire-bolts. Drag to dodge. Your bow fires on its
own. Fill the meter and unleash a rotating arsenal of divine **astras**. Sever
all ten heads — then survive his Rage.

```
   (o)(o)(o)(o)(o)(o)(o)(o)(o)(o)    <- Ravan's 10 heads
      v    O    Y    v    O          <- incoming aayudha (dodge!)
              |  |  |                 <- your auto-fired arrows
            >Ram<        [ASTRA]      <- drag to move | tap to unleash
```

---

## Why this design is engineered to go viral

Modern hits (Archero, Survivor.io) pair a **one-thumb core** with **roguelite
build depth** and **social loops**. The research is consistent: a player must
*get* the game in 3–5 seconds, every retry must be faster than the last, and the
game must manufacture *shareable, braggable moments*. Astra is built around
every one of those levers:

| Viral trait | How Astra delivers it |
|-------------|-----------------------|
| **3-second comprehension** | One rule: drag to dodge. Arrows fire themselves. Anyone plays instantly. |
| **One-thumb control** | The whole game is a single dragging thumb + an optional Astra tap. |
| **Instant restart** | Fall → tap → fighting again. No menus between attempts. |
| **"One more try" loop** | Runs are short, deaths feel like *your* dodge, the next run is one tap away. |
| **Roguelite build depth** | Every severed head offers a **Vardaan** (boon) — twin arrows, piercing, crits, astra surge… so each run grows its own bow (the Archero hook). |
| **Variable rewards** | Random 3-boon offers + a rotating astra arsenal keep the dopamine unpredictable. |
| **A braggable hero metric** | "I severed **8 / 10** of Ravan's heads" is instantly legible and competitive. |
| **Readable at a glance** | Bold silhouettes, signature gold/saffron vs demon-red — looks great in a 6-second clip. |
| **Share loop** | One-tap **SHARE** renders a branded result card (heads + score) for the system share sheet. |
| **Daily Battle** | A date-seeded fight that's *identical for everyone that day* — directly comparable scores to send friends. |
| **Daily streak** | Consecutive days build a streak shown on screen and on the share card — a daily-return hook. |
| **Cultural resonance** | The Ramayana is one of the most beloved stories on Earth — built-in emotional pull and a massive audience. |
| **Smooth juice** | Particle bursts, screen-shake, hit-flashes, head-sever explosions, synth SFX, haptics. |
| **Tiny + offline** | ~3 MB debug / **0.65 MB release**, no network, no accounts; the only permission is the benign auto-granted `VIBRATE`. |

## Gameplay

- **Drag** anywhere to slide Ram and dodge Ravan's incoming aayudha.
- Ram **auto-looses arrows** upward at the active head — focus on positioning and survival.
- **Astra meter** fills as you deal damage. When full, **tap the ASTRA button** to unleash the armed astra; firing **cycles to the next** one.
- Each head you sever offers a **choice of three boons** that permanently buff this run.
- **Lives** are three hearts (top-left). Take three hits and Ram falls.
- **Score** = heads × 250 + damage dealt + survival. Your best (endless) is saved locally.

### The arsenal

**Ravan's aayudha (dodge or shoot down):**

| Weapon | Behaviour |
|--------|-----------|
| **Baan** (arrow) | Straight & fast — your arrows *can* shoot it down. |
| **Chakra** (war-disc) | Spins and weaves side to side — destructible. |
| **Gada** (mace) | Heavy, lobbed in an arc — must be dodged. |
| **Trishul** (trident) | Falls straight and fast — must be dodged. |
| **Shakti** (fire-bolt) | Curves toward Ram — must be out-manoeuvred. |

**Ram's divine astras (rotate as you fire):**

| Astra | Effect |
|-------|--------|
| **Agneyastra** (fire) | A wall of flame burns all incoming aayudha and scorches Ravan. |
| **Vayavyastra** (wind) | A gale blows every incoming weapon off the screen. |
| **Nagastra** (serpent) | A volley of homing serpent-arrows seeks threats and Ravan. |
| **Brahmastra** (ultimate) | A pillar of light clears the field and devastates Ravan. |

### Modes & progression

- **Endless War** — a freshly random battle every run. Your best score is saved.
- **Daily Battle** — toggle on the start screen. The attack patterns are seeded
  from the date, so everyone playing that day faces the *same* fight. Play it,
  then tap **SHARE** to post a result card and challenge friends to beat it.
  - **Streak** — finishing the daily on consecutive days builds a day streak,
    shown on the start screen, the game-over screen, and the share card.
- **Rage of Ravan** — sever all ten heads and Ravan regrows them, tougher each
  cycle, for endless score-chasing. Your heads-severed count keeps climbing.

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
│   ├── MainActivity.kt      # immersive single-activity host
│   ├── GameView.kt          # game loop, state machine, rendering, spawning, boons
│   ├── Ram.kt               # the player archer: movement, lives, bow
│   ├── Ravan.kt             # the ten-headed boss: heads, HP, drawing
│   ├── Arrow.kt             # Ram's arrows (+ pierce / homing flags)
│   ├── Aayudha.kt           # Ravan's incoming weapon arsenal + collision
│   ├── Astra.kt             # the four divine astras + on-screen effects
│   ├── Boon.kt              # the roguelite loadout + Vardaan upgrades
│   ├── ParticleSystem.kt    # pooled burst particles (no per-frame allocation)
│   ├── SoundManager.kt      # runtime PCM sound synthesis
│   ├── Haptics.kt           # crash-proof vibration feedback
│   ├── ShareCard.kt         # renders & shares the result-card image
│   └── Prefs.kt             # best score, most heads, FX, daily streak
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
