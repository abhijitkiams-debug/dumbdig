/*
 * copilot.js — the embeddable "LendCopilot" SDK.
 *
 * Design goal: sit INSIDE the host lending app and drive form completion,
 * suggest next actions and recommend products — WITHOUT touching the client's
 * own UI. It achieves that two ways:
 *
 *   1) It renders its own UI into a self-contained, namespaced layer
 *      (all classes prefixed `lc-`, mounted in its own root element).
 *   2) It reads and writes the host form only through a formal ADAPTER
 *      bridge (getValue / setValue / focusField ...), never by reaching into
 *      the client's DOM widgets. Swap the host app and the SDK is unchanged.
 *
 * Usage:
 *   LendCopilot.init({ schema, adapter, catalog, nlu, brand });
 *
 * Dependencies: LendingCatalog (products.js) and LendCopilotNLU (nlu.js),
 * passed in via config so the SDK stays decoupled.
 */
(function (global) {
  'use strict';

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  /*
   * Prompt sets. The default is Hinglish (Hindi in Devanagari with the natural
   * English financial terms Indians actually use) — spoken via Sarvam hi-IN TTS.
   * English is authored too. Other languages fall back to English + translation.
   */
  var PROMPTS = {
    hi: {
      brandName: 'आर्या',
      welcome: 'नमस्ते! मैं आर्या हूँ, Setu Finance की loan assistant। आप मुझसे बात कर सकते हैं — mic दबाकर बोलिए, या keyboard से type कीजिए। बताइए, आपको किस चीज़ के लिए loan चाहिए?',
      greet: 'नमस्ते! 👋 मैं <b>आर्या</b> हूँ, आपकी loan assistant। आप मुझसे <b>बात</b> कर सकते हैं (mic दबाइए) या <b>type</b> कर सकते हैं। पहले कुछ छोटे सवाल पूछूँगी, फिर आपके लिए सही loan suggest करूँगी। बताइए, आपको किस चीज़ के लिए loan चाहिए?',
      help: 'कोई बात नहीं — बस अपनी ज़रूरत बताइए। Loan का type, amount, और monthly income बता दीजिए, बाकी मैं देख लूँगी।',
      gotIt: function (labels) { return 'ठीक है, ' + labels + ' note कर लिया। '; },
      askMore: 'थोड़ा और बताइए — जैसे loan type, amount, या income।',
      ask: {
        loanType: 'आपको कौन सा loan चाहिए — personal, home, gold, business, car, two-wheeler या education?',
        monthlyIncome: 'आपकी monthly income कितनी है? जैसे, साठ हज़ार रुपये महीना।',
        employment: 'आप salaried हैं, self-employed, या business owner?',
        cibil: 'आपका CIBIL score कितना है? इससे आपका interest rate तय होता है। अगर पता नहीं, तो "skip" बोलिए।',
        amount: 'आपको कितने का loan चाहिए? जैसे, पाँच लाख।',
        fullName: 'आपका पूरा नाम क्या है, PAN card के अनुसार?',
        mobile: 'आपका दस अंकों का mobile number बताइए।',
        age: 'आपकी उम्र कितनी है?',
        pan: 'आपका PAN number क्या है?',
        city: 'आप किस city में रहते हैं?',
        consent: 'आख़िरी step — क्या आप अपनी details verify करने और credit report check करने की अनुमति देते हैं? हाँ बोलिए तो मैं submit कर दूँ।'
      },
      labels: { loanType: 'loan type', monthlyIncome: 'income', employment: 'employment', amount: 'amount', fullName: 'नाम', mobile: 'mobile number', age: 'उम्र', pan: 'PAN', city: 'city', consent: 'consent' },
      recommendIntro: 'आपकी जानकारी के अनुसार, ये रहे आपके सबसे अच्छे options:',
      recommendTop: function (name, amt, rate, emi) {
        return name + ' आपके लिए सबसे अच्छा है — ' + amt + ' तक, ' + rate + ' percent पर, EMI लगभग ' + emi + ' महीना। क्या मैं इसके लिए आपकी application शुरू करूँ? हाँ बोलिए।';
      },
      afterPick: function (name) { return 'बढ़िया! मैंने आपको ' + name + ' के लिए select कर लिया। अब बस कुछ details चाहिए। '; },
      pickedIntro: function (name) { return 'बढ़िया choice! ' + name + ' के लिए, आपका best rate जानने के लिए मुझे थोड़ी जानकारी चाहिए। आपकी monthly income कितनी है?'; },
      submitAsk: 'बस हो गया! क्या मैं आपकी application submit कर दूँ? हाँ बोलिए।',
      submitted: function (ref) { return 'हो गया! आपकी application submit हो गई। Reference ' + ref + '। एक credit officer आपको जल्दी call करेगा। Setu Finance चुनने के लिए धन्यवाद!'; },
      didntCatch: 'माफ़ कीजिए, समझ नहीं आया। दोबारा बोलिए या नीचे type कीजिए।',
      notCaught: 'माफ़ कीजिए, मैं ठीक से समझ नहीं पाई।',
      changeWhat: 'कोई बात नहीं। आप क्या बदलना चाहते हैं?',
      switched: 'ठीक है, मैं हिंदी में बात करती हूँ।'
    },
    en: {
      brandName: 'Arya',
      welcome: 'Namaste! I\'m Arya, your loan assistant at Setu Finance. You can talk to me — tap the mic — or type. I\'ll ask a couple of quick questions and then suggest the right loan. To begin, what do you need the loan for?',
      greet: 'Namaste! 👋 I\'m <b>Arya</b>, your loan assistant. You can <b>talk</b> (tap the mic) or <b>type</b>. I\'ll ask a couple of quick questions, then suggest the best loan for you. To start, what do you need the loan for?',
      help: 'Sure — just describe your situation. Tell me the loan type, amount and monthly income, and I\'ll do the rest.',
      gotIt: function (labels) { return 'Got it — updated ' + labels + '. '; },
      askMore: 'Tell me a bit more — loan type, amount, or income works great.',
      ask: {
        loanType: 'Which loan are you after — personal, home, gold, business, car, two-wheeler or education?',
        monthlyIncome: 'What\'s your monthly income? (say “60k a month” or “12 LPA”)',
        employment: 'Are you salaried, self-employed or a business owner?',
        cibil: 'What\'s your CIBIL score? It decides your interest rate. If you don\'t know, just say “skip”.',
        amount: 'How much would you like to borrow? (e.g. “5 lakh”)',
        fullName: 'What\'s your full name as per PAN?',
        mobile: 'What\'s your 10-digit mobile number?',
        age: 'How old are you?',
        pan: 'Could you share your PAN? (format ABCDE1234F)',
        city: 'Which city are you in?',
        consent: 'Last step — do you allow us to verify your details and check your credit report? Say yes and I\'ll submit.'
      },
      labels: { loanType: 'loan type', monthlyIncome: 'income', employment: 'employment', amount: 'amount', fullName: 'full name', mobile: 'mobile number', age: 'age', pan: 'PAN', city: 'city', consent: 'consent' },
      recommendIntro: 'Based on what you told me, here are your best options:',
      recommendTop: function (name, amt, rate, emi) {
        return 'A ' + name + ' looks best for you — up to ' + amt + ' at ' + rate + ' percent, EMI around ' + emi + ' a month. Shall I start your application for it? Say yes.';
      },
      afterPick: function (name) { return 'Great choice! I\'ve set you up for a ' + name + '. Now I just need a few details. '; },
      pickedIntro: function (name) { return 'Great choice! For a ' + name + ', I need a few details to get your best rate. What\'s your monthly income?'; },
      submitAsk: 'That\'s everything! Shall I submit your application now? Say yes.',
      submitted: function (ref) { return 'Done! Your application is submitted. Reference ' + ref + '. A credit officer will call you shortly. Thank you for choosing Setu Finance!'; },
      didntCatch: 'Sorry, I didn\'t catch that. Please say it again or type below.',
      notCaught: 'Sorry, I didn\'t quite get that.',
      changeWhat: 'No problem. What would you like to change?',
      switched: 'Sure, I\'ll continue in English.'
    }
  };

  function Copilot(config) {
    this.schema = config.schema || [];
    this.adapter = config.adapter;
    this.catalog = config.catalog;
    this.nlu = config.nlu;
    this.brand = config.brand || { name: 'LendCopilot', accent: '#4f46e5' };
    this.open = false;
    this.pendingField = null;     // field the copilot last asked for
    this.lastFilled = [];         // for the Undo action
    this.schemaById = {};
    var self = this;
    this.schema.forEach(function (f) { self.schemaById[f.id] = f; });

    // voice state
    this.voice = (typeof LendVoice !== 'undefined') ? LendVoice : null;
    this.voiceOn = !!(this.voice && this.voice.isSupported());
    this.phase = 'idle';
    this._firstOpen = true;
    this._lastBotText = '';
    this._speakBuffer = '';
    this._collecting = false;
    this._convDone = false;
    this._inCall = false;
    this._callConnected = false;
    this._audioObserved = false;
    this.autostart = !!config.autostart;
    this.convLang = (config.brand && config.brand.language) || 'hi-IN'; // default Hindi
    this.langLocked = true;   // stick to one language unless the user asks to switch
    this.stage = 'discovery'; // discovery -> recommend -> application
    this.awaitingProductPick = false;
    this.awaitingSubmit = false;
    this._cibilAsked = false;
    // Add ?debug=1 to the URL to see, after each voice turn, exactly what STT
    // heard, the detected language, the English the NLU received, and whether a
    // field was filled — so a "captured but not recognised" case is diagnosable.
    this._debug = /[?&]debug=1\b/.test((global.location && global.location.search) || '');
    if (this.voice) this.voice.setLang(this.convLang);

    this._build();
    this._greet();
    // Keep the progress ring live if the user edits the client form directly,
    // and — when the user answers the very field Arya is waiting on by typing or
    // picking from the dropdown — accept it and move the conversation forward.
    if (this.adapter.onChange) this.adapter.onChange(function (id) {
      self._refreshStatus();
      if (self._selfWrite) return; // ignore the copilot's own writes
      self._onHostEdit(id);
    });

    // Welcome the user by voice as soon as they land.
    if (this.autostart && this.voice && this.voice.isSupported()) {
      setTimeout(function () { self._welcome(); }, 400);
    }
  }

  /* ================= i18n ================= */

  Copilot.prototype._uiLang = function () {
    return this.convLang === 'hi-IN' ? 'hi' : 'en';
  };
  Copilot.prototype._P = function () { return PROMPTS[this._uiLang()] || PROMPTS.en; };
  // Fetch a prompt string (or call a prompt function) for the current language.
  Copilot.prototype._t = function (key) {
    var p = this._P();
    var v = p[key];
    if (typeof v === 'function') return v.apply(null, Array.prototype.slice.call(arguments, 1));
    return v != null ? v : (PROMPTS.en[key] || '');
  };

  /* ================= profile derived from the live form ================= */

  Copilot.prototype._profile = function () {
    var a = this.adapter;
    var num = function (v) { var n = parseFloat(String(v).replace(/[^\d.]/g, '')); return isNaN(n) ? null : n; };
    return {
      fullName: a.getValue('fullName'),
      loanType: a.getValue('loanType') || null,
      amount: num(a.getValue('amount')),
      monthlyIncome: num(a.getValue('monthlyIncome')),
      existingEmi: num(a.getValue('existingEmi')) || 0,
      employment: a.getValue('employment') || null,
      cibil: num(a.getValue('cibil')),
      age: num(a.getValue('age'))
    };
  };

  /* ================= completion tracking ================= */

  Copilot.prototype._status = function () {
    var self = this, done = 0, req = 0, missing = [];
    this.schema.forEach(function (f) {
      if (!f.required) return;
      req++;
      var v = self.adapter.getValue(f.id);
      if (v != null && String(v).trim() !== '') done++;
      else missing.push(f);
    });
    return { done: done, req: req, pct: req ? Math.round((done / req) * 100) : 0, missing: missing };
  };

  /* ================= UI construction (own namespaced layer) ================= */

  Copilot.prototype._build = function () {
    var self = this;
    var root = el('div', 'lc-root');
    root.style.setProperty('--lc-accent', this.brand.accent);
    this.root = root;

    // Floating launcher — a Siri-style orb with a clear white mic glyph on top.
    var fab = el('button', 'lc-fab',
      '<span class="lc-orb-glow lc-fab-glow"></span>' +
      '<span class="lc-fab-ico"><svg viewBox="0 0 24 24" width="26" height="26" fill="#fff" aria-hidden="true">' +
        '<path d="M12 15a3 3 0 0 0 3-3V6a3 3 0 1 0-6 0v6a3 3 0 0 0 3 3z"/>' +
        '<path d="M19 12a7 7 0 0 1-14 0H3a9 9 0 0 0 8 8.94V24h2v-3.06A9 9 0 0 0 21 12h-2z"/></svg></span>' +
      '<span class="lc-fab-dot"></span>');
    fab.title = 'Talk to ' + this.brand.name;
    fab.addEventListener('click', function () { self._fabTap(); });
    this.fab = fab;

    // Panel
    var panel = el('div', 'lc-panel');
    panel.innerHTML =
      '<div class="lc-head">' +
        '<div class="lc-head-id">' +
          '<span class="lc-orb"><span class="lc-orb-glow"></span><span class="lc-orb-core"></span></span>' +
          '<div><div class="lc-head-name">' + esc(this.brand.name) + '</div>' +
          '<div class="lc-head-sub">Your lending assistant</div></div>' +
        '</div>' +
        '<button class="lc-icobtn lc-voicebtn" title="Voice on/off">🔊</button>' +
        '<button class="lc-icobtn lc-setbtn" title="Voice settings">⚙</button>' +
        '<div class="lc-ring" aria-label="application completion"><svg viewBox="0 0 44 44">' +
          '<circle class="lc-ring-bg" cx="22" cy="22" r="18"/>' +
          '<circle class="lc-ring-fg" cx="22" cy="22" r="18"/></svg>' +
          '<span class="lc-ring-num">0%</span></div>' +
        '<button class="lc-close" title="Close">✕</button>' +
      '</div>' +
      '<div class="lc-body">' +
        '<div class="lc-suggest"></div>' +
        '<div class="lc-chat"></div>' +
      '</div>' +
      '<div class="lc-composer">' +
        '<div class="lc-voice-status"><span class="lc-dot"></span><span class="lc-voice-cap">Listening…</span></div>' +
        '<div class="lc-chips"></div>' +
        '<div class="lc-input-row">' +
          '<input class="lc-input" type="text" placeholder="Tell me what you need…" />' +
          '<button class="lc-send" title="Send">➤</button>' +
          '<button class="lc-mic" title="Tap to talk">🎙️</button>' +
        '</div>' +
        '<div class="lc-foot">Voice-enabled · your app UI is untouched</div>' +
      '</div>';
    this.panel = panel;
    this.chatEl = panel.querySelector('.lc-chat');
    this.suggestEl = panel.querySelector('.lc-suggest');
    this.chipsEl = panel.querySelector('.lc-chips');
    this.inputEl = panel.querySelector('.lc-input');
    this.ringFg = panel.querySelector('.lc-ring-fg');
    this.ringNum = panel.querySelector('.lc-ring-num');
    this.micBtn = panel.querySelector('.lc-mic');
    this.voiceBtn = panel.querySelector('.lc-voicebtn');
    this.voiceCap = panel.querySelector('.lc-voice-cap');

    panel.querySelector('.lc-close').addEventListener('click', function () { self.toggle(false); });
    panel.querySelector('.lc-send').addEventListener('click', function () { self._submit(); });
    this.inputEl.addEventListener('keydown', function (e) { if (e.key === 'Enter') self._submit(); });
    this.micBtn.addEventListener('click', function () { self._micTap(); });
    this.voiceBtn.addEventListener('click', function () { self._toggleVoice(); });
    panel.querySelector('.lc-setbtn').addEventListener('click', function () { self._openSettings(); });

    // hide voice affordances entirely if the browser can't do voice at all
    if (!this.voice || !this.voice.isSupported()) {
      this.micBtn.style.display = 'none';
      this.voiceBtn.style.display = 'none';
    }
    this._reflectVoiceBtn();

    // ===== Siri-style call bar (the primary voice surface, mockup-style) =====
    var callbar = el('div', 'lc-callbar');
    callbar.innerHTML =
      '<span class="lc-orb lc-orb-lg" title="Open chat"><span class="lc-orb-glow"></span><span class="lc-orb-core"></span></span>' +
      '<div class="lc-call-mid">' +
        '<div class="lc-call-name">' + esc(this.brand.name) + '</div>' +
        '<div class="lc-call-cap">00:00</div>' +
      '</div>' +
      '<button class="lc-call-lang" title="Language">EN</button>' +
      '<button class="lc-call-kb" title="Type instead">⌨️</button>' +
      '<button class="lc-call-mute" title="Tap to talk">🎙️</button>' +
      '<button class="lc-call-end" title="Disconnect Arya">⏻<span class="lc-end-txt">Disconnect</span></button>';
    this.callbar = callbar;
    this.callName = callbar.querySelector('.lc-call-name');
    this.callCap = callbar.querySelector('.lc-call-cap');
    this.callLangBtn = callbar.querySelector('.lc-call-lang');
    this.callMuteBtn = callbar.querySelector('.lc-call-mute');
    callbar.querySelector('.lc-orb-lg').addEventListener('click', function () { self.toggle(true); });
    callbar.querySelector('.lc-call-kb').addEventListener('click', function () { self.toggle(true); });
    this.callCap.style.cursor = 'pointer';
    this.callCap.title = 'Voice settings';
    this.callCap.addEventListener('click', function () { self._openSettings(); });
    callbar.querySelector('.lc-call-end').addEventListener('click', function () { self.endCall(); });
    this.callMuteBtn.addEventListener('click', function () { self._callMuteTap(); });
    this.callLangBtn.addEventListener('click', function () { self._cycleLang(); });
    root.appendChild(callbar);
    this._reflectLangBtn();

    root.appendChild(panel);
    root.appendChild(fab);
    document.body.appendChild(root);

    var C = 2 * Math.PI * 18;
    this.ringFg.style.strokeDasharray = C;
    this.ringFg.style.strokeDashoffset = C;
    this._ringCirc = C;
  };

  Copilot.prototype.toggle = function (force) {
    this.open = force == null ? !this.open : force;
    this.root.classList.toggle('lc-open', this.open);
    if (this.open) {
      this._refreshStatus();
      this._scroll();
      // First open is a user gesture — safe to auto-start the voice greeting.
      if (this._firstOpen) {
        this._firstOpen = false;
        if (this.voiceOn && this.voice) {
          var self = this;
          this._speak(this._lastBotText).then(function () { self._startListen(); });
        } else {
          this.inputEl.focus();
        }
      }
    } else {
      // Closing the chat panel shouldn't hang up an active call.
      if (!this._inCall) {
        if (this.voice) this.voice.stop();
        this._setPhase('idle');
      }
    }
  };

  /* ================= chat rendering ================= */

  Copilot.prototype._say = function (html, who) {
    var msg = el('div', 'lc-msg lc-' + (who || 'bot'));
    msg.innerHTML = who === 'user' ? esc(html) : html;
    this.chatEl.appendChild(msg);
    this._scroll();
    if (who !== 'user') {
      var plain = this._plainText(html);
      this._lastBotText = plain;
      if (this._collecting) {
        this._speakBuffer += (this._speakBuffer ? ' ' : '') + plain;
      } else if (this.voiceOn && (this.open || this._inCall)) {
        this._speak(plain);
      }
    }
    return msg;
  };

  // A muted, never-spoken diagnostic line (only shown in ?debug=1 mode).
  Copilot.prototype._sayDebug = function (text) {
    var msg = el('div', 'lc-msg lc-debug');
    msg.style.cssText = 'align-self:center;max-width:92%;background:#111827;color:#9ca3af;' +
      'font:11px/1.5 ui-monospace,Menlo,Consolas,monospace;padding:6px 10px;border-radius:8px;' +
      'white-space:pre-wrap;word-break:break-word;opacity:.9';
    msg.textContent = text;
    this.chatEl.appendChild(msg);
    this._scroll();
    return msg;
  };

  // Strip HTML tags + emoji so TTS reads clean text.
  Copilot.prototype._plainText = function (html) {
    var d = document.createElement('div');
    d.innerHTML = html;
    var t = d.textContent || d.innerText || '';
    // drop emoji / pictographs and collapse whitespace
    t = t.replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2190}-\u{21FF}\u{2B00}-\u{2BFF}️]/gu, '');
    return t.replace(/\s+/g, ' ').trim();
  };
  Copilot.prototype._scroll = function () {
    var b = this.panel.querySelector('.lc-body');
    requestAnimationFrame(function () { b.scrollTop = b.scrollHeight; });
  };

  Copilot.prototype._greet = function () { this._say(this._t('greet')); };

  // Spoken welcome — kept short and natural for TTS.
  Copilot.prototype._welcomeSpeech = function () { return this._t('welcome'); };

  /* ================= the conversation turn ================= */

  Copilot.prototype._submit = function () {
    var text = this.inputEl.value.trim();
    if (!text) return;
    this.inputEl.value = '';
    this._turn(text, false);
  };

  /* ================= voice ================= */

  // One conversational turn: process input, then (in voice mode) speak the
  // reply and — if it came from voice — listen again.
  Copilot.prototype._turn = function (text, fromVoice, displayText) {
    var self = this;
    this._collecting = true;
    this._speakBuffer = '';
    this.handle(text, displayText);
    this._collecting = false;
    var toSpeak = this._speakBuffer.trim();
    this._speakBuffer = '';
    var active = this.open || this._inCall;
    if (this.voiceOn && this.voice && active && toSpeak) {
      this._speak(toSpeak).then(function () {
        if (fromVoice && (self.open || self._inCall) && self.voiceOn && !self._convDone) self._startListen();
      });
    } else {
      this._setPhase('idle');
    }
  };

  Copilot.prototype._speak = function (text, onStart) {
    if (!text || !this.voice || !this.voiceOn) return Promise.resolve();
    this._setPhase('speaking');
    var self = this;
    var lang = this.convLang || 'hi-IN';
    // Authored prompts are already in the target language. Only translate when
    // the text is plain English but the conversation language isn't English.
    var hasDevanagari = /[ऀ-ॿ]/.test(text);
    var skip = (lang === 'en-IN') || (lang === 'hi-IN' && hasDevanagari);
    return this.voice.speak(text, lang, onStart, skip).then(function () {
      if (self.phase === 'speaking') self._setPhase('idle');
    }).catch(function () { self._setPhase('idle'); });
  };

  Copilot.prototype._startListen = function () {
    if (!this.voice || !this.voiceOn || !this.voice.canListen()) {
      this._setPhase('idle');
      this.inputEl.focus();
      return;
    }
    if (this._listening || this.phase === 'listening') return; // no overlapping captures
    var self = this;
    this._listening = true;
    this._callConnected = true; // the call is now active; timer runs
    this._setPhase('listening');
    this.voice.listen().then(function (res) {
      self._listening = false;
      if (!self.open && !self._inCall) { self._setPhase('idle'); return; }
      res = res || {};
      var english = (res.text || '').trim();     // for the NLU
      var shown = (res.display || res.text || '').trim(); // what they actually said
      // NOTE: we do NOT switch convLang to the detected language — Arya sticks to
      // one language (default Hindi) unless the customer explicitly asks to change.
      if (english) {
        self._emptyListens = 0;
        self._setPhase('thinking');
        var dbgId = self.pendingField && self.pendingField.id;
        var dbgBefore = dbgId ? self.adapter.getValue(dbgId) : null;
        self._turn(english, true, shown);
        if (self._debug) {
          var dbgAfter = dbgId ? self.adapter.getValue(dbgId) : null;
          var matched = (dbgId && dbgAfter && dbgAfter !== dbgBefore)
            ? '✓ ' + dbgId + ' = ' + dbgAfter
            : '✗ no field matched';
          self._sayDebug('🎤 heard: ' + shown + '\n🌐 lang: ' + (res.lang || '?') +
            '   → NLU: “' + english + '”\n' + matched);
        }
      } else {
        var lastErr = (self.voice.getLastError && self.voice.getLastError()) || '';
        // A hard error (mic blocked / insecure page) is not worth retrying — show it.
        var hardError = res.error || /secure|blocked|denied|permission/i.test(lastErr);
        if (self._inCall && !hardError) {
          // Silence or a brief miss: keep the call alive by listening again,
          // instead of going dead and forcing a manual tap.
          self._emptyListens = (self._emptyListens || 0) + 1;
          if (self._emptyListens <= 2) {
            self.voiceCap.textContent = 'Listening…';
            self._setPhase('idle');
            setTimeout(function () {
              if (self._inCall && !self._listening && self.phase === 'idle') self._startListen();
            }, 350);
            return;
          }
        }
        self._emptyListens = 0;
        self._setPhase('idle');
        self.voiceCap.textContent = hardError ? lastErr : 'Didn\'t catch that — tap 🎙️ to retry';
        self.root.classList.add('lc-thinking');
        setTimeout(function () { if (self.phase === 'idle') self.root.classList.remove('lc-thinking'); }, 1800);
      }
    }).catch(function () { self._listening = false; self._setPhase('idle'); });
  };

  Copilot.prototype._micTap = function () {
    if (!this.voice) return;
    if (this.voice.unlockAudio) this.voice.unlockAudio(); // iOS: unlock in the gesture
    if (this.phase === 'listening' || this.phase === 'speaking') {
      this.voice.stop();
      this._setPhase('idle');
    } else {
      if (!this.voiceOn) { this.voiceOn = true; this._reflectVoiceBtn(); }
      this._startListen();
    }
  };

  Copilot.prototype._toggleVoice = function () {
    this.voiceOn = !this.voiceOn;
    this._reflectVoiceBtn();
    if (!this.voiceOn) { if (this.voice) this.voice.stop(); this._setPhase('idle'); }
  };

  /* ---- the "call" experience (mockup-style bar) ---- */

  Copilot.prototype._fabTap = function () {
    // Voice-capable -> start a call; otherwise open the text chat panel.
    if (this.voice && this.voice.isSupported()) this.startCall();
    else this.toggle(true);
  };

  Copilot.prototype.startCall = function () {
    if (!this.voice || !this.voice.isSupported()) { this.toggle(true); return; }
    if (this.voice.unlockAudio) this.voice.unlockAudio(); // iOS: unlock in the gesture
    if (this._inCall) return;
    this._inCall = true;
    this._firstOpen = false;
    this.voiceOn = true;
    this._reflectVoiceBtn();
    this.root.classList.add('lc-incall');
    this._callStart = Date.now();
    this._startCallTimer();
    var self = this;
    // Greet in the conversation language, then start listening.
    this._speak(this._welcomeSpeech()).then(function () {
      if (self._inCall) self._startListen();
    });
  };

  Copilot.prototype.endCall = function () {
    this._inCall = false;
    this._callConnected = false;
    this.root.classList.remove('lc-incall');
    if (this._callTimer) { clearInterval(this._callTimer); this._callTimer = null; }
    if (this.voice) this.voice.stop();
    this._setPhase('idle');
  };

  Copilot.prototype._welcomeHint = function () {
    return this._uiLang() === 'hi' ? '🎙 बोलिए · ⌨ type कीजिए' : 'Tap 🎙 to talk · ⌨ to type';
  };

  // On landing we show the loan-listing (browse) screen — NOT a blocking call
  // overlay. We prime audio on the first interaction and try a soft autoplay
  // greeting; if the browser allows it, Arya promotes herself to a live call.
  // Otherwise the customer starts her explicitly via "Talk to Arya" / a loan card.
  Copilot.prototype._welcome = function () {
    var self = this;
    // Unlock audio on the first user interaction anywhere (non-blocking).
    var unlock = function () {
      if (self.voice && self.voice.unlockAudio) self.voice.unlockAudio();
      document.removeEventListener('pointerdown', unlock, true);
      document.removeEventListener('touchstart', unlock, true);
      document.removeEventListener('keydown', unlock, true);
    };
    document.addEventListener('pointerdown', unlock, true);
    document.addEventListener('touchstart', unlock, true);
    document.addEventListener('keydown', unlock, true);

    // Best-effort autoplay greeting (returning users / PWAs). If audio actually
    // starts, open the call bar and begin listening; if blocked, stay silent and
    // let the browse-screen CTAs start the voice.
    this._speak(this._welcomeSpeech(), function () {
      if (!self._inCall) {
        self._inCall = true; self._firstOpen = false; self.voiceOn = true; self._reflectVoiceBtn();
        self.root.classList.add('lc-incall'); self._callStart = Date.now();
        self._callConnected = true; self._startCallTimer();
      }
    }).then(function () {
      if (self._inCall && self.voiceOn) self._startListen();
    });
  };

  // The greeting produced no audio — tell the user exactly why, right on the bar.
  Copilot.prototype._voiceTrouble = function () {
    var err = (this.voice && this.voice.getLastError && this.voice.getLastError()) || '';
    var keyIssue = /key|403|missing|rejected/i.test(err);
    if (this.callCap) {
      this.callCap.textContent = '🔊 ' + (err ? err.slice(0, 44) : 'Voice unavailable — tap to fix');
    }
    if (keyIssue) {
      this._openSettings();   // let them see/fix the API key immediately
    } else if (this._inCall && this.voiceOn) {
      this._startListen();    // no audio but STT may work — keep the conversation going
    }
  };

  Copilot.prototype._callMuteTap = function () {
    if (this.voice && this.voice.unlockAudio) this.voice.unlockAudio(); // iOS unlock
    if (this.phase === 'listening' || this.phase === 'speaking') {
      if (this.voice) this.voice.stop();
      this._setPhase('idle');           // paused
    } else {
      this._startListen();
    }
  };

  Copilot.prototype._startCallTimer = function () {
    var self = this;
    if (this._callTimer) clearInterval(this._callTimer);
    this._callTimer = setInterval(function () {
      if (self._callConnected && self.phase === 'idle' && self.callCap) {
        var s = Math.floor((Date.now() - self._callStart) / 1000);
        var mm = String(Math.floor(s / 60)).padStart(2, '0');
        var ss = String(s % 60).padStart(2, '0');
        self.callCap.textContent = mm + ':' + ss;
      }
    }, 1000);
  };

  var LANG_CYCLE = [
    { code: 'hi-IN', label: 'हिं' }, { code: 'en-IN', label: 'EN' },
    { code: 'ta-IN', label: 'த' }, { code: 'te-IN', label: 'తె' },
    { code: 'bn-IN', label: 'বাং' }, { code: 'mr-IN', label: 'मरा' }
  ];
  Copilot.prototype._cycleLang = function () {
    var idx = 0;
    for (var i = 0; i < LANG_CYCLE.length; i++) if (LANG_CYCLE[i].code === this.convLang) { idx = i; break; }
    var next = LANG_CYCLE[(idx + 1) % LANG_CYCLE.length];
    this.convLang = next.code;
    if (this.voice) this.voice.setLang(next.code);
    this._reflectLangBtn();
    // acknowledge + re-ask the current question in the newly chosen language
    var line = this._t('switched');
    var nx = this._pendingOrNext();
    if (nx) line += ' ' + nx;
    this._say(line);
  };
  Copilot.prototype._reflectLangBtn = function () {
    if (!this.callLangBtn) return;
    var cur = LANG_CYCLE[0];
    for (var i = 0; i < LANG_CYCLE.length; i++) if (LANG_CYCLE[i].code === this.convLang) cur = LANG_CYCLE[i];
    this.callLangBtn.textContent = cur.label;
  };

  Copilot.prototype._reflectVoiceBtn = function () {
    if (!this.voiceBtn) return;
    this.voiceBtn.textContent = this.voiceOn ? '🔊' : '🔇';
    this.voiceBtn.classList.toggle('lc-on', this.voiceOn);
    this.voiceBtn.title = this.voiceOn ? 'Voice on' : 'Voice off';
  };

  Copilot.prototype._setPhase = function (p) {
    this.phase = p;
    var r = this.root;
    r.classList.remove('lc-listening', 'lc-speaking', 'lc-thinking');
    var cap = '';
    if (p === 'listening') { r.classList.add('lc-listening'); cap = 'Listening…'; }
    else if (p === 'speaking') { r.classList.add('lc-speaking'); cap = 'Speaking…'; }
    else if (p === 'thinking') { r.classList.add('lc-thinking'); cap = 'Thinking…'; }
    if (this.voiceCap && cap) this.voiceCap.textContent = cap;
    // call bar caption: live status, then timer once connected, else a hint
    if (this.callCap) {
      if (cap) this.callCap.textContent = cap;
      else if (p === 'idle' && this._inCall && !this._callConnected) this.callCap.textContent = this._welcomeHint();
      this.callMuteBtn && this.callMuteBtn.classList.toggle('lc-muted', p === 'idle');
    }
    if (p === 'listening' || p === 'speaking') this._startAmpLoop();
    else this._stopAmpLoop();
  };

  Copilot.prototype._startAmpLoop = function () {
    if (this._ampRaf) return;
    var self = this;
    var loop = function () {
      var amp = self.voice ? self.voice.getAmplitude() : 0;
      if (amp > 0.05) self._audioObserved = true; // TTS/mic actually producing sound
      if (self.phase === 'speaking' && amp < 0.02) {
        // speechSynthesis gives no amplitude — synthesise a gentle pulse
        amp = 0.25 + 0.2 * Math.abs(Math.sin(Date.now() / 180));
      }
      self.root.style.setProperty('--lc-amp', amp.toFixed(3));
      self._ampRaf = requestAnimationFrame(loop);
    };
    loop();
  };

  Copilot.prototype._stopAmpLoop = function () {
    if (this._ampRaf) cancelAnimationFrame(this._ampRaf);
    this._ampRaf = null;
    this.root.style.setProperty('--lc-amp', '0');
  };

  Copilot.prototype._openSettings = function () {
    if (!this.voice) return;
    var self = this;
    var back = el('div', 'lc-modal-back');
    back.innerHTML =
      '<div class="lc-modal">' +
        '<div class="lc-modal-t">Voice settings</div>' +
        '<label class="lc-modal-l">Sarvam API key (enables Sarvam STT/TTS)</label>' +
        '<input class="lc-modal-in lc-k" type="text" spellcheck="false" autocapitalize="off" placeholder="sk_…" />' +
        '<div class="lc-key-src"></div>' +
        '<div class="lc-modal-row" style="margin-top:8px"><button class="lc-btn lc-btn-ghost lc-clear" style="flex:1">Clear saved key</button></div>' +
        '<label class="lc-modal-l">Language</label>' +
        '<select class="lc-modal-in lc-lang">' +
          '<option value="en-IN">English (India)</option>' +
          '<option value="hi-IN">Hindi</option>' +
          '<option value="ta-IN">Tamil</option>' +
          '<option value="te-IN">Telugu</option>' +
          '<option value="bn-IN">Bengali</option>' +
          '<option value="mr-IN">Marathi</option>' +
          '<option value="kn-IN">Kannada</option>' +
          '<option value="gu-IN">Gujarati</option>' +
        '</select>' +
        '<div class="lc-modal-note">Stored only in this browser. Without a key, voice uses your browser\'s built-in speech.</div>' +
        '<div class="lc-modal-row"><button class="lc-btn lc-btn-ghost lc-test">🔊 Test voice</button>' +
        '<button class="lc-btn lc-btn-primary lc-save">Save</button></div>' +
        '<div class="lc-test-result"></div>' +
        '<div class="lc-modal-row" style="margin-top:8px"><button class="lc-btn lc-btn-ghost lc-cancel" style="flex:1">Close</button></div>' +
      '</div>';
    this.root.appendChild(back);
    var keyIn = back.querySelector('.lc-k');
    var langIn = back.querySelector('.lc-lang');
    keyIn.value = this.voice.getKey();
    langIn.value = this.convLang || 'en-IN';
    var resultEl = back.querySelector('.lc-test-result');
    var srcEl = back.querySelector('.lc-key-src');
    var refreshSrc = function () {
      var src = self.voice.keySource();
      var k = self.voice.getKey();
      srcEl.textContent = 'Active key: ' + (k ? (k.slice(0, 6) + '…' + k.slice(-3) + '  (' + src + ')') : 'none set');
    };
    refreshSrc();
    back.querySelector('.lc-clear').addEventListener('click', function () {
      self.voice.clearKey();
      keyIn.value = self.voice.getKey(); // falls back to voice-config.local.js if present
      refreshSrc();
      resultEl.textContent = 'Cleared the browser-saved key.';
      resultEl.className = 'lc-test-result';
    });
    var close = function () { back.remove(); };
    back.addEventListener('click', function (e) { if (e.target === back) close(); });
    back.querySelector('.lc-cancel').addEventListener('click', close);
    var applySettings = function () {
      self.voice.setKey(keyIn.value);
      self.voice.setLang(langIn.value);
      self.convLang = langIn.value;
      self.voiceOn = self.voice.isSupported();
      self._reflectVoiceBtn();
    };
    back.querySelector('.lc-save').addEventListener('click', function () { applySettings(); close(); });
    back.querySelector('.lc-test').addEventListener('click', function () {
      applySettings();
      refreshSrc();
      resultEl.textContent = 'Testing… (make sure your volume is up)';
      resultEl.className = 'lc-test-result';
      self.voice.test().then(function (r) {
        if (r.ok) {
          resultEl.textContent = '✓ Voice is working (' + r.provider + '). You should have heard it.';
          resultEl.className = 'lc-test-result lc-ok';
        } else {
          resultEl.textContent = '✕ ' + (r.error || 'No audio.');
          resultEl.className = 'lc-test-result lc-err';
        }
      });
    });
  };

  // Public: feed text (from input or a quick chip) into the copilot.
  // `displayText` (optional) is what the user actually said in their language;
  // `text` is always English for the NLU.
  Copilot.prototype.handle = function (text, displayText) {
    var self = this;
    this._say(displayText || text, 'user');

    // Language switch — only when the user explicitly asks (otherwise we stick).
    var sw = this._detectLangSwitch(text);
    if (sw && sw !== this.convLang) {
      this.convLang = sw;
      if (this.voice) this.voice.setLang(sw);
      this._reflectLangBtn();
      var ack = this._t('switched');
      var nx = this._pendingOrNext();
      if (nx) ack += ' ' + nx;
      this._say(ack);
      this._renderChips();
      return;
    }

    var res = this.nlu.parse(text);
    var actionIntents = ['recommend', 'eligibility', 'emi', 'documents', 'help', 'greet', 'affirm', 'deny'];
    var isAction = res.intents.some(function (i) { return actionIntents.indexOf(i) !== -1; });
    var consentAffirm = this.pendingField &&
      (this.pendingField.type === 'checkbox' || this.pendingField.id === 'consent') &&
      /^(yes|yeah|yep|sure|ok|okay|agree|accept|confirm|done|haan|ji|theek|thik)\b/i.test(text);

    // Fill logic, grounded to avoid mis-reading the customer:
    //  - a SHORT reply is treated as the answer to the current question first
    //    (so "5 lakh" fills the field Arya just asked, not a random amount slot);
    //  - longer sentences go through full multi-field extraction.
    var wordCount = text.trim().split(/\s+/).length;
    var shortAnswer = wordCount <= 6;
    var filled = [];
    if (this.pendingField && shortAnswer && (!isAction || consentAffirm)) {
      // Short reply = the answer to the current question. Try to slot it there.
      var slot = this._fillPending(text);
      if (slot) filled = [slot];
      else {
        // It didn't fit. Only extract STRONG, unambiguous entities (loan type,
        // employment, PAN…) — NEVER repurpose a bare number into another field.
        var strong = {};
        ['loanType', 'employment', 'pan', 'email', 'mobile', 'city', 'fullName', 'pincode', 'aadhaar']
          .forEach(function (k) { if (res.entities[k] != null) strong[k] = res.entities[k]; });
        filled = this._applyEntities(strong);
      }
    } else {
      // Longer / free-form utterance: full multi-field extraction.
      filled = this._applyEntities(res.entities);
      if (!filled.length && this.pendingField && (!isAction || consentAffirm)) {
        var slot2 = this._fillPending(text);
        if (slot2) filled = [slot2];
      }
    }
    // remember whether this turn actually captured the field we asked for
    this._answeredPending = filled.some(function (f) {
      return self.pendingField && f.id === self.pendingField.id;
    });

    this._refreshStatus();
    var reply = this._advance(res, filled);
    if (reply) this._say(reply);

    this._renderSuggestions();
    this._renderChips();

    if (res.intents.indexOf('emi') !== -1) this._renderEmi();
    if (res.intents.indexOf('documents') !== -1) this._renderDocs();
  };

  // Detect an explicit "switch language" request. Returns a lang code or null.
  Copilot.prototype._detectLangSwitch = function (text) {
    var t = text.toLowerCase();
    if (/\b(english|angrezi|angreji)\b|अंग्रे|इंग्लिश/.test(t)) {
      if (/(in english|english me|english mein|angrezi me|switch to english|speak english|talk in english|अंग्रे)/.test(t) || /^english$/.test(t.trim())) return 'en-IN';
    }
    if (/\b(hindi)\b|हिंदी|हिन्दी/.test(t)) {
      if (/(in hindi|hindi me|hindi mein|switch to hindi|speak hindi|हिंदी में|हिन्दी में)/.test(t) || /^hindi$/.test(t.trim())) return 'hi-IN';
    }
    return null;
  };

  // The current question to (re-)ask, e.g. after a language switch.
  Copilot.prototype._pendingOrNext = function () {
    var P = this._P();
    if (this.awaitingSubmit) return P.submitAsk;
    if (this.stage === 'recommend' && this.awaitingProductPick) return this._recommendText();
    var f = this.pendingField || this._nextBestField();
    if (f && P.ask[f.id]) { this.pendingField = f; return P.ask[f.id]; }
    return '';
  };

  // The stage machine: discovery -> recommend -> application -> submit.
  Copilot.prototype._advance = function (res, filled) {
    var P = this._P();
    var gotIt = filled.length ? P.gotIt(this._confirmText(filled)) : '';
    // When we asked a question and got no usable answer, say so (don't fake it).
    var lead = gotIt;
    if (!filled.length && this.pendingField && res.intents.length === 0) lead = (P.notCaught || '') + ' ';

    if (res.intents.indexOf('help') !== -1) return P.help;

    if (this.awaitingSubmit) {
      if (this._isAffirm(res)) { this._doSubmit(); return ''; }
      if (res.intents.indexOf('deny') !== -1) { this.awaitingSubmit = false; this.stage = 'application'; return P.changeWhat; }
    }

    // Explicit "recommend / show options" at any time -> open the reco screen.
    if ((res.intents.indexOf('recommend') !== -1 || res.intents.indexOf('eligibility') !== -1) &&
        this._profile().loanType) {
      this.stage = 'recommend';
      this.awaitingProductPick = true;
      this._offerProducts();
      return gotIt + this._recommendText();
    }

    // Recommend stage: waiting for a product choice.
    if (this.stage === 'recommend' && this.awaitingProductPick) {
      var picked = this._resolvePick(res);
      if (picked) return gotIt + this._pickProduct(picked);
      if (filled.length) { this._offerProducts(); return gotIt + this._recommendText(); } // new info -> re-rank
      return this._recommendText(); // just re-ask, no duplicate cards
    }

    // Discovery stage: ask the couple of questions.
    if (this.stage === 'discovery') {
      var nextD = this._nextBestField();
      if (nextD) {
        if (nextD.id === 'cibil') this._cibilAsked = true; // ask CIBIL exactly once
        this.pendingField = nextD;
        return lead + P.ask[nextD.id];
      }
      // discovery complete -> move to recommendations
      this.stage = 'recommend';
      this.awaitingProductPick = true;
      this._offerProducts();
      return gotIt + this._recommendText();
    }

    // Application stage: collect the remaining details, then submit.
    var nextA = this._nextBestField();
    if (nextA) { this.pendingField = nextA; return lead + P.ask[nextA.id]; }
    this.awaitingSubmit = true;
    return gotIt + P.submitAsk;
  };

  // Confirm the ACTUAL captured value(s) so the customer can catch a mishearing.
  Copilot.prototype._confirmText = function (filled) {
    var self = this, C = this.catalog;
    var parts = filled.map(function (f) {
      var v = self.adapter.getValue(f.id);
      if (f.id === 'loanType') { var p = C.byId(v); return p ? p.name : v; }
      if (f.id === 'employment') {
        var opts = (self.schemaById.employment && self.schemaById.employment.options) || [];
        for (var i = 0; i < opts.length; i++) if (opts[i].value === v) return opts[i].label;
        return v;
      }
      if (['amount', 'monthlyIncome', 'existingEmi'].indexOf(f.id) !== -1) {
        return C.inr(parseFloat(String(v).replace(/[^\d.]/g, '')));
      }
      if (f.id === 'cibil') return 'CIBIL ' + v;
      return v;
    });
    return parts.join(', ');
  };

  Copilot.prototype._isAffirm = function (res) {
    return res.intents.indexOf('affirm') !== -1 || res.intents.indexOf('apply') !== -1;
  };

  // Localised, comma-joined field labels for the "got it" confirmation.
  Copilot.prototype._joinLabels = function (filled) {
    var P = this._P();
    var names = filled.map(function (f) { return P.labels[f.id] || f.label; });
    var and = this._uiLang() === 'hi' ? ' और ' : ' and ';
    if (names.length === 1) return names[0];
    return names.slice(0, -1).join(', ') + and + names[names.length - 1];
  };

  // Spoken recommendation: a crisp one-line summary of EACH of the top products,
  // then an apply prompt. (Arya speaks each product, briefly.)
  Copilot.prototype._recommendText = function () {
    var recs = (this._recs && this._recs.length) ? this._recs : this.catalog.recommend(this._profile()).slice(0, 3);
    this._recs = recs;
    if (!recs.length) return this._P().askMore;
    var C = this.catalog, hi = this._uiLang() === 'hi';
    var ord = hi ? ['पहला', 'दूसरा', 'तीसरा'] : ['First', 'Second', 'Third'];
    var lines = recs.slice(0, 3).map(function (r, i) {
      var n = r.product.name;
      return hi
        ? ord[i] + ', ' + n + ' — ' + C.inrShort(r.maxEligible) + ' तक, ' + r.rate.toFixed(1) + '% पर, EMI लगभग ' + C.inr(r.emi) + ' महीना।'
        : ord[i] + ', ' + n + ' — up to ' + C.inrShort(r.maxEligible) + ' at ' + r.rate.toFixed(1) + '%, EMI about ' + C.inr(r.emi) + ' a month.';
    });
    var ask = hi ? 'कौन सा पसंद है? नाम बोलिए, या "पहला" बोलिए।' : 'Which one would you like? Say its name, or say "first".';
    return this._t('recommendIntro') + ' ' + lines.join(' ') + ' ' + ask;
  };

  // Resolve which product the user chose in the recommend stage.
  Copilot.prototype._resolvePick = function (res) {
    var recs = this._recs || [];
    if (res.entities && res.entities.loanType) {
      for (var i = 0; i < recs.length; i++) if (recs[i].product.id === res.entities.loanType) return recs[i];
    }
    var t = (res.text || '').toLowerCase();
    if (/\b(first|1st|top|number one|this one)\b/.test(t)) return recs[0] || null;
    if (/\b(second|2nd)\b/.test(t)) return recs[1] || null;
    if (/\b(third|3rd)\b/.test(t)) return recs[2] || null;
    if (this._isAffirm(res)) return recs[0] || null;
    return null;
  };

  /* ---- write extracted entities into the host form via the adapter ---- */

  Copilot.prototype._applyEntities = function (entities) {
    var self = this, filled = [];
    var map = {
      fullName: 'fullName', mobile: 'mobile', email: 'email', pan: 'pan',
      aadhaar: 'aadhaar', city: 'city', pincode: 'pincode', age: 'age',
      employment: 'employment', monthlyIncome: 'monthlyIncome', cibil: 'cibil',
      loanType: 'loanType', amount: 'amount', tenureMonths: 'tenure'
    };
    Object.keys(map).forEach(function (k) {
      if (entities[k] == null) return;
      var fieldId = map[k];
      if (!self.schemaById[fieldId]) return;
      if (!self._plausible(fieldId, entities[k])) return; // ignore implausible values
      var prev = self.adapter.getValue(fieldId);
      self._write(fieldId, entities[k]);
      if (self.adapter.flashField) self.adapter.flashField(fieldId);
      filled.push({ id: fieldId, label: self.schemaById[fieldId].label, value: entities[k], prev: prev });
    });
    if (filled.length) this.lastFilled = filled;
    return filled;
  };

  // Resolve a free-text answer to one of a select field's option VALUES.
  // Order of preference: (1) the NLU entity for that field (authoritative for
  // employment/loanType — it understands "I run a shop" -> business, phrasing,
  // synonyms); (2) exact value/label match; (3) the answer contains an option
  // value/label or a distinctive label word ("professional" -> self-employed).
  // Normalisation folds hyphens/spaces/case so "self employed", "Self-Employed"
  // and "self-employed" all resolve to the same option.
  Copilot.prototype._matchOption = function (f, raw) {
    var norm = function (s) { return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim(); };
    var v = norm(raw);
    if (!v) return null;

    // (1) Let the NLU decide for the smart fields it knows about.
    if ((f.id === 'employment' || f.id === 'loanType') && this.nlu && this.nlu.parse) {
      var ent = (this.nlu.parse(raw).entities || {})[f.id];
      if (ent) {
        for (var a = 0; a < f.options.length; a++) if (f.options[a].value === ent) return ent;
      }
    }

    // (2) + (3) match against option values/labels.
    for (var i = 0; i < f.options.length; i++) {
      var o = f.options[i];
      if (!o.value) continue; // skip the "Select…" placeholder
      var ov = norm(o.value), ol = norm(o.label);
      if (v === ov || v === ol) return o.value;                 // exact
      if (ov && (v.indexOf(ov) !== -1 || ov.indexOf(v) !== -1)) return o.value; // contains
      var words = ol.split(' ');
      for (var w = 0; w < words.length; w++) {
        if (words[w].length > 3 && v.indexOf(words[w]) !== -1) return o.value; // label word
      }
    }
    return null;
  };

  // Interpret a bare answer for the field we just asked about.
  Copilot.prototype._fillPending = function (text) {
    var f = this.pendingField;
    if (!f) return null;
    var val = text.trim();
    if (val.length > 60) return null; // a sentence, not a field value
    if (f.type === 'checkbox' || f.id === 'consent') {
      if (!/^(yes|yeah|yep|sure|ok|okay|agree|i agree|accept|confirm|done|haan)\b/i.test(val)) return null;
      val = true;
    } else if (f.type === 'select' && f.options) {
      var matched = this._matchOption(f, val);
      if (matched != null) val = matched; else return null;
    } else if (f.numeric) {
      var amt = this.nlu.parseAmount(text);
      var n = amt != null ? amt : parseFloat(val.replace(/[^\d.]/g, ''));
      if (isNaN(n)) return null;
      if (!this._plausible(f.id, n)) return null; // reject implausible -> re-ask
      val = n;
    } else if (!this._validText(f.id, val)) {
      return null; // don't drop a stray question into a name/city field
    }
    var prev = this.adapter.getValue(f.id);
    this._write(f.id, val);
    if (this.adapter.flashField) this.adapter.flashField(f.id);
    this.lastFilled = [{ id: f.id, label: f.label, value: val, prev: prev }];
    this.pendingField = null;
    return { id: f.id, label: f.label, value: val, prev: prev };
  };

  // The user edited the host form directly (typed a value or picked from a
  // <select>). If it's the field Arya is currently waiting on, treat it as the
  // answer: confirm it and advance — so a dropdown pick is accepted just like a
  // spoken or typed reply, instead of Arya re-asking the same question.
  Copilot.prototype._onHostEdit = function (id) {
    if (!id || !this._inCall) return;
    var f = this.schemaById[id];
    if (!f) return;
    if (!this.pendingField || this.pendingField.id !== id) return;
    // Only auto-advance on a discrete, deliberate pick (dropdown / checkbox).
    // Text & number inputs fire on every keystroke, so advancing on them would
    // interrupt the user mid-typing — those are captured via the chat/voice.
    if (f.type !== 'select' && f.type !== 'checkbox' && f.id !== 'consent') return;
    var v = this.adapter.getValue(id);
    if (v == null || String(v).trim() === '') return;
    this.pendingField = null;
    this._answeredPending = true;
    var filled = [{ id: id, label: f.label, value: v }];
    this.lastFilled = filled;
    this._refreshStatus();
    var reply = this._advance({ intents: [], entities: {}, text: '' }, filled);
    if (reply) this._say(reply);
    this._renderSuggestions();
    this._renderChips();
  };

  // All copilot-initiated writes go through here so the host onChange handler
  // can tell them apart from a user directly editing the form (dropdown/typing).
  Copilot.prototype._write = function (id, val) {
    this._selfWrite = true;
    try { this.adapter.setValue(id, val); }
    finally { this._selfWrite = false; }
  };

  // Numeric plausibility so a mis-heard number never silently fills a field.
  // Only numeric fields get bounds; text/select fields (employment, loanType,
  // city, PAN…) are always "plausible" here — validated elsewhere. Previously
  // this returned false for any non-numeric value, which silently dropped every
  // employment/loanType answer that arrived through the NLU path.
  var NUMERIC_FIELDS = { monthlyIncome: 1, amount: 1, age: 1, cibil: 1, existingEmi: 1 };
  Copilot.prototype._plausible = function (id, v) {
    if (!NUMERIC_FIELDS[id]) return true; // non-numeric field: no numeric bound
    var n = parseFloat(String(v).replace(/[^\d.]/g, ''));
    if (isNaN(n)) return false;
    if (id === 'monthlyIncome') return n >= 3000 && n <= 5000000;
    if (id === 'amount') return n >= 1000 && n <= 100000000;
    if (id === 'age') return n >= 18 && n <= 75;
    if (id === 'cibil') return n >= 300 && n <= 900;
    if (id === 'existingEmi') return n >= 0 && n <= 5000000;
    return true;
  };

  // Light plausibility check so free text answers only fill fields they fit.
  Copilot.prototype._validText = function (id, val) {
    var words = val.split(/\s+/).length;
    if (id === 'fullName') return /^[a-z][a-z .']{1,40}$/i.test(val) && words <= 4;
    if (id === 'city') return /^[a-z][a-z .'-]{1,30}$/i.test(val) && words <= 3;
    if (id === 'pincode') return /^[1-9]\d{5}$/.test(val);
    if (id === 'purpose') return words <= 10;
    return true;
  };

  // Next question to ask — scoped to the current stage so discovery only asks
  // the couple of qualifying questions before recommending products.
  // Discovery includes CIBIL (optional but rate-defining) — asked once.
  var DISCOVERY_ORDER = ['loanType', 'monthlyIncome', 'employment', 'cibil'];
  var APPLICATION_ORDER = ['amount', 'fullName', 'mobile', 'age', 'pan', 'city', 'consent'];
  Copilot.prototype._nextBestField = function () {
    var order = this.stage === 'discovery' ? DISCOVERY_ORDER : APPLICATION_ORDER;
    for (var i = 0; i < order.length; i++) {
      var id = order[i];
      var f = this.schemaById[id];
      if (!f) continue;
      var v = this.adapter.getValue(id);
      var empty = v == null || String(v).trim() === '';
      if (!empty) continue;
      if (id === 'cibil' && this._cibilAsked) continue; // optional — ask only once
      return f;
    }
    return null;
  };

  Copilot.prototype._askFor = function (f) {
    var prompts = {
      loanType: 'Which loan are you after — personal, home, gold, business, car, two-wheeler or education?',
      amount: 'How much would you like to borrow? (e.g. “5 lakh” or “₹2,50,000”)',
      monthlyIncome: 'What\'s your monthly income? (say “60k a month” or “12 LPA”)',
      employment: 'Are you salaried, self-employed or a business owner?',
      fullName: 'What\'s your full name as per PAN?',
      mobile: 'What\'s your 10-digit mobile number?',
      age: 'How old are you?',
      pan: 'Could you share your PAN? (format ABCDE1234F)',
      city: 'Which city are you in?',
      pincode: 'What\'s your 6-digit pincode?',
      email: 'What email should we use for updates?',
      aadhaar: 'And your 12-digit Aadhaar number? (kept masked)',
      cibil: 'Do you know your CIBIL score? It helps me get you a better rate.',
      tenure: 'Over how many months would you like to repay?',
      consent: 'Last step — please tick the consent box so I can submit.'
    };
    return prompts[f.id] || ('Could you provide your ' + f.label.toLowerCase() + '?');
  };

  /* ================= status ring ================= */

  Copilot.prototype._refreshStatus = function () {
    var st = this._status();
    var off = this._ringCirc * (1 - st.pct / 100);
    this.ringFg.style.strokeDashoffset = off;
    this.ringNum.textContent = st.pct + '%';
    this.root.classList.toggle('lc-complete', st.pct === 100);
  };

  /* ================= NATIVE suggestion & action cards ================= */

  Copilot.prototype._renderSuggestions = function () {
    var self = this;
    var st = this._status();
    this.suggestEl.innerHTML = '';

    // Completion nudge card (the "drive completion rate" engine)
    var nudge = el('div', 'lc-card lc-nudge');
    var next = this._nextBestField();
    if (st.pct === 100) {
      nudge.innerHTML = '<div class="lc-card-t">✅ Application complete</div>' +
        '<div class="lc-card-s">All required details are in. You\'re ready to submit.</div>';
      var submit = el('button', 'lc-btn lc-btn-primary', 'Review & submit');
      submit.addEventListener('click', function () { self._doSubmit(); });
      nudge.appendChild(submit);
    } else {
      nudge.innerHTML = '<div class="lc-card-t">📈 ' + st.done + ' of ' + st.req + ' done · ' + st.pct + '%</div>' +
        '<div class="lc-card-s">' + (next ? 'Next: <b>' + esc(next.label) + '</b>. ' + this._microMotivator(st.pct) : 'Almost there!') + '</div>';
    }
    this.suggestEl.appendChild(nudge);

    // Undo card if we just auto-filled something
    if (this.lastFilled && this.lastFilled.length) {
      var undo = el('div', 'lc-card lc-undo');
      undo.innerHTML = '<div class="lc-card-s">I auto-filled ' +
        this.lastFilled.map(function (f) { return '<b>' + esc(f.label) + '</b>'; }).join(', ') + '.</div>';
      var ub = el('button', 'lc-btn lc-btn-ghost', 'Undo');
      ub.addEventListener('click', function () { self._undo(); });
      undo.appendChild(ub);
    }
  };

  Copilot.prototype._microMotivator = function (pct) {
    if (pct >= 80) return 'Just one or two more!';
    if (pct >= 50) return 'You\'re over halfway. 💪';
    if (pct >= 25) return 'Great start — keep going.';
    return 'This takes under a minute with me.';
  };

  Copilot.prototype._undo = function () {
    var self = this;
    (this.lastFilled || []).forEach(function (f) {
      self._write(f.id, f.prev == null ? '' : f.prev);
    });
    this.lastFilled = [];
    this._say('Reverted. What should it be instead?');
    this._refreshStatus();
    this._renderSuggestions();
    this._renderChips();
  };

  /* ---- quick-reply chips: context-aware actions ---- */

  Copilot.prototype._renderChips = function () {
    var self = this;
    this.chipsEl.innerHTML = '';
    var chips = [];
    var next = this._nextBestField();

    if (next && next.id === 'loanType') {
      chips = [['Personal loan', 'I want a personal loan'], ['Home loan', 'home loan'],
        ['Gold loan', 'gold loan'], ['Business loan', 'business loan'], ['Car loan', 'car loan']];
    } else if (next && next.id === 'employment') {
      chips = [['Salaried', 'I am salaried'], ['Self-employed', 'I am self-employed'], ['Business owner', 'I own a business']];
    } else if (next && next.id === 'amount') {
      chips = [['₹1 lakh', '1 lakh'], ['₹5 lakh', '5 lakh'], ['₹10 lakh', '10 lakh'], ['₹25 lakh', '25 lakh']];
    } else if (next && next.id === 'monthlyIncome') {
      chips = [['₹30k/mo', '30k per month'], ['₹60k/mo', '60k per month'], ['₹1L/mo', '1 lakh per month']];
    } else {
      chips = [['Which product suits me?', 'recommend a product for me'],
        ['Check my eligibility', 'how much am i eligible for'],
        ['Estimate my EMI', 'what will my emi be'],
        ['What documents?', 'what documents do i need']];
    }
    chips.forEach(function (c) {
      var b = el('button', 'lc-chip', esc(c[0]));
      b.addEventListener('click', function () { self._turn(c[1], false); });
      self.chipsEl.appendChild(b);
    });
  };

  /* ================= product recommendation cards ================= */

  Copilot.prototype._renderProducts = function () {
    var self = this;
    var profile = this._profile();
    var recs = this.catalog.recommend(profile).slice(0, 3);
    this._recs = recs;
    var wrap = el('div', 'lc-msg lc-bot lc-products');
    wrap.innerHTML = '<div class="lc-prod-h">' + esc(this._t('recommendIntro')) + '</div>';

    recs.forEach(function (r, i) {
      var p = r.product;
      var card = el('div', 'lc-prod' + (i === 0 ? ' lc-prod-top' : ''));
      var badge = i === 0 ? '<span class="lc-tag">Top pick</span>' : '';
      var eligLine = r.eligible
        ? 'Eligible up to <b>' + self.catalog.inrShort(r.maxEligible) + '</b>'
        : '<span class="lc-warn">Needs a few more details</span>';
      card.innerHTML =
        '<div class="lc-prod-head"><span class="lc-prod-emoji">' + p.emoji + '</span>' +
          '<div class="lc-prod-name">' + esc(p.name) + badge + '</div></div>' +
        '<div class="lc-prod-blurb">' + esc(p.blurb) + '</div>' +
        '<div class="lc-prod-grid">' +
          '<div><span>Rate</span><b>' + r.rate.toFixed(2) + '% p.a.</b></div>' +
          '<div><span>' + eligLine + '</span></div>' +
          '<div><span>Est. EMI</span><b>' + self.catalog.inr(r.emi) + '/mo</b></div>' +
          '<div><span>Tenure</span><b>' + Math.round(r.tenureMonths / 12 * 10) / 10 + ' yr</b></div>' +
        '</div>';
      var pick = el('button', 'lc-btn lc-btn-primary lc-prod-btn',
        (self._uiLang() === 'hi' ? 'Apply करें: ' : 'Apply for ') + p.name);
      pick.addEventListener('click', function () { self._pickFromCard(r); });
      card.appendChild(pick);
      wrap.appendChild(card);
    });
    this.chatEl.appendChild(wrap);
    this._scroll();
  };

  /* ---- native full-screen recommendation overlay (quotes-style) ---- */

  Copilot.prototype._offerProducts = function () {
    var recs = this.catalog.recommend(this._profile()).slice(0, 4);
    this._recs = recs;
    this._renderProducts();     // cards in the chat panel too
    this._showRecoScreen(recs); // the native overlay screen
  };

  Copilot.prototype._recoLabels = function () {
    if (this._uiLang() === 'hi') {
      return { title: 'आपके लिए Best Loans', sub: 'Arya की सलाह — आपकी profile के हिसाब से',
        eligible: 'Eligible amount', rate: 'ब्याज दर', emi: 'EMI', tenure: 'अवधि', month: '/महीना',
        apply: 'Apply करें', talk: 'Arya से बात करें', best: 'Best Match', fast: 'तुरंत approval',
        low: 'सबसे कम rate', yr: 'साल', perAnnum: '% सालाना', totalInt: 'कुल ब्याज', close: 'बंद करें' };
    }
    return { title: 'Recommended for you', sub: 'Arya’s picks based on your profile',
      eligible: 'Eligible amount', rate: 'Interest rate', emi: 'EMI', tenure: 'Tenure', month: '/mo',
      apply: 'Apply', talk: 'Talk to Arya', best: 'Best Match', fast: 'Fast approval',
      low: 'Lowest rate', yr: 'yr', perAnnum: '% p.a.', totalInt: 'Total interest', close: 'Close' };
  };

  Copilot.prototype._showRecoScreen = function (recs) {
    var self = this, C = this.catalog, L = this._recoLabels();
    if (this._reco) this._reco.remove();
    var reco = el('div', 'lc-reco');

    // header
    var head = '<div class="lc-reco-head">' +
      '<button class="lc-reco-back" title="' + esc(L.close) + '">‹</button>' +
      '<div><div class="lc-reco-title">' + esc(L.title) + '</div>' +
      '<div class="lc-reco-sub">' + esc(L.sub) + '</div></div></div>';

    // profile summary chips
    var p = this._profile();
    var chips = [];
    if (p.loanType) { var lp = C.byId(p.loanType); if (lp) chips.push(lp.emoji + ' ' + lp.name); }
    if (p.monthlyIncome) chips.push('💰 ' + C.inrShort(p.monthlyIncome) + L.month);
    if (p.employment) chips.push('💼 ' + p.employment);
    if (p.cibil) chips.push('📊 CIBIL ' + p.cibil);
    var strip = '<div class="lc-reco-strip">' + chips.map(function (c) { return '<span class="lc-reco-chip">' + esc(c) + '</span>'; }).join('') + '</div>';

    // cards
    var cards = recs.map(function (r, i) {
      var pr = r.product;
      var tags = [];
      if (i === 0) tags.push('<span class="lc-reco-tag lc-tag-best">' + esc(L.best) + '</span>');
      if (!pr.secured) tags.push('<span class="lc-reco-tag lc-tag-fast">' + esc(L.fast) + '</span>');
      var yrs = Math.round(r.tenureMonths / 12 * 10) / 10;
      return '<div class="lc-reco-card' + (i === 0 ? ' lc-reco-top' : '') + '" data-idx="' + i + '">' +
        '<div class="lc-reco-crow">' +
          '<span class="lc-reco-emoji">' + pr.emoji + '</span>' +
          '<div class="lc-reco-name">Setu ' + esc(pr.name) + '<div class="lc-reco-tags">' + tags.join('') + '</div></div>' +
        '</div>' +
        '<div class="lc-reco-grid">' +
          '<div><span>' + esc(L.eligible) + '</span><b>' + C.inrShort(r.maxEligible) + '</b></div>' +
          '<div><span>' + esc(L.rate) + '</span><b>' + r.rate.toFixed(2) + esc(L.perAnnum) + '</b></div>' +
          '<div><span>' + esc(L.tenure) + '</span><b>' + yrs + ' ' + esc(L.yr) + '</b></div>' +
        '</div>' +
        '<div class="lc-reco-foot">' +
          '<div class="lc-reco-emi"><span>' + esc(L.emi) + '</span><b>' + C.inr(r.emi) + '<i>' + esc(L.month) + '</i></b></div>' +
          '<button class="lc-reco-apply" data-idx="' + i + '">' + esc(L.apply) + ' ›</button>' +
        '</div>' +
        '<div class="lc-reco-note">' + esc(pr.blurb) + '</div>' +
      '</div>';
    }).join('');

    reco.innerHTML = head + strip + '<div class="lc-reco-list">' + cards + '</div>' +
      '<div class="lc-reco-bar"><button class="lc-reco-talk">🎙️ ' + esc(L.talk) + '</button></div>';

    reco.querySelector('.lc-reco-back').addEventListener('click', function () { self._hideRecoScreen(); });
    reco.querySelector('.lc-reco-talk').addEventListener('click', function () {
      self._hideRecoScreen();
      if (self.voiceOn && self.voice) self._startListen();
    });
    reco.querySelectorAll('.lc-reco-apply').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var idx = parseInt(btn.getAttribute('data-idx'), 10);
        var r = recs[idx];
        self._hideRecoScreen();
        self._pickFromCard(r);
      });
    });

    this.root.appendChild(reco);
    this._reco = reco;
    this.root.classList.add('lc-reco-open');
  };

  Copilot.prototype._hideRecoScreen = function () {
    this.root.classList.remove('lc-reco-open');
    if (this._reco) { this._reco.remove(); this._reco = null; }
  };

  // One-tap apply from a product card -> move into the application stage.
  Copilot.prototype._pickFromCard = function (r) {
    var text = this._pickProduct(r);
    this._say(text);
    this._refreshStatus();
    this._renderSuggestions();
    this._renderChips();
    if (this.voiceOn && (this._inCall || this.open) && this.voice) this._startListen();
  };

  // Set the chosen product, switch to application stage, return the next prompt.
  Copilot.prototype._pickProduct = function (r) {
    this._write('loanType', r.productId);
    if (this.adapter.flashField) this.adapter.flashField('loanType');
    if (!this.adapter.getValue('amount') && r.maxEligible) {
      this._write('amount', Math.min(r.maxEligible, r.product.maxAmount));
      if (this.adapter.flashField) this.adapter.flashField('amount');
    }
    if (this.schemaById.tenure && !this.adapter.getValue('tenure')) {
      this._write('tenure', r.tenureMonths);
    }
    this.stage = 'application';
    this.awaitingProductPick = false;
    this._refreshStatus();
    // Take the user from the browse/reco screen to the application form.
    if (this.adapter.showApplication) this.adapter.showApplication();
    var P = this._P();
    var next = this._nextBestField();
    if (next) { this.pendingField = next; return P.afterPick(r.product.name) + P.ask[next.id]; }
    this.awaitingSubmit = true;
    return P.afterPick(r.product.name) + P.submitAsk;
  };

  // Called by the host when a loan card is tapped on the browse screen: set the
  // loan type and start the voice flow straight at the requirement questions.
  Copilot.prototype.startFromBrowse = function (productId) {
    this._write('loanType', productId);
    this.stage = 'discovery';
    var name = (this.catalog.byId(productId) || {}).name || '';
    if (!this.voice || !this.voice.isSupported()) { this.toggle(true); return; }
    if (this.voice.unlockAudio) this.voice.unlockAudio();
    if (!this._inCall) {
      this._inCall = true; this._firstOpen = false; this.voiceOn = true; this._reflectVoiceBtn();
      this.root.classList.add('lc-incall'); this._callStart = Date.now(); this._startCallTimer();
    }
    this._started = true;
    if (this._startCatch) { this._startCatch.remove(); this._startCatch = null; }
    if (this.voice) this.voice.stop();
    this.pendingField = this.schemaById['monthlyIncome'];
    var self = this;
    var intro = this._t('pickedIntro', name);
    this._collecting = true; this._speakBuffer = '';
    this._say(intro);
    this._collecting = false;
    var toSpeak = this._speakBuffer.trim(); this._speakBuffer = '';
    this._speak(toSpeak).then(function () { if (self._inCall && self.voiceOn) self._startListen(); });
  };

  Copilot.prototype._renderEmi = function () {
    var self = this;
    var profile = this._profile();
    if (!profile.loanType || !profile.amount) {
      this._say('Tell me the loan type and amount and I\'ll estimate your EMI instantly.');
      return;
    }
    var r = this.catalog.assess(this.catalog.byId(profile.loanType), profile);
    this._say('For a <b>' + esc(r.product.name) + '</b> of <b>' + self.catalog.inr(profile.amount) +
      '</b> at <b>' + r.rate.toFixed(2) + '% p.a.</b> over <b>' + Math.round(r.tenureMonths) +
      ' months</b>:<br>• EMI ≈ <b>' + self.catalog.inr(r.emi) + '/month</b><br>• Total interest ≈ ' +
      self.catalog.inr(r.totalInterest) + '<br>• Total payable ≈ ' + self.catalog.inr(r.totalPayable));
  };

  Copilot.prototype._renderDocs = function () {
    var profile = this._profile();
    var p = profile.loanType ? this.catalog.byId(profile.loanType) : this.catalog.byId('personal');
    var list = p.docs.map(function (d) { return '<li>' + esc(d) + '</li>'; }).join('');
    this._say('For a <b>' + esc(p.name) + '</b> you\'ll typically need:<ul class="lc-docs">' + list + '</ul>');
  };

  /* ================= submit ================= */

  Copilot.prototype._doSubmit = function () {
    var st = this._status();
    if (st.pct < 100) {
      this.stage = 'application';
      var nf = this._nextBestField();
      this._say(nf ? this._P().ask[nf.id] : this._t('askMore'));
      return;
    }
    if (this.adapter.submit) this.adapter.submit();
    this._convDone = true;
    this.awaitingSubmit = false;
    var ref = 'LC-' + Math.random().toString(36).slice(2, 8).toUpperCase();
    this._say(this._t('submitted', ref));
  };

  /* ================= public factory ================= */

  global.LendCopilot = {
    init: function (config) { return new Copilot(config); }
  };
})(typeof window !== 'undefined' ? window : this);
