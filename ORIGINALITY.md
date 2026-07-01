# Originality & Copyright Statement

**Dhishoom** is an original work, written from scratch for this project. It is
intentionally built to be **free of any third-party copyrighted material** so
it can be published, modified, and sold without licensing concerns.

## What makes this project copyright-clean

### 1. No imported art
There is not a single bitmap, sprite sheet, PNG, JPG, or SVG sourced from
anywhere. Every visual — the fighters, the stage, the HUD, the on-screen
controls, the K.O. stars — is drawn at runtime from primitive shapes
(`Canvas.drawCircle`, `drawLine`, `drawRoundRect`, `drawPath`, `drawText`) or,
for the launcher icon, from hand-written vector `pathData`. See:
- `app/src/main/java/com/originalgames/dhishoom/` (all rendering code)
- `app/src/main/res/drawable/ic_launcher_foreground.xml` (original vector fist icon)

### 2. No imported audio
All sound effects — punches, kicks, the super, the round bell, K.O. — are
**synthesized at runtime** as PCM (sine sweeps plus filtered noise for impacts);
see `SoundManager.kt`. No `.wav`, `.mp3`, `.ogg`, or any other audio file ships
with the app.

### 3. No imported fonts
Text uses the device's built-in system typeface (`Typeface.DEFAULT_BOLD`).
No font files are bundled.

### 4. No game-engine or third-party game code
The game runs on a plain Android `SurfaceView` with a hand-written render loop.
The local multiplayer is built directly on the Java/Android networking classes
(`Socket`, `ServerSocket`, `DatagramSocket`, `BluetoothSocket`) — no networking
or matchmaking libraries. Dependencies are limited to Google's own AndroidX
libraries (`core-ktx`, `appcompat`), licensed under Apache-2.0.

### 5. Original mechanics, characters and presentation
The fighters (RANI, BHIM, VEER, NAAG, GARUD, TITAN), their names, colour
palettes, stats and personas, the "DHISHOOM" super, the round/combo systems, the
name *Dhishoom*, and the visual style were all created for this project. The
Tekken-style *genre* (a round-based versus fighter) and the akhada (wrestling-pit)
*flavour* are not protectable by copyright; the specific code, art, audio,
characters, and presentation here are all original expression. No third-party
characters, likenesses, logos, or assets from any existing game are used or
referenced.

## Third-party components and their licenses

| Component | Source | License | Notes |
|-----------|--------|---------|-------|
| AndroidX `core-ktx`, `appcompat` | Google | Apache-2.0 | Standard, redistributable |
| Gradle wrapper scripts (`gradlew`, `gradlew.bat`) | Gradle | Apache-2.0 | Standard build tooling |
| Kotlin stdlib | JetBrains | Apache-2.0 | Bundled by the Kotlin plugin |

All of the above are permissively licensed and impose no restriction on
shipping or selling the resulting app.

## Bottom line
You own the game content outright. You may rename it, reskin it, monetize it,
or publish it to any store. Just keep the `LICENSE` notice for the bits that
are covered by it (i.e. this project's own MIT grant), and retain the standard
Apache-2.0 notices that ship inside the AndroidX/Kotlin/Gradle artifacts.
