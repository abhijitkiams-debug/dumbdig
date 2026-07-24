/*
 * products.js — Indian lending product catalog + eligibility & EMI engine.
 *
 * Pure, dependency-free logic. Exposes a single global `LendingCatalog`
 * so it can be loaded with a classic <script> tag (works over file://).
 *
 * All money is in Indian Rupees (paise ignored). Formatting uses the Indian
 * digit grouping system (lakh / crore).
 */
(function (global) {
  'use strict';

  /* ---------- formatting helpers (Indian numbering) ---------- */

  function inr(n) {
    if (n == null || isNaN(n)) return '—';
    n = Math.round(n);
    var sign = n < 0 ? '-' : '';
    n = Math.abs(n);
    var s = String(n);
    var last3 = s.slice(-3);
    var rest = s.slice(0, -3);
    if (rest) {
      last3 = ',' + last3;
      rest = rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',');
    }
    return sign + '₹' + rest + last3;
  }

  // Human "lakh / crore" short form, e.g. 2500000 -> "₹25.0 L"
  function inrShort(n) {
    if (n == null || isNaN(n)) return '—';
    n = Math.round(n);
    if (n >= 1e7) return '₹' + (n / 1e7).toFixed(n % 1e7 === 0 ? 0 : 2) + ' Cr';
    if (n >= 1e5) return '₹' + (n / 1e5).toFixed(n % 1e5 === 0 ? 0 : 1) + ' L';
    if (n >= 1e3) return '₹' + (n / 1e3).toFixed(0) + 'k';
    return inr(n);
  }

  /* ---------- core finance maths ---------- */

  // Equated Monthly Instalment for principal P, annual rate %, over `months`.
  function emi(P, annualRatePct, months) {
    var r = annualRatePct / 12 / 100;
    if (months <= 0) return 0;
    if (r === 0) return P / months;
    var f = Math.pow(1 + r, months);
    return (P * r * f) / (f - 1);
  }

  // Largest principal whose EMI stays within `budgetEmi` at given rate/tenure.
  function principalFromEmi(budgetEmi, annualRatePct, months) {
    var r = annualRatePct / 12 / 100;
    if (months <= 0) return 0;
    if (r === 0) return budgetEmi * months;
    var f = Math.pow(1 + r, months);
    return (budgetEmi * (f - 1)) / (r * f);
  }

  /* ---------- product catalog (representative Indian market ranges) ---------- */

  var PRODUCTS = [
    {
      id: 'personal',
      name: 'Personal Loan',
      tagline: 'Cash for any need',
      emoji: '💰',
      blurb: 'Unsecured cash for any need — weddings, travel, medical, debt consolidation.',
      minAmount: 50000, maxAmount: 4000000,
      minRate: 10.5, maxRate: 24,
      minMonths: 12, maxMonths: 60, defaultMonths: 48,
      secured: false, minIncome: 15000, minAge: 21, maxAge: 60,
      docs: ['PAN', 'Aadhaar', '3 months bank statement', 'Latest salary slips']
    },
    {
      id: 'home',
      name: 'Home Loan',
      tagline: 'Your dream home',
      emoji: '🏠',
      blurb: 'Buy, build or renovate your home. Long tenure, lowest rates, tax benefits.',
      minAmount: 500000, maxAmount: 50000000,
      minRate: 8.4, maxRate: 10.5,
      minMonths: 60, maxMonths: 360, defaultMonths: 240,
      secured: true, minIncome: 25000, minAge: 21, maxAge: 65,
      docs: ['PAN', 'Aadhaar', 'Income proof', 'Property papers', 'Sale agreement']
    },
    {
      id: 'gold',
      name: 'Gold Loan',
      tagline: 'Instant cash on gold',
      emoji: '🥇',
      blurb: 'Instant funds against gold jewellery. Minimal paperwork, quick disbursal.',
      minAmount: 25000, maxAmount: 5000000,
      minRate: 9, maxRate: 18,
      minMonths: 3, maxMonths: 36, defaultMonths: 12,
      secured: true, minIncome: 0, minAge: 18, maxAge: 70,
      docs: ['PAN / Aadhaar', 'Gold to pledge']
    },
    {
      id: 'business',
      name: 'Business Loan',
      tagline: 'Fuel your business',
      emoji: '💼',
      blurb: 'Working capital & growth funding for MSMEs and self-employed professionals.',
      minAmount: 100000, maxAmount: 5000000,
      minRate: 14, maxRate: 24,
      minMonths: 12, maxMonths: 48, defaultMonths: 36,
      secured: false, minIncome: 30000, minAge: 24, maxAge: 65,
      docs: ['PAN', 'GST returns', '6-12 months bank statement', 'Business proof']
    },
    {
      id: 'twowheeler',
      name: 'Two-Wheeler Loan',
      tagline: 'Ride home today',
      emoji: '🏍️',
      blurb: 'Ride home your bike or scooter with up to 100% on-road financing.',
      minAmount: 30000, maxAmount: 300000,
      minRate: 11, maxRate: 22,
      minMonths: 12, maxMonths: 48, defaultMonths: 36,
      secured: true, minIncome: 10000, minAge: 21, maxAge: 60,
      docs: ['PAN', 'Aadhaar', 'Income proof', 'Vehicle quotation']
    },
    {
      id: 'car',
      name: 'Used Car Loan',
      emoji: '🚗',
      tagline: 'Drive home your dream car',
      blurb: 'Finance a new or pre-owned car with up to 90% funding and flexible tenure.',
      minAmount: 100000, maxAmount: 10000000,
      minRate: 9, maxRate: 14,
      minMonths: 12, maxMonths: 84, defaultMonths: 60,
      secured: true, minIncome: 20000, minAge: 21, maxAge: 65,
      docs: ['PAN', 'Aadhaar', 'Income proof', 'Car quotation']
    },
    {
      id: 'consumer',
      name: 'Consumer Durable Loan',
      emoji: '📺',
      tagline: 'Easy EMIs on appliances',
      blurb: 'No-cost & easy EMIs on TVs, fridges, washing machines and laptops.',
      minAmount: 5000, maxAmount: 500000,
      minRate: 10, maxRate: 24,
      minMonths: 3, maxMonths: 24, defaultMonths: 9,
      secured: false, minIncome: 12000, minAge: 21, maxAge: 60,
      docs: ['PAN', 'Aadhaar', 'Bank statement']
    },
    {
      id: 'mobile',
      name: 'Mobile Loan',
      emoji: '📱',
      tagline: 'The latest phone on EMI',
      blurb: 'Buy the newest smartphone on easy EMIs with instant approval.',
      minAmount: 5000, maxAmount: 200000,
      minRate: 12, maxRate: 26,
      minMonths: 3, maxMonths: 24, defaultMonths: 12,
      secured: false, minIncome: 10000, minAge: 18, maxAge: 60,
      docs: ['PAN', 'Aadhaar']
    },
    {
      id: 'tractor',
      name: 'Tractor Loan',
      emoji: '🚜',
      tagline: 'Power up your farm',
      blurb: 'Finance a new or used tractor with flexible, harvest-friendly EMIs.',
      minAmount: 100000, maxAmount: 1500000,
      minRate: 12, maxRate: 20,
      minMonths: 12, maxMonths: 60, defaultMonths: 48,
      secured: true, minIncome: 0, minAge: 21, maxAge: 65,
      docs: ['PAN', 'Aadhaar', 'Land records', 'RC book']
    },
    {
      id: 'threewheeler',
      name: 'Three-Wheeler Loan',
      emoji: '🛺',
      tagline: 'Own it, start earning',
      blurb: 'Own an auto or e-rickshaw with up to 95% funding and start earning.',
      minAmount: 50000, maxAmount: 500000,
      minRate: 13, maxRate: 24,
      minMonths: 12, maxMonths: 48, defaultMonths: 36,
      secured: true, minIncome: 0, minAge: 21, maxAge: 60,
      docs: ['PAN', 'Aadhaar', 'Income proof', 'Vehicle quotation']
    },
    {
      id: 'lap',
      name: 'Loan Against Property',
      emoji: '🏢',
      tagline: 'Unlock your property\'s value',
      blurb: 'Big-ticket funds against your home or shop, at low rates and long tenure.',
      minAmount: 500000, maxAmount: 20000000,
      minRate: 9, maxRate: 14,
      minMonths: 36, maxMonths: 180, defaultMonths: 120,
      secured: true, minIncome: 30000, minAge: 24, maxAge: 65,
      docs: ['PAN', 'Aadhaar', 'Property papers', 'Income proof']
    },
    {
      id: 'ucv',
      name: 'Commercial Vehicle Loan',
      emoji: '🚛',
      tagline: 'Grow your transport business',
      blurb: 'Finance a new or used truck / commercial vehicle to grow your business.',
      minAmount: 100000, maxAmount: 5000000,
      minRate: 14, maxRate: 22,
      minMonths: 12, maxMonths: 60, defaultMonths: 48,
      secured: true, minIncome: 0, minAge: 21, maxAge: 65,
      docs: ['PAN', 'Aadhaar', 'RC book', 'Business proof']
    },
    {
      id: 'education',
      name: 'Education Loan',
      emoji: '🎓',
      tagline: 'Invest in your future',
      blurb: 'Fund higher studies in India or abroad. Moratorium during the course.',
      minAmount: 50000, maxAmount: 7500000,
      minRate: 8.5, maxRate: 14,
      minMonths: 12, maxMonths: 180, defaultMonths: 84,
      secured: false, minIncome: 0, minAge: 18, maxAge: 35,
      docs: ['PAN', 'Aadhaar', 'Admission letter', 'Fee structure', 'Co-applicant KYC']
    }
  ];

  var BY_ID = {};
  PRODUCTS.forEach(function (p) { BY_ID[p.id] = p; });

  /* ---------- credit / risk scoring ---------- */

  // Rate offered = base rate nudged by CIBIL band + employment type.
  function offeredRate(product, profile) {
    var rate = product.minRate;
    var cibil = profile.cibil;
    if (cibil == null) {
      rate += (product.maxRate - product.minRate) * 0.4; // unknown => mid-ish
    } else if (cibil >= 780) {
      rate += 0;
    } else if (cibil >= 750) {
      rate += (product.maxRate - product.minRate) * 0.15;
    } else if (cibil >= 700) {
      rate += (product.maxRate - product.minRate) * 0.45;
    } else if (cibil >= 650) {
      rate += (product.maxRate - product.minRate) * 0.75;
    } else {
      rate = product.maxRate; // subprime -> top of band
    }
    if (profile.employment === 'self-employed' || profile.employment === 'business') {
      rate += 0.75;
    }
    return Math.min(product.maxRate, Math.round(rate * 100) / 100);
  }

  // FOIR (Fixed Obligation to Income Ratio) cap by CIBIL strength.
  function foirCap(profile) {
    var c = profile.cibil;
    if (c == null) return 0.45;
    if (c >= 750) return 0.55;
    if (c >= 700) return 0.50;
    if (c >= 650) return 0.42;
    return 0.35;
  }

  // Tenure that also respects the customer's retirement age for the product.
  function safeTenure(product, profile) {
    var months = product.defaultMonths;
    if (profile.age != null) {
      var monthsToExit = (product.maxAge - profile.age) * 12;
      if (monthsToExit > 0) months = Math.min(months, monthsToExit);
    }
    return Math.max(product.minMonths, Math.min(product.maxMonths, months));
  }

  /*
   * Evaluate a single product for a profile.
   * Returns an assessment object (eligible or not, with reasons + numbers).
   */
  function assess(product, profile) {
    var reasons = [];
    var eligible = true;
    var income = profile.monthlyIncome || 0;
    var existingEmi = profile.existingEmi || 0;

    if (profile.age != null && (profile.age < product.minAge || profile.age > product.maxAge)) {
      eligible = false;
      reasons.push('Age must be ' + product.minAge + '–' + product.maxAge + ' for this product.');
    }
    if (product.minIncome > 0 && income > 0 && income < product.minIncome) {
      eligible = false;
      reasons.push('Needs monthly income of at least ' + inr(product.minIncome) + '.');
    }
    if (profile.cibil != null && profile.cibil < 640 && !product.secured) {
      eligible = false;
      reasons.push('Credit score below 640 rarely qualifies for an unsecured loan.');
    }

    var rate = offeredRate(product, profile);
    var months = safeTenure(product, profile);

    // How much EMI budget is left after existing obligations?
    var maxEligible = product.maxAmount;
    if (income > 0) {
      var emiBudget = Math.max(0, income * foirCap(profile) - existingEmi);
      var byIncome = principalFromEmi(emiBudget, rate, months);
      maxEligible = Math.min(product.maxAmount, byIncome);
      if (emiBudget <= 0) {
        eligible = false;
        reasons.push('Existing EMIs already use up your repayment capacity.');
      }
    }
    maxEligible = Math.max(0, Math.floor(maxEligible / 1000) * 1000);

    // Requested amount vs product bounds / eligibility.
    var requested = profile.amount || 0;
    var overAsk = requested > 0 && requested > maxEligible && income > 0;
    if (overAsk) {
      reasons.push('For ' + inr(requested) + ' you may need a longer tenure or co-applicant; eligible now up to ' + inr(maxEligible) + '.');
    }

    var sizingAmount = requested > 0 ? Math.min(requested, maxEligible || requested) : Math.min(maxEligible || product.minAmount, product.maxAmount);
    if (sizingAmount < product.minAmount) sizingAmount = product.minAmount;
    var monthlyEmi = emi(sizingAmount, rate, months);

    return {
      productId: product.id,
      product: product,
      eligible: eligible,
      rate: rate,
      tenureMonths: months,
      maxEligible: maxEligible,
      sizedAmount: sizingAmount,
      emi: Math.round(monthlyEmi),
      totalPayable: Math.round(monthlyEmi * months),
      totalInterest: Math.round(monthlyEmi * months - sizingAmount),
      overAsk: overAsk,
      reasons: reasons
    };
  }

  /*
   * Recommend & rank products for a profile.
   * Scoring favours: match to requested loan type, eligibility, headroom,
   * and lower rate.
   */
  function recommend(profile) {
    profile = profile || {};
    var results = PRODUCTS.map(function (p) { return assess(p, profile); });

    results.forEach(function (r) {
      var score = 0;
      if (profile.loanType && r.productId === profile.loanType) score += 1000;
      if (r.eligible) score += 300;
      if (r.overAsk) score -= 120;
      score += Math.min(150, r.maxEligible / 50000);   // headroom
      score += (28 - r.rate) * 4;                        // cheaper is better
      if (!r.product.secured) score += 15;               // faster to disburse
      r.score = score;
    });

    results.sort(function (a, b) { return b.score - a.score; });
    return results;
  }

  global.LendingCatalog = {
    PRODUCTS: PRODUCTS,
    byId: function (id) { return BY_ID[id]; },
    inr: inr,
    inrShort: inrShort,
    emi: emi,
    principalFromEmi: principalFromEmi,
    assess: assess,
    recommend: recommend
  };
})(typeof window !== 'undefined' ? window : this);
