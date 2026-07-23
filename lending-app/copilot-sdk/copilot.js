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
    if (this.voice && config.brand && config.brand.language) this.voice.setLang(config.brand.language);

    this._build();
    this._greet();
    // Keep the progress ring live if the user edits the client form directly.
    if (this.adapter.onChange) this.adapter.onChange(function () { self._refreshStatus(); });
  }

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

    // Floating launcher button
    var fab = el('button', 'lc-fab', '<span class="lc-fab-dot"></span>💬');
    fab.title = 'Ask ' + this.brand.name;
    fab.addEventListener('click', function () { self.toggle(); });
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
      if (this.voice) this.voice.stop();
      this._setPhase('idle');
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
      } else if (this.voiceOn && this.open) {
        this._speak(plain);
      }
    }
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

  Copilot.prototype._greet = function () {
    this._say(
      'Namaste! 👋 I\'m your lending assistant. Tell me what you need in plain language — ' +
      'like <em>“I want a ₹5 lakh personal loan, I earn 60k a month”</em> — and I\'ll fill the ' +
      'form for you, check eligibility and suggest the right product.'
    );
  };

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
  Copilot.prototype._turn = function (text, fromVoice) {
    var self = this;
    this._collecting = true;
    this._speakBuffer = '';
    this.handle(text);
    this._collecting = false;
    var toSpeak = this._speakBuffer.trim();
    this._speakBuffer = '';
    if (this.voiceOn && this.voice && this.open && toSpeak) {
      this._speak(toSpeak).then(function () {
        if (fromVoice && self.open && self.voiceOn && !self._convDone) self._startListen();
      });
    } else {
      this._setPhase('idle');
    }
  };

  Copilot.prototype._speak = function (text) {
    if (!text || !this.voice || !this.voiceOn) return Promise.resolve();
    this._setPhase('speaking');
    var self = this;
    return this.voice.speak(text).then(function () {
      if (self.phase === 'speaking') self._setPhase('idle');
    }).catch(function () { self._setPhase('idle'); });
  };

  Copilot.prototype._startListen = function () {
    if (!this.voice || !this.voiceOn || !this.voice.canListen()) {
      this._setPhase('idle');
      this.inputEl.focus();
      return;
    }
    var self = this;
    this._setPhase('listening');
    this.voice.listen().then(function (text) {
      if (!self.open) { self._setPhase('idle'); return; }
      if (text && text.trim()) {
        self._setPhase('thinking');
        self._turn(text.trim(), true);
      } else {
        self._setPhase('idle');
        self.voiceCap.textContent = 'Didn\'t catch that — tap 🎙️ to retry';
        self.root.classList.add('lc-thinking'); // keep the status bar visible briefly
        setTimeout(function () { if (self.phase === 'idle') self.root.classList.remove('lc-thinking'); }, 1800);
      }
    }).catch(function () { self._setPhase('idle'); });
  };

  Copilot.prototype._micTap = function () {
    if (!this.voice) return;
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
    if (p === 'listening') { r.classList.add('lc-listening'); this.voiceCap.textContent = 'Listening…'; }
    else if (p === 'speaking') { r.classList.add('lc-speaking'); this.voiceCap.textContent = 'Speaking…'; }
    else if (p === 'thinking') { r.classList.add('lc-thinking'); this.voiceCap.textContent = 'Thinking…'; }
    if (p === 'listening' || p === 'speaking') this._startAmpLoop();
    else this._stopAmpLoop();
  };

  Copilot.prototype._startAmpLoop = function () {
    if (this._ampRaf) return;
    var self = this;
    var loop = function () {
      var amp = self.voice ? self.voice.getAmplitude() : 0;
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
        '<label class="lc-modal-l">Sarvam API key (optional — enables Sarvam STT/TTS)</label>' +
        '<input class="lc-modal-in lc-k" type="password" placeholder="sk_…" />' +
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
        '<div class="lc-modal-row"><button class="lc-btn lc-btn-ghost lc-cancel">Cancel</button>' +
        '<button class="lc-btn lc-btn-primary lc-save">Save</button></div>' +
      '</div>';
    this.root.appendChild(back);
    var keyIn = back.querySelector('.lc-k');
    var langIn = back.querySelector('.lc-lang');
    keyIn.value = this.voice.getKey();
    langIn.value = (this.voice.getKey && this.brand.language) || 'en-IN';
    var close = function () { back.remove(); };
    back.addEventListener('click', function (e) { if (e.target === back) close(); });
    back.querySelector('.lc-cancel').addEventListener('click', close);
    back.querySelector('.lc-save').addEventListener('click', function () {
      self.voice.setKey(keyIn.value);
      self.voice.setLang(langIn.value);
      self.voiceOn = self.voice.isSupported();
      self._reflectVoiceBtn();
      close();
    });
  };

  // Public: feed text (from input or a quick chip) into the copilot.
  Copilot.prototype.handle = function (text) {
    this._say(text, 'user');
    var res = this.nlu.parse(text);
    var filled = this._applyEntities(res.entities);

    // If we were waiting for a specific field and the user gave a bare value,
    // try to slot it in — but NOT when the message is really a command/question
    // (e.g. "recommend a product"), which must not land in a text field.
    var actionIntents = ['recommend', 'eligibility', 'emi', 'documents', 'help', 'greet', 'affirm', 'deny'];
    var isAction = res.intents.some(function (i) { return actionIntents.indexOf(i) !== -1; });
    if (!filled.length && this.pendingField && !isAction) {
      var slotted = this._fillPending(text);
      if (slotted) filled = [slotted];
    }

    var reply = this._composeReply(res, filled);
    if (reply) this._say(reply);

    this._refreshStatus();
    this._renderSuggestions();
    this._renderChips();

    // If the user explicitly asked for products / eligibility / emi, surface them.
    if (res.intents.indexOf('recommend') !== -1 || res.intents.indexOf('eligibility') !== -1) {
      this._renderProducts();
    }
    if (res.intents.indexOf('emi') !== -1) this._renderEmi();
    if (res.intents.indexOf('documents') !== -1) this._renderDocs();
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
      var prev = self.adapter.getValue(fieldId);
      self.adapter.setValue(fieldId, entities[k]);
      if (self.adapter.flashField) self.adapter.flashField(fieldId);
      filled.push({ id: fieldId, label: self.schemaById[fieldId].label, value: entities[k], prev: prev });
    });
    if (filled.length) this.lastFilled = filled;
    return filled;
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
      var lower = val.toLowerCase();
      var opt = f.options.find(function (o) {
        return o.value && (o.value.toLowerCase() === lower || o.label.toLowerCase().indexOf(lower) !== -1);
      });
      if (opt) val = opt.value; else return null;
    } else if (f.numeric) {
      var amt = this.nlu.parseAmount(text);
      var n = amt != null ? amt : parseFloat(val.replace(/[^\d.]/g, ''));
      if (isNaN(n)) return null;
      val = n;
    } else if (!this._validText(f.id, val)) {
      return null; // don't drop a stray question into a name/city field
    }
    var prev = this.adapter.getValue(f.id);
    this.adapter.setValue(f.id, val);
    if (this.adapter.flashField) this.adapter.flashField(f.id);
    this.lastFilled = [{ id: f.id, label: f.label, value: val, prev: prev }];
    this.pendingField = null;
    return { id: f.id, label: f.label, value: val, prev: prev };
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

  /* ---- craft a natural reply ---- */

  Copilot.prototype._composeReply = function (res, filled) {
    var parts = [];
    if (filled.length) {
      var names = filled.map(function (f) {
        var v = f.value;
        return '<b>' + esc(f.label) + '</b>';
      });
      parts.push('Got it — updated ' + this._joinList(names) + '. ');
    }
    if (res.intents.indexOf('greet') !== -1 && !filled.length) {
      return 'Hi! What do you need funds for? You can say something like <em>“2 lakh for a bike”</em> or <em>“home loan”</em>.';
    }
    if (res.intents.indexOf('help') !== -1) {
      return 'Sure — just describe your situation and I\'ll do the paperwork. Try: your loan type, amount, monthly income and employment. I\'ll compute eligibility & EMI and pick the best product for you.';
    }
    if (res.intents.indexOf('deny') !== -1 && !filled.length) {
      return 'No problem. What would you like to change or add?';
    }

    // Otherwise, drive completion: ask for the next best missing field.
    var next = this._nextBestField();
    if (next) {
      this.pendingField = next;
      parts.push(this._askFor(next));
    } else if (filled.length) {
      parts.push('That\'s everything I need. 🎉 Tap <b>Review & submit</b> whenever you\'re ready — or ask me to check your best product.');
    } else if (!res.intents.length) {
      parts.push('Tell me a bit more — your loan type, amount, or income works great.');
    }
    return parts.join('');
  };

  Copilot.prototype._joinList = function (arr) {
    if (arr.length === 1) return arr[0];
    return arr.slice(0, -1).join(', ') + ' and ' + arr[arr.length - 1];
  };

  // Choose the highest-value missing field, prioritising the ones that unlock
  // eligibility (loan type -> amount -> income -> employment) then KYC.
  Copilot.prototype._nextBestField = function () {
    var priority = ['loanType', 'amount', 'monthlyIncome', 'employment', 'fullName',
      'mobile', 'age', 'pan', 'city', 'pincode', 'email', 'aadhaar', 'cibil', 'tenure', 'consent'];
    var st = this._status();
    var missingIds = {};
    st.missing.forEach(function (f) { missingIds[f.id] = f; });
    for (var i = 0; i < priority.length; i++) {
      if (missingIds[priority[i]]) return missingIds[priority[i]];
    }
    return st.missing[0] || null;
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
      self.adapter.setValue(f.id, f.prev == null ? '' : f.prev);
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
    var wrap = el('div', 'lc-msg lc-bot lc-products');
    wrap.innerHTML = '<div class="lc-prod-h">Best matches for you</div>';

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
      var pick = el('button', 'lc-btn lc-btn-primary lc-prod-btn', 'Apply for ' + p.name);
      pick.addEventListener('click', function () { self._pickProduct(r); });
      card.appendChild(pick);
      wrap.appendChild(card);
    });
    this.chatEl.appendChild(wrap);
    this._scroll();
  };

  Copilot.prototype._pickProduct = function (r) {
    this.adapter.setValue('loanType', r.productId);
    if (this.adapter.flashField) this.adapter.flashField('loanType');
    if (!this.adapter.getValue('amount') && r.maxEligible) {
      this.adapter.setValue('amount', Math.min(r.maxEligible, r.product.maxAmount));
      if (this.adapter.flashField) this.adapter.flashField('amount');
    }
    if (this.schemaById.tenure && !this.adapter.getValue('tenure')) {
      this.adapter.setValue('tenure', r.tenureMonths);
    }
    this._say('Great choice — I\'ve set you up for a <b>' + esc(r.product.name) + '</b> at <b>' + r.rate.toFixed(2) +
      '% p.a.</b> Let\'s finish the remaining details.');
    this._refreshStatus();
    this._renderSuggestions();
    this._renderChips();
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
      this._say('Almost — we still need <b>' + esc((this._nextBestField() || {}).label || 'a few fields') + '</b>.');
      return;
    }
    if (this.adapter.submit) this.adapter.submit();
    this._convDone = true;
    if (this.voice) this.voice.stop();
    this._say('🎉 <b>Submitted!</b> Your application is in. A credit officer will reach out on your mobile shortly. Reference: <b>LC-' +
      Math.random().toString(36).slice(2, 8).toUpperCase() + '</b>');
  };

  /* ================= public factory ================= */

  global.LendCopilot = {
    init: function (config) { return new Copilot(config); }
  };
})(typeof window !== 'undefined' ? window : this);
