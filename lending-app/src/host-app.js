/*
 * host-app.js — the HOST lending application ("Setu Finance").
 *
 * This represents the CLIENT'S own product: it renders its own form and owns
 * its own DOM. It publishes two things the copilot needs:
 *
 *   • schema  — a declarative description of the form fields.
 *   • adapter — a small bridge the copilot uses to read/write field values,
 *               flash a field, and submit. The copilot NEVER touches these
 *               DOM widgets directly; it goes through this adapter.
 *
 * Because integration is only "hand the copilot a schema + adapter", the same
 * SDK drops into any host app unchanged.
 */
(function () {
  'use strict';

  /* ---------------- form schema (single source of truth) ---------------- */

  var SCHEMA = [
    // group: personal
    { id: 'fullName', label: 'Full name', type: 'text', group: 'Personal details', required: true, placeholder: 'As per PAN' },
    { id: 'mobile', label: 'Mobile number', type: 'tel', group: 'Personal details', required: true, placeholder: '10-digit mobile', hint: 'We\'ll send an OTP' },
    { id: 'email', label: 'Email', type: 'email', group: 'Personal details', required: false, placeholder: 'you@email.com' },
    { id: 'age', label: 'Age', type: 'number', group: 'Personal details', required: true, numeric: true, placeholder: 'Years' },
    { id: 'city', label: 'City', type: 'text', group: 'Personal details', required: true, placeholder: 'e.g. Pune' },
    { id: 'pincode', label: 'Pincode', type: 'text', group: 'Personal details', required: false, placeholder: '6-digit' },

    // group: KYC
    { id: 'pan', label: 'PAN', type: 'text', group: 'KYC', required: true, placeholder: 'ABCDE1234F', hint: 'Permanent Account Number' },
    { id: 'aadhaar', label: 'Aadhaar', type: 'text', group: 'KYC', required: false, placeholder: '12-digit', hint: 'Stored masked' },

    // group: employment & income
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
    { id: 'cibil', label: 'CIBIL score', type: 'number', group: 'Employment & income', required: false, numeric: true, placeholder: '300–900 (optional)', hint: 'Helps unlock better rates' },

    // group: loan requirement
    {
      id: 'loanType', label: 'Loan type', type: 'select', group: 'Loan requirement', required: true,
      options: [
        { value: '', label: 'Select…' },
        { value: 'personal', label: 'Personal Loan' },
        { value: 'home', label: 'Home Loan' },
        { value: 'gold', label: 'Gold Loan' },
        { value: 'business', label: 'Business Loan' },
        { value: 'car', label: 'Car Loan' },
        { value: 'twowheeler', label: 'Two-Wheeler Loan' },
        { value: 'education', label: 'Education Loan' }
      ]
    },
    { id: 'amount', label: 'Loan amount (₹)', type: 'number', group: 'Loan requirement', required: true, numeric: true, placeholder: 'e.g. 500000' },
    { id: 'tenure', label: 'Tenure (months)', type: 'number', group: 'Loan requirement', required: false, numeric: true, placeholder: 'e.g. 48' },
    { id: 'purpose', label: 'Purpose', type: 'text', group: 'Loan requirement', required: false, placeholder: 'Optional' }
  ];

  var CONSENT = { id: 'consent', label: 'Consent', required: true };

  /* ---------------- render the form ---------------- */

  var form = document.getElementById('loan-form');
  var groups = {};
  SCHEMA.forEach(function (f) {
    if (!groups[f.group]) groups[f.group] = [];
    groups[f.group].push(f);
  });

  var groupMeta = {
    'Personal details': { n: 1, sub: 'Basic details so we can reach you.' },
    'KYC': { n: 2, sub: 'Identity verification, as mandated by RBI.' },
    'Employment & income': { n: 3, sub: 'Helps us assess your repayment capacity.' },
    'Loan requirement': { n: 4, sub: 'What you\'d like to borrow.' }
  };

  Object.keys(groups).forEach(function (g) {
    var meta = groupMeta[g] || { n: '', sub: '' };
    var h = document.createElement('h3');
    h.innerHTML = '<span class="n">' + meta.n + '</span>' + g;
    form.appendChild(h);
    var sub = document.createElement('p');
    sub.className = 'section-sub';
    sub.textContent = meta.sub;
    form.appendChild(sub);

    var grid = document.createElement('div');
    grid.className = 'grid';
    groups[g].forEach(function (f) {
      grid.appendChild(renderField(f));
    });
    form.appendChild(grid);
  });

  // consent row
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
        var opt = document.createElement('option');
        opt.value = o.value; opt.textContent = o.label;
        input.appendChild(opt);
      });
    } else {
      input = document.createElement('input');
      input.type = f.type === 'number' ? 'text' : f.type; // text so we can format freely
      input.inputMode = f.numeric ? 'numeric' : 'text';
      if (f.placeholder) input.placeholder = f.placeholder;
    }
    input.id = 'field-' + f.id;
    input.dataset.field = f.id;
    wrap.appendChild(input);

    if (f.hint) {
      var hint = document.createElement('div');
      hint.className = 'hint';
      hint.textContent = f.hint;
      wrap.appendChild(hint);
    }
    return wrap;
  }

  /* ---------------- the ADAPTER bridge (host <-> copilot) ---------------- */

  var changeSubscribers = [];
  function elFor(id) { return document.getElementById('field-' + id); }

  var adapter = {
    getValue: function (id) {
      if (id === 'consent') return elFor('consent') && elFor('consent').checked ? 'yes' : '';
      var e = elFor(id);
      return e ? e.value : null;
    },
    setValue: function (id, value) {
      if (id === 'consent') {
        var c = elFor('consent'); if (c) c.checked = !!value;
      } else {
        var e = elFor(id);
        if (!e) return;
        e.value = value;
      }
      notify(id);
    },
    getAll: function () {
      var out = {};
      SCHEMA.forEach(function (f) { out[f.id] = adapter.getValue(f.id); });
      out.consent = adapter.getValue('consent');
      return out;
    },
    focusField: function (id) { var e = elFor(id); if (e) e.focus(); },
    flashField: function (id) {
      var e = elFor(id);
      if (!e) return;
      e.classList.remove('flash');
      void e.offsetWidth; // restart animation
      e.classList.add('flash');
    },
    onChange: function (cb) { changeSubscribers.push(cb); },
    submit: function () { doSubmit(); }
  };

  function notify(id) { changeSubscribers.forEach(function (cb) { try { cb(id); } catch (e) {} }); }

  // Reflect direct user edits back to the copilot (keeps the ring live).
  form.addEventListener('input', function (e) {
    var id = e.target.dataset ? e.target.dataset.field : null;
    if (e.target.id === 'field-consent') id = 'consent';
    if (id) notify(id);
  });
  form.addEventListener('change', function (e) {
    var id = e.target.dataset ? e.target.dataset.field : null;
    if (e.target.id === 'field-consent') id = 'consent';
    if (id) notify(id);
  });

  /* ---------------- submit + toast ---------------- */

  function doSubmit() {
    var missing = SCHEMA.filter(function (f) {
      return f.required && !String(adapter.getValue(f.id) || '').trim();
    });
    if (!adapter.getValue('consent')) missing.push(CONSENT);
    if (missing.length) {
      toast('Please complete: ' + missing.map(function (m) { return m.label; }).join(', '));
      var first = elFor(missing[0].id); if (first) first.focus();
      return;
    }
    toast('✅ Application submitted to Setu Finance. Ref LC-' + Math.random().toString(36).slice(2, 8).toUpperCase());
  }

  document.getElementById('submit-btn').addEventListener('click', doSubmit);
  document.getElementById('reset-btn').addEventListener('click', function () {
    SCHEMA.forEach(function (f) { adapter.setValue(f.id, ''); });
    adapter.setValue('consent', false);
    toast('Form cleared.');
  });

  var toastEl = document.getElementById('toast');
  var toastTimer;
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 3800);
  }

  /* ---------------- publish schema for the copilot ---------------- */

  var copilotSchema = SCHEMA.concat([{ id: 'consent', label: 'Consent', type: 'checkbox', group: 'Consent', required: true }]);

  window.HostApp = { schema: copilotSchema, adapter: adapter };

  /* ---------------- wire up the copilot SDK ---------------- */
  // This is the ENTIRE integration surface: hand the SDK a schema + adapter.
  LendCopilot.init({
    schema: copilotSchema,
    adapter: adapter,
    catalog: window.LendingCatalog,
    nlu: window.LendCopilotNLU,
    brand: { name: 'Arya', accent: '#4f46e5', language: 'hi-IN' },
    autostart: true  // greet the user by voice on landing
  });
})();
