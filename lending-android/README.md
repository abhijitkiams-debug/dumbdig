# Setu Finance (Android) — voice-first lending app with a Siri-style assistant

A native **Android** lending app for the Indian market with **Saathi**, a
voice-first AI assistant that sits *over* the app. On launch it **auto-starts**,
greets the customer, **asks their name**, and then guides onboarding by voice —
understanding free-form Indian-context speech, **filling the application form**,
recommending the right **lending product**, and driving completion to 100%.

Speech is powered by **Sarvam AI** (STT + TTS). The assistant UI is a
**Siri-inspired** animated gradient orb that breathes, glows while speaking, and
swells with the caller's voice while listening.

> Built with Jetpack Compose + Kotlin. The onboarding NLU and eligibility/EMI
> engine are Kotlin ports of the browser-tested logic in the sibling
> [`lending-app/`](../lending-app) web prototype.

## How it behaves

1. App opens straight into the loan form with the assistant overlay on top.
2. Saathi speaks: *"Namaste! Welcome to Setu Finance… may I know your name?"* and
   starts listening.
3. The customer just talks — *"I want a five lakh personal loan, I earn sixty
   thousand a month, I'm salaried"* — and the form fills itself.
4. Saathi asks for whatever's still missing (next-best-field), reads back EMIs,
   suggests products, and finally asks for consent and submits.
5. Everything is voice-first, with an always-available **type** fallback and a
   **minimize** button that collapses the assistant into a floating orb.

## Architecture

```
lending-android/
├── build.gradle                         # module config + Sarvam key injection
└── src/main/
    ├── AndroidManifest.xml              # RECORD_AUDIO + INTERNET
    ├── res/…                            # theme, colors, adaptive launcher icon
    └── java/com/setu/lending/
        ├── MainActivity.kt             # single-activity Compose host
        ├── domain/
        │   ├── FormModel.kt            # declarative field schema + completion
        │   └── Products.kt             # 7 loan products, eligibility + EMI engine
        ├── assistant/
        │   ├── Nlu.kt                  # offline intent + entity extraction (Indian context)
        │   └── AssistantViewModel.kt   # the voice conversation state machine
        ├── voice/
        │   ├── SarvamClient.kt         # Sarvam STT + TTS (OkHttp)
        │   ├── AudioRecorder.kt        # mic → 16kHz WAV + amplitude + silence endpointing
        │   ├── AudioPlayer.kt          # plays TTS WAV
        │   └── Settings.kt             # API key + voice settings (runtime + BuildConfig)
        └── ui/
            ├── LendingApp.kt           # app shell (form + overlay/floating orb)
            ├── LoanForm.kt             # the client UI form (assistant never edits widgets directly)
            ├── AssistantOverlay.kt     # the voice surface (orb, captions, cards, text fallback)
            ├── SiriOrb.kt              # the Siri-style animated orb
            └── Theme.kt
```

**Separation of concerns (like the web version):** the assistant only reads and
writes the form through the ViewModel's field API (`setField`, `values`) — it
never reaches into individual UI widgets — and it renders its own overlay layer.
The form UI is untouched by the assistant.

## Configure the Sarvam API key

The key is **never committed**. Provide it one of two ways:

**A) At build time** — add to `local.properties` (git-ignored) in the repo root:

```properties
SARVAM_API_KEY=sk_your_key_here
```

(or export `SARVAM_API_KEY` as an environment variable before building).

**B) At runtime** — open the app, tap the ⚙ icon in the assistant, and paste the
key. It's stored in the app's private `SharedPreferences` on that device only.

Without a key, voice is disabled but the whole flow still works by typing.

## Build & run

Requires **Android Studio** (or an Android SDK with `local.properties` pointing
at it via `sdk.dir`). This is a Gradle module of the repo:

```bash
./gradlew :lending-android:assembleDebug
# -> lending-android/build/outputs/apk/debug/lending-android-debug.apk

adb install -r lending-android/build/outputs/apk/debug/lending-android-debug.apk
```

- **minSdk 26**, targetSdk 34, Kotlin 1.9.24, AGP 8.5.2, Compose BOM 2024.09.
- Grant the **microphone** permission when prompted (needed for STT).

> Note: this module was authored in an environment without the Android SDK, so
> it was not compiled there. Build it in Android Studio; the NLU and
> eligibility/EMI logic are direct ports of the web version's browser-tested code.

## Sarvam endpoints used

- **STT** `POST https://api.sarvam.ai/speech-to-text` — multipart WAV, model
  `saarika:v2.5`, `language_code=unknown` (auto-detect Hindi/English/…).
- **TTS** `POST https://api.sarvam.ai/text-to-speech` — model `bulbul:v2`,
  speaker `anushka`, returns base64 WAV.
- Auth header on both: `api-subscription-key: <key>`.

Language and speaker are configurable in `Settings.kt` (defaults `en-IN` /
`anushka`).

## Notes

Fictional demo. Rates, products and eligibility are illustrative. Nothing is sent
anywhere except the audio you speak, which goes to Sarvam for transcription and
synthesis when you enable voice.
