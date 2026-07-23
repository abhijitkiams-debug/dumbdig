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
        '<div class="lc-head-id"><span class="lc-avatar">🪙</span>' +
          '<div><div class="lc-head-name">' + esc(this.brand.name) + '</div>' +
          '<div class="lc-head-sub">Your lending assistant</div></div>' +
        '</div>' +
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
        '<div class="lc-chips"></div>' +
        '<div class="lc-input-row">' +
          '<input class="lc-input" type="text" placeholder="Tell me what you need… e.g. ‘5 lakh personal loan’" />' +
          '<button class="lc-send" title="Send">➤</button>' +
        '</div>' +
        '<div class="lc-foot">Native copilot layer · your app UI is untouched</div>' +
      '</div>';
    this.panel = panel;
    this.chatEl = panel.querySelector('.lc-chat');
    this.suggestEl = panel.querySelector('.lc-suggest');
    this.chipsEl = panel.querySelector('.lc-chips');
    this.inputEl = panel.querySelector('.lc-input');
    this.ringFg = panel.querySelector('.lc-ring-fg');
    this.ringNum = panel.querySelector('.lc-ring-num');

    panel.querySelector('.lc-close').addEventListener('click', function () { self.toggle(false); });
    panel.querySelector('.lc-send').addEventListener('click', function () { self._submit(); });
    this.inputEl.addEventListener('keydown', function (e) { if (e.key === 'Enter') self._submit(); });

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
    if (this.open) { this._refreshStatus(); this.inputEl.focus(); this._scroll(); }
  };

  /* ================= chat rendering ================= */

  Copilot.prototype._say = function (html, who) {
    var msg = el('div', 'lc-msg lc-' + (who || 'bot'));
    msg.innerHTML = who === 'user' ? esc(html) : html;
    this.chatEl.appendChild(msg);
    this._scroll();
    return msg;
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
    this.handle(text);
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
      b.addEventListener('click', function () { self.handle(c[1]); });
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
    this._say('🎉 <b>Submitted!</b> Your application is in. A credit officer will reach out on your mobile shortly. Reference: <b>LC-' +
      Math.random().toString(36).slice(2, 8).toUpperCase() + '</b>');
  };

  /* ================= public factory ================= */

  global.LendCopilot = {
    init: function (config) { return new Copilot(config); }
  };
})(typeof window !== 'undefined' ? window : this);
