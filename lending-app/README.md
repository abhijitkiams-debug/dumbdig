# Setu Finance — a sample Indian lending app with an embedded copilot

A self-contained demo of a lending application for the **Indian market**, with an
in-app AI-style assistant (**"Saathi"**) that talks to the customer in plain
language, **understands** them, **fills the application form**, **suggests the
next action**, **drives the completion rate**, and **recommends the right lending
product** — all while rendering its own native UI **without touching the host
app's UI**.

No build step, no dependencies. Open `index.html` and it runs. It is
**mobile-first responsive** and **voice-enabled** (talk to the assistant, it
talks back).

## Mobile & voice

- **Responsive / mobile-first:** on phones the assistant becomes a full-screen
  bottom sheet, inputs use 16px text (no iOS auto-zoom), tap targets are ≥44px,
  the layout is single-column, and safe-area insets are respected. No horizontal
  scroll at any width.
- **Voice, Siri-style:** the assistant has an animated gradient **orb** that
  reacts to your voice while listening and glows while speaking. Tap 🎙️ to talk;
  it auto-starts a spoken greeting the first time you open it.
- **Speech providers:** if you add a **Sarvam** API key it uses Sarvam STT
  (`saarika`) + TTS (`bulbul`); otherwise it falls back to the browser's built-in
  **Web Speech API** (works keyless on mobile Chrome). If Sarvam is unreachable
  (e.g. CORS), it degrades to the browser engine automatically. There's always a
  **type** fallback too.

### Setting a Sarvam key (optional)

The key is **never committed**. Two ways:

1. **In-app:** open the assistant → tap ⚙ → paste the key (saved to your
   browser's `localStorage`).
2. **Local file:** `cp copilot-sdk/voice-config.local.js.example
   copilot-sdk/voice-config.local.js` and put your key in it. That file is
   git-ignored.

> Voice STT and `speechSynthesis` need a secure context — serve over `https://`
> or `http://localhost` (some browsers don't allow the mic on `file://`). A
> quick local server: `python3 -m http.server` inside `lending-app/`.

```
lending-app/
├── index.html                 # host app shell (loads everything)
├── assets/styles.css          # the CLIENT's own UI styles
├── src/
│   ├── host-app.js            # the lending app: renders the form + exposes schema/adapter
│   └── data/products.js       # Indian loan catalog + eligibility & EMI engine
└── copilot-sdk/               # the drop-in assistant (fully self-contained)
    ├── copilot.js             # orchestrator: conversation, form-filling, suggestions
    ├── copilot.css            # copilot's OWN namespaced (`lc-`) styles
    └── nlu.js                 # offline NLU: intent + Indian-context entity extraction
```

## Run it

Just open the file in a browser:

```bash
xdg-open lending-app/index.html      # Linux
open lending-app/index.html          # macOS
```

Then click the 💬 launcher at the bottom-right and type something like:

> *"I want a ₹5 lakh personal loan, I earn 60k a month, I'm salaried, age 30"*

Watch the form on the left fill itself, the completion ring climb, and product
recommendations appear.

## What the copilot does

| Capability | How it shows up |
|---|---|
| **Speaks to the customer** | Conversational chat panel with a friendly, Indian-context tone (Namaste, lakh/crore, ₹). |
| **Understands them** | Offline NLU extracts loan type, amount (lakh/crore/k), income (monthly vs LPA/CTC), employment, PAN, Aadhaar, mobile, email, city, pincode, CIBIL, age, tenure. |
| **Fills the form** | Extracted values are written into the host form through the adapter and flashed green. |
| **Suggests actions** | Context-aware quick-reply chips + a "next best field" engine that always asks for the highest-value missing detail first. |
| **Drives completion rate** | A live completion ring, milestone nudges ("you're over halfway 💪"), and an undo for anything it auto-filled. |
| **Recommends products** | An eligibility + EMI engine ranks 7 loan types for the customer's profile with rate, max eligible amount, EMI and tenure — one tap to apply. |

### Supported loan products (representative Indian ranges)

Personal · Home · Gold · Business · Two-Wheeler · Car · Education — each with its
own rate band, amount limits, tenure, minimum income/age and document checklist.

## The key design idea: native UI, zero impact on the client's UI

The whole point is that the copilot **sits inside the app but never modifies the
client's own UI**. It achieves that two ways:

1. **Its own isolated layer.** Every copilot element lives under a single
   `.lc-root` node and every class is `lc-`-prefixed, so its CSS cannot leak into
   or clobber the host's styles. The launcher, panel, cards and chips are all the
   copilot's own native UI.

2. **A formal adapter bridge — never direct DOM access.** The copilot reads and
   writes the host form only through a small contract:

   ```js
   LendCopilot.init({
     schema,     // declarative description of the form fields
     adapter,    // { getValue, setValue, focusField, flashField, onChange, submit }
     catalog,    // product / eligibility engine
     nlu,        // language understanding engine
     brand: { name: 'Saathi', accent: '#4f46e5' }
   });
   ```

   The copilot calls `adapter.setValue('amount', 500000)` — it does **not** reach
   into the client's `<input>` widgets. Swap the host app for a different one and
   the SDK is unchanged; that's what makes it a genuine drop-in.

The host app publishes exactly two things (`window.HostApp.schema` and
`window.HostApp.adapter`) and the assistant does the rest. Edits the user makes
directly in the form flow back to the copilot via `adapter.onChange`, so the
completion ring stays in sync either way.

## Try these phrases

- `home loan of 45 lakhs, my ctc is 18 LPA` → home loan + income ₹1.5L/mo
- `I need 2.5 lac for a bike, self employed, cibil 720, age 29`
- `my PAN is ABCDE1234F, mobile 9876543210, email raj@x.com, I live in Pune 411001`
- `recommend the best product for me`
- `what will my EMI be` · `what documents do I need`

## Notes

This is an illustrative demo. Rates, eligibility rules and the EMI/FOIR logic are
representative of the Indian market but simplified, and **nothing is sent anywhere
— all data stays in your browser.** "Setu Finance" and "Saathi" are fictional.
