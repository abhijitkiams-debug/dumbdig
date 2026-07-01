# Dhishoom 👊💥

**A quick, one-more-round Android fighting game — built from scratch, 100% original, zero copyrighted assets, and playable with no internet at all.**

Pick a fighter, dish out punches, kicks and a screen-shaking **DHISHOOM!** super,
and settle it in a best-of-3. A round is 60 seconds, so a whole match is over in
3–4 minutes — decide a winner and pass the phone. Play the bot solo, or link two
phones over **Bluetooth** or a **Wi-Fi hotspot** for local versus with no servers
in the middle.

```
   RANI                                   BHIM
  [====----]  60                    [=======-]
     ◖o                                   o◗
    /|                                    |\
    /|      ← ‹ › ▲            P K B ★ →  |\
```

---

## Why this design is built to spread

Viral, casual games share a small set of traits. Dhishoom is engineered around
every one of them:

| Viral trait | How Dhishoom delivers it |
|-------------|--------------------------|
| **Decides fast** | Best-of-3, 60s rounds → a clear win/lose in ~5 minutes, every time. |
| **Instant rematch** | Match over → one tap → fighting again. No loading, no menus in between. |
| **"One more round" loop** | Short, swingy fights; comebacks are always one super away. |
| **Play with the person next to you** | Local Bluetooth **or** Wi-Fi hotspot versus — the format that spreads hand-to-hand. |
| **Truly offline** | No accounts, no servers, no ads. Nothing you fight ever leaves the two phones. |
| **Readable at a glance** | Bold silhouettes, two-colour fighters, big K.O. moments — looks great in a clip. |
| **Bragging rights** | Career W/L and a **best-combo** record; a **SHARE** card baits the rematch. |
| **Roster to chase** | Six original fighters; three unlock as you rack up wins. |
| **Juice** | Hit sparks, screen shake, hit-flash, combo popups, synth SFX, haptics. |
| **Tiny** | ~690 KB release APK. The only benign permissions are Bluetooth/local-Wi-Fi for versus. |

## Gameplay

- **Move** with `‹` `›`, hop with `▲`.
- **P** punch (fast), **K** kick (heavy, more knockback), **B** block (chip damage only).
- **★ DHISHOOM** — your super. It charges as you land and take hits; when the meter
  is full the button lights gold. It hits hard, briefly armors your start-up, and
  knocks the opponent down.
- **Combos** — keep hitting a stunned opponent to stack an `xN COMBO`. Your best
  ever is saved and printed on the share card.
- **Win a round** by draining the enemy's health (**K.O.**) or leading on health
  when the clock hits zero (**TIME UP**). First to two rounds wins the match.

### Modes

- **Solo vs Bot** — three difficulties (Easy / Normal / Hard) that tune the bot's
  reaction speed, aggression and defence. Winning here builds your career and
  unlocks fighters.
- **Wi-Fi / Hotspot versus** — both phones on the same Wi-Fi, or one phone hosts a
  hotspot the other joins. The host is discovered **automatically** over the local
  network (a UDP beacon), so the joiner just taps *Join* — no typing IP addresses.
- **Bluetooth versus** — pair the two phones once in Android's Bluetooth settings,
  then *Host* on one and pick it from the paired list on the other.

### Roster

Six original fighters, each with its own speed / power / vitality mix:
**RANI** (fast), **BHIM** (heavy), **VEER** (all-round), and the unlockables
**NAAG**, **GARUD** and **TITAN**. The names and akhada (wrestling-pit) flavour
are theme only — no third-party characters or likenesses are used.

## Tech at a glance

- **Language:** 100% Kotlin
- **Rendering:** plain `SurfaceView` + a hand-written 60 fps render thread (no game engine)
- **Graphics:** every fighter, stage and UI element drawn procedurally with `Canvas`
  primitives — no image files
- **Audio:** punches, kicks, the super, the bell and K.O. are all synthesized at
  runtime as PCM (tones + filtered noise) — no audio files
- **Netcode:** host-authoritative. The host simulates the fight and streams compact
  world snapshots ~30×/s; the client streams its button inputs and renders what it's
  told. Same tiny protocol over both transports (Wi-Fi TCP + UDP discovery, and
  Bluetooth RFCOMM).
- **Dependencies:** only Google's AndroidX `core-ktx` + `appcompat` (Apache-2.0)
- **Min SDK:** 21 (Android 5.0) · **Target SDK:** 34 · **Orientation:** landscape

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/originalgames/dhishoom/
│   ├── MainActivity.kt      # immersive landscape host + Bluetooth permission bridge
│   ├── GameView.kt          # game loop, screens, round/match state machine, HUD, netcode glue
│   ├── Fighter.kt           # physics, fighting-game state machine, hit/hurt boxes, procedural art
│   ├── Roster.kt            # the six original fighter archetypes + unlock logic
│   ├── Ai.kt                # the bot: reads state, emits the same buttons a human would
│   ├── Input.kt             # the per-frame button bitmask shared by touch / bot / network
│   ├── ParticleSystem.kt    # pooled hit sparks & K.O. debris (no per-frame allocation)
│   ├── SoundManager.kt      # runtime PCM sound synthesis
│   ├── Haptics.kt           # crash-proof vibration feedback
│   ├── ShareCard.kt         # renders & shares the result-card image
│   ├── Prefs.kt             # career record, best combo, equipped fighter, FX
│   └── net/
│       ├── NetLink.kt       # transport-agnostic link, wire protocol & snapshot codec
│       ├── WifiLink.kt      # UDP auto-discovery + TCP host/join (Wi-Fi / hotspot)
│       └── BluetoothLink.kt # RFCOMM host/join over paired devices
└── res/                     # original vector fist launcher icon, theme, file_paths
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

### About the permissions

Dhishoom contacts **no internet server**. Android nonetheless requires the
`INTERNET` permission to open *any* socket — including the purely local one used
for hotspot versus — and requires `BLUETOOTH_CONNECT` (API 31+) to talk to a
paired phone. `CHANGE_WIFI_MULTICAST_STATE` lets the joiner hear the host's local
discovery beacon. All match data stays on the link between the two devices.

## Copyright

Dhishoom ships **no third-party art, audio, fonts, or game code**. Everything
visual and audible is generated at runtime from primitives. See
[`ORIGINALITY.md`](ORIGINALITY.md) for a full breakdown and
[`LICENSE`](LICENSE) for the MIT grant. You can publish, reskin, or sell this
freely.

## Ideas to push it further

- Networked rematch without returning to the menu
- Per-fighter unique supers and animations
- A "perfect round" (no damage taken) badge
- Round-start character intro animations
