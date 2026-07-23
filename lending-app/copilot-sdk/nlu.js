/*
 * nlu.js — a lightweight, offline Natural Language Understanding engine
 * tuned for Indian lending conversations.
 *
 * No network, no API key: pure regex + keyword heuristics. It extracts the
 * entities a loan form needs and classifies the customer's intent, so the
 * copilot can auto-fill fields and pick the next best action.
 *
 * Exposes global `LendCopilotNLU`.
 */
(function (global) {
  'use strict';

  /* ---------- small dictionaries ---------- */

  var LOAN_TYPES = [
    { id: 'personal', words: ['personal', 'cash', 'urgent money', 'wedding', 'marriage', 'medical', 'travel', 'consolidat'] },
    { id: 'home', words: ['home', 'house', 'housing', 'flat', 'apartment', 'property', 'plot', 'renovat'] },
    { id: 'gold', words: ['gold', 'jewel', 'jewellery', 'jewelry', 'ornament'] },
    { id: 'business', words: ['business', 'working capital', 'msme', 'shop', 'enterprise', 'startup', 'inventory'] },
    { id: 'twowheeler', words: ['two wheeler', 'two-wheeler', 'bike', 'scooter', 'scooty', 'motorcycle', 'activa'] },
    { id: 'car', words: ['car', 'auto loan', 'vehicle', 'four wheeler', 'four-wheeler', 'suv', 'sedan'] },
    { id: 'education', words: ['education', 'study', 'studies', 'college', 'university', 'course', 'tuition', 'abroad', 'mba'] }
  ];

  var EMPLOYMENT = [
    { id: 'salaried', words: ['salaried', 'salary', 'employee', 'employed', 'job', 'private company', 'govt', 'government', 'working in', 'work at', 'ctc'] },
    { id: 'self-employed', words: ['self employed', 'self-employed', 'freelanc', 'consultant', 'professional', 'doctor', 'lawyer', 'ca ', 'architect'] },
    { id: 'business', words: ['business owner', 'businessman', 'proprietor', 'trader', 'shop owner', 'entrepreneur', 'own business', 'own a business'] },
    { id: 'student', words: ['student', 'studying', 'no income', 'unemployed'] }
  ];
  // NOTE: dictionaries share substrings ("employed" ⊂ "self employed"), so
  // matching uses LONGEST-keyword-wins, not first-in-list, to disambiguate.

  // A modest list of Indian cities for light city detection.
  var CITIES = ['mumbai', 'delhi', 'new delhi', 'bengaluru', 'bangalore', 'hyderabad', 'ahmedabad',
    'chennai', 'kolkata', 'surat', 'pune', 'jaipur', 'lucknow', 'kanpur', 'nagpur', 'indore',
    'thane', 'bhopal', 'visakhapatnam', 'patna', 'vadodara', 'ghaziabad', 'ludhiana', 'agra',
    'nashik', 'coimbatore', 'kochi', 'chandigarh', 'guwahati', 'gurgaon', 'gurugram', 'noida'];

  /* ---------- regexes ---------- */

  var RE = {
    pan: /\b([A-Z]{5}[0-9]{4}[A-Z])\b/i,
    aadhaar: /\b(\d{4}\s?\d{4}\s?\d{4})\b/,
    mobile: /(?:\+91[\-\s]?|0)?([6-9]\d{9})\b/,
    email: /\b([a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,})\b/i,
    pincode: /\b([1-9]\d{5})\b/,
    cibil: /\b(?:cibil|credit\s*score|score)\D{0,8}(\d{3})\b/i,
    ageWord: /\b(?:age|aged|i am|i'm|im)\s*(\d{1,2})\b|\b(\d{1,2})\s*(?:years?\s*old|yrs?\s*old|yo)\b/i,
    name: /\b(?:my\s+(?:full\s+)?name\s+is|name\s*[:=]\s*|i am|i'm|this is)\s+([a-z][a-z'.]+(?:\s+[a-z][a-z'.]+){0,2})/i
  };

  // Words that must never be treated as the start of a person's name — they
  // follow "I am / I'm" far more often as state than as identity.
  var NAME_STOP = { in: 1, at: 1, from: 1, on: 1, the: 1, a: 1, an: 1, is: 1, am: 1,
    looking: 1, interested: 1, salaried: 1, self: 1, employed: 1, working: 1, going: 1,
    here: 1, there: 1, not: 1, no: 1, yes: 1, based: 1, currently: 1, still: 1, just: 1,
    really: 1, very: 1, so: 1, also: 1, applying: 1, planning: 1, trying: 1, unemployed: 1 };

  /* ---------- amount parsing (lakh / crore / k) ---------- */

  // Returns the numeric rupee value of the first monetary mention, or null.
  function parseAmount(text) {
    var t = text.toLowerCase().replace(/,/g, '');
    // e.g. "5 lakh", "2.5 lac", "1 crore", "3 cr", "50k", "₹200000", "10 lpa".
    // The leading \b before each number stops digits glued to letters — e.g.
    // the "9876K" inside PAN "ABCDE9876K" — from being read as an amount.
    var m;

    m = t.match(/(?:₹|rs\.?|inr)?\s*\b(\d+(?:\.\d+)?)\s*(crore|crores|cr)\b/);
    if (m) return Math.round(parseFloat(m[1]) * 1e7);

    m = t.match(/(?:₹|rs\.?|inr)?\s*\b(\d+(?:\.\d+)?)\s*(lakh|lakhs|lac|lacs|lpa|l)\b/);
    if (m) return Math.round(parseFloat(m[1]) * 1e5);

    m = t.match(/(?:₹|rs\.?|inr)?\s*\b(\d+(?:\.\d+)?)\s*(thousand|k)\b/);
    if (m) return Math.round(parseFloat(m[1]) * 1e3);

    // plain numbers only with an explicit currency cue (avoids grabbing phone/pincode)
    m = t.match(/(?:₹|rs\.?|inr)\s*\b(\d{4,})/);
    if (m) return parseInt(m[1], 10);

    return null;
  }

  // Income: detect a value AND whether it's monthly vs annual, normalise to monthly.
  function parseIncome(text) {
    var t = text.toLowerCase();
    // Word-boundaried so "p.a."/"p.m." don't match inside words like "pan".
    var isAnnual = /(per year|per annum|\bp\.?a\.?\b|annual|yearly|\blpa\b|\bctc\b)/.test(t);
    var isMonthly = /(per month|monthly|\bp\.?m\.?\b|a month|every month|in hand)/.test(t);
    var amt = parseAmount(text);
    if (amt == null) return null;
    // "lpa" strongly implies annual lakhs already handled by parseAmount
    if (/lpa/.test(t)) isAnnual = true;
    var monthly = isAnnual ? Math.round(amt / 12) : amt;
    return { monthly: monthly, raw: amt, basis: isAnnual ? 'annual' : (isMonthly ? 'monthly' : 'assumed-monthly') };
  }

  // Tenure -> months.
  function parseTenure(text) {
    var t = text.toLowerCase();
    var m = t.match(/(\d+)\s*(year|years|yr|yrs)\b/);
    if (m) return parseInt(m[1], 10) * 12;
    m = t.match(/(\d+)\s*(month|months|mo|mos)\b/);
    if (m) return parseInt(m[1], 10);
    return null;
  }

  /* ---------- matchers ---------- */

  // Longest-keyword-wins so a specific phrase ("self employed") beats a generic
  // substring ("employed") regardless of dictionary order.
  function matchDict(text, dict) {
    var t = ' ' + text.toLowerCase() + ' ';
    var bestId = null, bestLen = 0;
    for (var i = 0; i < dict.length; i++) {
      var entry = dict[i];
      for (var j = 0; j < entry.words.length; j++) {
        var w = entry.words[j];
        if (t.indexOf(w) !== -1 && w.length > bestLen) {
          bestLen = w.length; bestId = entry.id;
        }
      }
    }
    return bestId;
  }

  function matchCity(text) {
    var t = ' ' + text.toLowerCase() + ' ';
    // longest first so "new delhi" wins over "delhi"
    var sorted = CITIES.slice().sort(function (a, b) { return b.length - a.length; });
    for (var i = 0; i < sorted.length; i++) {
      if (t.indexOf(' ' + sorted[i] + ' ') !== -1 || t.indexOf(' ' + sorted[i] + ',') !== -1) {
        return sorted[i].replace(/\b\w/g, function (c) { return c.toUpperCase(); });
      }
    }
    return null;
  }

  /* ---------- intent classification ---------- */

  var INTENT_RULES = [
    { id: 'greet', re: /\b(hi|hii|hello|hey|namaste|namaskar|good (morning|afternoon|evening)|start)\b/i },
    { id: 'help', re: /\b(help|how (do|does|to)|what can you|guide|assist|confused|stuck)\b/i },
    // NOTE: no trailing \b on stems so plurals ("documents", "products") match.
    { id: 'recommend', re: /\b(recommend|suggest|which loan|best loan|what loan|option|product|eligible for)/i },
    { id: 'eligibility', re: /\b(eligib|qualify|how much (can|do) i get|max(imum)? (loan|amount)|approve)/i },
    { id: 'emi', re: /\b(emi|instal|monthly payment|repay|interest|calculate|how much per month)/i },
    { id: 'documents', re: /\b(document|papers|kyc|what do i need|proof)/i },
    { id: 'apply', re: /\b(apply|application|want (a|to) loan|need (a|money|loan)|take a loan|get a loan|proceed|submit)\b/i },
    { id: 'affirm', re: /^(yes|yeah|yep|sure|ok|okay|correct|right|haan|yup|do it|go ahead|please)\b/i },
    { id: 'deny', re: /^(no|nope|not|nah|cancel|wrong|nahi)\b/i }
  ];

  function classifyIntents(text) {
    var hits = [];
    INTENT_RULES.forEach(function (r) { if (r.re.test(text)) hits.push(r.id); });
    return hits;
  }

  /* ---------- public parse ---------- */

  function parse(text) {
    text = (text || '').trim();
    var entities = {};

    var loanType = matchDict(text, LOAN_TYPES);
    if (loanType) entities.loanType = loanType;

    var employment = matchDict(text, EMPLOYMENT);
    if (employment) entities.employment = employment;

    var city = matchCity(text);
    if (city) entities.city = city;

    // Amounts are assigned per-CLAUSE so "5 lakh loan, I earn 60k a month"
    // sends 5L to the loan amount and 60k to income — not the first number to both.
    var INCOME_CUE = /(salary|income|earn|per month|per annum|monthly|\blpa\b|\bctc\b|in hand|a month|\bp\.?m\.?\b|\bp\.?a\.?\b)/i;
    var LOAN_CUE = /(loan|borrow|amount|require|fund|finance|want|need|for a|for my)/i;
    var clauses = text.split(/\s*(?:,|;|\band\b|\bbut\b)\s*/i);

    var incomeVal = null, loanVal = null, orphanVal = null;
    clauses.forEach(function (c) {
      var a = parseAmount(c);
      if (a == null) return;
      var hasIncome = INCOME_CUE.test(c);
      var hasLoan = LOAN_CUE.test(c) || matchDict(c, LOAN_TYPES);
      if (hasIncome && incomeVal == null) {
        var inc = parseIncome(c);
        incomeVal = inc ? { monthly: inc.monthly, basis: inc.basis } : { monthly: a, basis: 'assumed-monthly' };
      } else if (hasLoan && loanVal == null) {
        loanVal = a;
      } else if (orphanVal == null) {
        orphanVal = a;
      }
    });
    // A lone number with no cue defaults to the loan amount (most common ask).
    if (loanVal == null && orphanVal != null) loanVal = orphanVal;
    if (incomeVal) { entities.monthlyIncome = incomeVal.monthly; entities.incomeBasis = incomeVal.basis; }
    if (loanVal != null) entities.amount = loanVal;

    var tenure = parseTenure(text);
    if (tenure) entities.tenureMonths = tenure;

    var m;
    if ((m = text.match(RE.pan))) entities.pan = m[1].toUpperCase();
    if ((m = text.match(RE.aadhaar))) entities.aadhaar = m[1].replace(/\s/g, '');
    if ((m = text.match(RE.email))) entities.email = m[1].toLowerCase();
    if ((m = text.match(RE.mobile))) entities.mobile = m[1];
    // Pincode only if it isn't the same 6 digits as something else; keep simple.
    if ((m = text.match(RE.pincode)) && !entities.aadhaar) {
      // avoid grabbing part of a phone number
      if (!(entities.mobile && entities.mobile.indexOf(m[1]) !== -1)) entities.pincode = m[1];
    }
    if ((m = text.match(RE.cibil))) {
      var sc = parseInt(m[1], 10);
      if (sc >= 300 && sc <= 900) entities.cibil = sc;
    }
    if ((m = text.match(RE.ageWord))) {
      var age = parseInt(m[1] || m[2], 10);
      if (age >= 18 && age <= 75) entities.age = age;
    }
    if ((m = text.match(RE.name))) {
      var nm = m[1].trim();
      var firstWord = nm.split(/\s+/)[0].toLowerCase();
      // reject state phrases ("i'm in Mumbai"), employment, cities and numbers
      if (!/^\d/.test(nm) && !NAME_STOP[firstWord] && !matchDict(nm, EMPLOYMENT) &&
          !matchCity(nm) && nm.split(/\s+/).length <= 3) {
        entities.fullName = nm.replace(/\b\w/g, function (c) { return c.toUpperCase(); });
      }
    }

    var intents = classifyIntents(text);
    // Expressing a need alongside a loan type / amount is an application intent.
    if (intents.indexOf('apply') === -1 &&
        (entities.loanType || entities.amount != null) &&
        /\b(want|need|looking|require|get|take|apply)\b/i.test(text)) {
      intents.push('apply');
    }

    return {
      text: text,
      intents: intents,
      entities: entities
    };
  }

  global.LendCopilotNLU = {
    parse: parse,
    parseAmount: parseAmount,
    parseIncome: parseIncome,
    parseTenure: parseTenure,
    LOAN_TYPES: LOAN_TYPES
  };
})(typeof window !== 'undefined' ? window : this);
