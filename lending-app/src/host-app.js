/*
 * host-app.js — the HOST lending platform ("Setu Finance").
 *
 * A lender-style multi-screen flow:
 *   1) BROWSE   — a loan listing / marketplace (products with indicative rates).
 *   2) capture  — the voice agent (Arya) asks for loan type, income, employment
 *                 and CIBIL score (CIBIL drives the interest rate).
 *   3) recommend — Arya opens the native recommendation screen (personalised).
 *   4) APPLY    — the application form, shown only after a product is chosen.
 *
 * The client still exposes only a `schema` + `adapter` to the copilot; the
 * adapter additionally lets the copilot navigate screens (showApplication).
 */
(function () {
  'use strict';

  var Cat = window.LendingCatalog;

  /* ---------------- form schema (single source of truth) ---------------- */

  var SCHEMA = [
    { id: 'fullName', label: 'Full name', type: 'text', group: 'Personal details', required: true, placeholder: 'As per PAN' },
    { id: 'mobile', label: 'Mobile number', type: 'tel', group: 'Personal details', required: true, placeholder: '10-digit mobile', hint: 'We\'ll send an OTP' },
    { id: 'email', label: 'Email', type: 'email', group: 'Personal details', required: false, placeholder: 'you@email.com' },
    { id: 'age', label: 'Age', type: 'number', group: 'Personal details', required: true, numeric: true, placeholder: 'Years' },
    { id: 'city', label: 'City', type: 'text', group: 'Personal details', required: true, placeholder: 'e.g. Pune' },
    { id: 'pincode', label: 'Pincode', type: 'text', group: 'Personal details', required: false, placeholder: '6-digit' },

    { id: 'pan', label: 'PAN', type: 'text', group: 'KYC', required: true, placeholder: 'ABCDE1234F', hint: 'Permanent Account Number' },
    { id: 'aadhaar', label: 'Aadhaar', type: 'text', group: 'KYC', required: false, placeholder: '12-digit', hint: 'Stored masked' },

    {
      id: 'employment', label: 'Employment type', type: 'select', group: 'Employment & income', required: true,
      options: [
        { value: '', label: 'Select…' },
        { value: 'salaried', label: 'Salaried' },
        { value: 'self-employed', label: 'Self-employed / Professional' },
        { value: 'business', label: 'Business owner' },
        { value: 'student', label: 'Student' }
      ]
    },
    { id: 'monthlyIncome', label: 'Monthly income (₹)', type: 'number', group: 'Employment & income', required: true, numeric: true, placeholder: 'Net monthly' },
    { id: 'existingEmi', label: 'Existing EMIs (₹/mo)', type: 'number', group: 'Employment & income', required: false, numeric: true, placeholder: '0 if none' },
    { id: 'cibil', label: 'CIBIL score', type: 'number', group: 'Employment & income', required: false, numeric: true, placeholder: '300–900', hint: 'Decides your interest rate' },

    {
      id: 'loanType', label: 'Loan type', type: 'select', group: 'Loan requirement', required: true,
      options: [{ value: '', label: 'Select…' }].concat(Cat.PRODUCTS.map(function (p) { return { value: p.id, label: p.name }; }))
    },
    { id: 'amount', label: 'Loan amount (₹)', type: 'number', group: 'Loan requirement', required: true, numeric: true, placeholder: 'e.g. 500000' },
    { id: 'tenure', label: 'Tenure (months)', type: 'number', group: 'Loan requirement', required: false, numeric: true, placeholder: 'e.g. 48' },
    { id: 'purpose', label: 'Purpose', type: 'text', group: 'Loan requirement', required: false, placeholder: 'Optional' }
  ];
  var CONSENT = { id: 'consent', label: 'Consent', required: true };

  var app = document.getElementById('app');
  var backBtn = document.getElementById('app-back');

  /* ==================== SCREEN 1: browse / loan listing ==================== */

  var browse = document.createElement('div');
  browse.className = 'screen screen-browse';
  browse.innerHTML =
    '<section class="fibe-hero">' +
      '<h2 class="fibe-title">Lightning-fast loans with<br><span class="fibe-hl" id="hero-rot">Personal Loans</span></h2>' +
      '<p class="fibe-sub">Setu Finance — making finance simpler</p>' +
      '<div class="fibe-feats">' +
        '<span>Cash-in-bank in minutes</span>' +
        '<span>Fast loan processing</span>' +
        '<span>Borrow and repay at your convenience</span>' +
        '<span>One-time application for multiple loans</span>' +
      '</div>' +
    '</section>' +
    '<div class="rate-note wrap-note">💡 Your interest rate depends on your <b>CIBIL score</b> — a higher score means a lower rate.</div>' +
    '<div class="browse-head"><h3 class="browse-h">Our loan products</h3>' +
      '<span class="browse-hint">Tap any product — Arya finds your best rate</span></div>' +
    '<div class="loan-grid" id="loan-grid"></div>' +
    '<div class="why"><div class="why-item">⚡<b>Instant approval</b><span>in principle in minutes</span></div>' +
      '<div class="why-item">📄<b>Minimal documents</b><span>paperless, Aadhaar-based</span></div>' +
      '<div class="why-item">🔒<b>Safe & RBI-compliant</b><span>fair-practice code</span></div></div>' +
    '<p class="browse-foot">Prefer to fill the form yourself? <a href="#" id="manual-link">Apply directly ›</a></p>';
  app.appendChild(browse);

  var grid = browse.querySelector('#loan-grid');
  Cat.PRODUCTS.forEach(function (p) {
    var card = document.createElement('button');
    card.className = 'loan-card';
    card.setAttribute('data-loan', p.id);
    card.innerHTML =
      '<div class="loan-emoji">' + p.emoji + '</div>' +
      '<div class="loan-name">' + p.name + '</div>' +
      '<div class="loan-tag">' + (p.tagline || p.blurb) + '</div>' +
      '<div class="loan-meta"><span class="loan-rate">from <b>' + p.minRate.toFixed(2) + '%</b> p.a.</span></div>' +
      '<span class="loan-go">Apply Now ›</span>';
    card.addEventListener('click', function () { selectLoan(p.id); });
    grid.appendChild(card);
  });

  // Rotating highlighted product name in the hero (Fibe-style).
  (function () {
    var rot = browse.querySelector('#hero-rot');
    if (!rot) return;
    var names = ['Personal Loans', 'Two-Wheeler Loans', 'Used Car Loans', 'Home Loans',
      'Gold Loans', 'Business Loans', 'Consumer Durable Loans'];
    var i = 0;
    setInterval(function () {
      i = (i + 1) % names.length;
      rot.style.opacity = '0';
      setTimeout(function () { rot.textContent = names[i]; rot.style.opacity = '1'; }, 240);
    }, 2400);
  })();

  /* ==================== SCREEN 2: application form ==================== */

  var apply = document.createElement('div');
  apply.className = 'screen screen-apply hidden';
  apply.innerHTML =
    '<div class="wrap"><div class="card">' +
      '<div class="apply-head"><h2>Complete your application</h2>' +
      '<p class="section-sub" style="margin:0">You picked a loan with Arya — just a few details to finish.</p></div>' +
      '<form id="loan-form" autocomplete="off"></form>' +
      '<div class="actions">' +
        '<button type="button" id="submit-btn" class="btn btn-primary">Submit application</button>' +
        '<button type="button" id="reset-btn" class="btn btn-ghost">Clear</button>' +
        '<span class="note">Your data stays in your browser — this is a demo.</span>' +
      '</div>' +
    '</div></div>';
  app.appendChild(apply);

  var footer = document.createElement('footer');
  footer.textContent = 'Setu Finance is a fictional demo. Rates, products and eligibility are illustrative only.';
  app.appendChild(footer);

  /* ---------------- render the form into the apply screen ---------------- */

  var form = apply.querySelector('#loan-form');
  var groups = {};
  SCHEMA.forEach(function (f) { (groups[f.group] = groups[f.group] || []).push(f); });
  var groupMeta = {
    'Loan requirement': { n: 1, sub: 'What you\'d like to borrow.' },
    'Employment & income': { n: 2, sub: 'Helps us assess your repayment capacity & rate.' },
    'Personal details': { n: 3, sub: 'Basic details so we can reach you.' },
    'KYC': { n: 4, sub: 'Identity verification, as mandated by RBI.' }
  };
  ['Loan requirement', 'Employment & income', 'Personal details', 'KYC'].forEach(function (g) {
    var meta = groupMeta[g] || { n: '', sub: '' };
    var h = document.createElement('h3');
    h.innerHTML = '<span class="n">' + meta.n + '</span>' + g;
    form.appendChild(h);
    var sub = document.createElement('p'); sub.className = 'section-sub'; sub.textContent = meta.sub;
    form.appendChild(sub);
    var gr = document.createElement('div'); gr.className = 'grid';
    (groups[g] || []).forEach(function (f) { gr.appendChild(renderField(f)); });
    form.appendChild(gr);
  });
  var consentWrap = document.createElement('div');
  consentWrap.className = 'consent';
  consentWrap.innerHTML =
    '<input type="checkbox" id="field-consent" />' +
    '<label for="field-consent">I authorise Setu Finance to verify my details and fetch my credit report ' +
    'from a licensed bureau. I have read the terms & fair-practice code.</label>';
  form.appendChild(consentWrap);

  function renderField(f) {
    var wrap = document.createElement('div');
    wrap.className = 'field' + (f.type === 'select' || f.id === 'purpose' ? ' full' : '');
    var reqStar = f.required ? ' <span class="req">*</span>' : '';
    var label = document.createElement('label');
    label.setAttribute('for', 'field-' + f.id);
    label.innerHTML = f.label + reqStar;
    wrap.appendChild(label);
    var input;
    if (f.type === 'select') {
      input = document.createElement('select');
      f.options.forEach(function (o) {
        var opt = document.createElement('option'); opt.value = o.value; opt.textContent = o.label; input.appendChild(opt);
      });
    } else {
      input = document.createElement('input');
      input.type = f.type === 'number' ? 'text' : f.type;
      input.inputMode = f.numeric ? 'numeric' : 'text';
      if (f.placeholder) input.placeholder = f.placeholder;
    }
    input.id = 'field-' + f.id; input.dataset.field = f.id;
    wrap.appendChild(input);
    if (f.hint) { var hint = document.createElement('div'); hint.className = 'hint'; hint.textContent = f.hint; wrap.appendChild(hint); }
    return wrap;
  }

  /* ---------------- the ADAPTER bridge (host <-> copilot) ---------------- */

  var changeSubscribers = [];
  function elFor(id) { return document.getElementById('field-' + id); }

  var adapter = {
    getValue: function (id) {
      if (id === 'consent') return elFor('consent') && elFor('consent').checked ? 'yes' : '';
      var e = elFor(id); return e ? e.value : null;
    },
    setValue: function (id, value) {
      if (id === 'consent') { var c = elFor('consent'); if (c) c.checked = !!value; }
      else { var e = elFor(id); if (!e) return; e.value = value; }
      notify(id);
    },
    getAll: function () { var out = {}; SCHEMA.forEach(function (f) { out[f.id] = adapter.getValue(f.id); }); out.consent = adapter.getValue('consent'); return out; },
    focusField: function (id) { var e = elFor(id); if (e) e.focus(); },
    flashField: function (id) { var e = elFor(id); if (!e) return; e.classList.remove('flash'); void e.offsetWidth; e.classList.add('flash'); },
    onChange: function (cb) { changeSubscribers.push(cb); },
    submit: function () { doSubmit(); },
    // screen navigation (the copilot drives the user from browse -> apply)
    showApplication: function () { showScreen('apply'); },
    showBrowse: function () { showScreen('browse'); }
  };
  function notify(id) { changeSubscribers.forEach(function (cb) { try { cb(id); } catch (e) {} }); }

  form.addEventListener('input', function (e) { var id = e.target.dataset ? e.target.dataset.field : null; if (e.target.id === 'field-consent') id = 'consent'; if (id) notify(id); });
  form.addEventListener('change', function (e) { var id = e.target.dataset ? e.target.dataset.field : null; if (e.target.id === 'field-consent') id = 'consent'; if (id) notify(id); });

  /* ---------------- screen router ---------------- */

  function showScreen(name) {
    var isApply = name === 'apply';
    apply.classList.toggle('hidden', !isApply);
    browse.classList.toggle('hidden', isApply);
    backBtn.hidden = !isApply;
    window.scrollTo(0, 0);
  }
  backBtn.addEventListener('click', function () { showScreen('browse'); });

  /* ---------------- submit + toast ---------------- */

  function doSubmit() {
    var missing = SCHEMA.filter(function (f) { return f.required && !String(adapter.getValue(f.id) || '').trim(); });
    if (!adapter.getValue('consent')) missing.push(CONSENT);
    if (missing.length) {
      toast('Please complete: ' + missing.map(function (m) { return m.label; }).join(', '));
      var first = elFor(missing[0].id); if (first) first.focus();
      return;
    }
    toast('✅ Application submitted to Setu Finance. Ref LC-' + Math.random().toString(36).slice(2, 8).toUpperCase());
  }
  apply.querySelector('#submit-btn').addEventListener('click', doSubmit);
  apply.querySelector('#reset-btn').addEventListener('click', function () {
    SCHEMA.forEach(function (f) { adapter.setValue(f.id, ''); });
    adapter.setValue('consent', false);
    toast('Form cleared.');
  });

  var toastEl = document.getElementById('toast');
  var toastTimer;
  function toast(msg) { toastEl.textContent = msg; toastEl.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 3800); }

  /* ---------------- publish schema + mount the copilot ---------------- */

  var copilotSchema = SCHEMA.concat([{ id: 'consent', label: 'Consent', type: 'checkbox', group: 'Consent', required: true }]);
  window.HostApp = { schema: copilotSchema, adapter: adapter };

  var copilot = LendCopilot.init({
    schema: copilotSchema,
    adapter: adapter,
    catalog: Cat,
    nlu: window.LendCopilotNLU,
    brand: { name: 'Arya', accent: '#4f46e5', language: 'hi-IN' },
    autostart: true
  });
  // Expose the mounted copilot for debugging/automated tests (no behavioural effect).
  window.LendCopilotInstance = copilot;

  /* ---------------- browse-screen actions ---------------- */

  // Tapping a loan card selects it and lets Arya capture the rest (income, CIBIL…).
  function selectLoan(id) {
    adapter.setValue('loanType', id);
    if (copilot && copilot.startFromBrowse) copilot.startFromBrowse(id);
    else if (copilot && copilot.startCall) copilot.startCall();
  }
  browse.querySelector('#manual-link').addEventListener('click', function (e) {
    e.preventDefault(); showScreen('apply');
  });
})();
