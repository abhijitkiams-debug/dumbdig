# Originality & Copyright Statement

**Astra: Ram vs Ravan** is an original work, written from scratch for this
project. It is intentionally built to be **free of any third-party copyrighted
material** so it can be published, modified, and sold without licensing
concerns.

## What makes this project copyright-clean

### 1. No imported art
There is not a single bitmap, sprite sheet, PNG, JPG, or SVG sourced from
anywhere. Every visual — Ram, Ravan's ten heads, every aayudha and astra, the
particles, the UI, and the share card — is drawn at runtime from primitive
shapes (`Canvas.drawCircle`, `drawPath`, `drawRect`, `drawArc`, `drawText`) or,
for the launcher icon, from hand-written vector `pathData`. See:
- `app/src/main/java/com/originalgames/astra/` (all rendering code)
- `app/src/main/res/drawable/ic_launcher_foreground.xml` (original vector icon)

### 2. No imported audio
All sound effects are **synthesized at runtime** as PCM sine waves with
envelopes — see `SoundManager.kt`. No `.wav`, `.mp3`, `.ogg`, or any other
audio file ships with the app.

### 3. No imported fonts
Text uses the device's built-in system typeface (`Typeface.DEFAULT_BOLD`).
No font files are bundled.

### 4. No game-engine or third-party game code
The game runs on a plain Android `SurfaceView` with a hand-written render loop.
Dependencies are limited to Google's own AndroidX libraries (`core-ktx`,
`appcompat`), which are licensed under Apache-2.0 and free to redistribute.

### 5. Original mechanic and presentation; public-domain source material
The **Ramayana** — including the characters *Ram* and *Ravan* and the divine
*astras* (Agneyastra, Vayavyastra, Nagastra, Brahmastra) — is an ancient epic in
the public domain; these names are shared cultural and religious heritage, not
anyone's protected property. The specific game mechanic ("drag to dodge a
ten-headed boss's projectile storm while auto-firing, severing heads for
roguelite boons and rotating divine specials"), the code, the art style, the
colour palette, the synthesized audio, and the name *Astra: Ram vs Ravan* were
all created for this project. Game *ideas* and *rules* are not protectable by
copyright; the specific expression here is all original.

## Third-party components and their licenses

| Component | Source | License | Notes |
|-----------|--------|---------|-------|
| AndroidX `core-ktx`, `appcompat` | Google | Apache-2.0 | Standard, redistributable |
| Gradle wrapper scripts (`gradlew`, `gradlew.bat`) | Gradle | Apache-2.0 | Standard build tooling |
| Kotlin stdlib | JetBrains | Apache-2.0 | Bundled by the Kotlin plugin |

All of the above are permissively licensed and impose no restriction on
shipping or selling the resulting app.

## A note on cultural respect
The Ramayana is sacred to many. This game is built as a heroic, celebratory
arcade tribute to the triumph of good over evil — the same spirit as Dussehra
and Vijayadashami — and avoids disrespectful or graphic depiction.

## Bottom line
You own the game content outright. You may rename it, reskin it, monetize it,
or publish it to any store. Just keep the `LICENSE` notice for the bits that
are covered by it (i.e. this project's own MIT grant), and retain the standard
Apache-2.0 notices that ship inside the AndroidX/Kotlin/Gradle artifacts.
