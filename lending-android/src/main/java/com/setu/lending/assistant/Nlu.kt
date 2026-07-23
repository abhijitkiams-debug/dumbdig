package com.setu.lending.assistant

import kotlin.math.roundToLong

/**
 * Offline NLU tuned for Indian lending conversations. Faithful Kotlin port of
 * the browser-tested nlu.js: intent classification + entity extraction
 * (loan type, amounts in lakh/crore, income monthly-vs-LPA, PAN, Aadhaar,
 * mobile, CIBIL, age, city, pincode, name).
 */

data class Entities(
    var loanType: String? = null,
    var employment: String? = null,
    var city: String? = null,
    var monthlyIncome: Long? = null,
    var incomeBasis: String? = null,
    var amount: Long? = null,
    var tenureMonths: Int? = null,
    var pan: String? = null,
    var aadhaar: String? = null,
    var email: String? = null,
    var mobile: String? = null,
    var pincode: String? = null,
    var cibil: Int? = null,
    var age: Int? = null,
    var fullName: String? = null
) {
    /** Flatten to (formFieldId -> value) so the ViewModel can fill the form. */
    fun toFieldValues(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        loanType?.let { m["loanType"] = it }
        amount?.let { m["amount"] = it.toString() }
        monthlyIncome?.let { m["monthlyIncome"] = it.toString() }
        employment?.let { m["employment"] = it }
        fullName?.let { m["fullName"] = it }
        mobile?.let { m["mobile"] = it }
        age?.let { m["age"] = it.toString() }
        pan?.let { m["pan"] = it }
        city?.let { m["city"] = it }
        pincode?.let { m["pincode"] = it }
        email?.let { m["email"] = it }
        aadhaar?.let { m["aadhaar"] = it }
        cibil?.let { m["cibil"] = it.toString() }
        tenureMonths?.let { m["tenure"] = it.toString() }
        return m
    }
}

data class Parsed(val text: String, val intents: List<String>, val entities: Entities)

private data class DictEntry(val id: String, val words: List<String>)

object Nlu {

    private val LOAN_TYPES = listOf(
        DictEntry("personal", listOf("personal", "cash", "urgent money", "wedding", "marriage", "medical", "travel", "consolidat")),
        DictEntry("home", listOf("home", "house", "housing", "flat", "apartment", "property", "plot", "renovat")),
        DictEntry("gold", listOf("gold", "jewel", "jewellery", "jewelry", "ornament")),
        DictEntry("business", listOf("business", "working capital", "msme", "shop", "enterprise", "startup", "inventory")),
        DictEntry("twowheeler", listOf("two wheeler", "two-wheeler", "bike", "scooter", "scooty", "motorcycle", "activa")),
        DictEntry("car", listOf("car", "auto loan", "vehicle", "four wheeler", "four-wheeler", "suv", "sedan")),
        DictEntry("education", listOf("education", "study", "studies", "college", "university", "course", "tuition", "abroad", "mba"))
    )

    private val EMPLOYMENT = listOf(
        DictEntry("salaried", listOf("salaried", "salary", "employee", "employed", "job", "private company", "govt", "government", "working in", "work at", "ctc")),
        DictEntry("self-employed", listOf("self employed", "self-employed", "freelanc", "consultant", "professional", "doctor", "lawyer", "ca ", "architect")),
        DictEntry("business", listOf("business owner", "businessman", "proprietor", "trader", "shop owner", "entrepreneur", "own business", "own a business")),
        DictEntry("student", listOf("student", "studying", "no income", "unemployed"))
    )

    private val CITIES = listOf(
        "mumbai", "delhi", "new delhi", "bengaluru", "bangalore", "hyderabad", "ahmedabad",
        "chennai", "kolkata", "surat", "pune", "jaipur", "lucknow", "kanpur", "nagpur", "indore",
        "thane", "bhopal", "visakhapatnam", "patna", "vadodara", "ghaziabad", "ludhiana", "agra",
        "nashik", "coimbatore", "kochi", "chandigarh", "guwahati", "gurgaon", "gurugram", "noida"
    )

    private val NAME_STOP = setOf(
        "in", "at", "from", "on", "the", "a", "an", "is", "am", "looking", "interested",
        "salaried", "self", "employed", "working", "going", "here", "there", "not", "no",
        "yes", "based", "currently", "still", "just", "really", "very", "so", "also",
        "applying", "planning", "trying", "unemployed"
    )

    private val I = setOf(RegexOption.IGNORE_CASE)

    private val RE_PAN = Regex("\\b([A-Z]{5}[0-9]{4}[A-Z])\\b", I)
    private val RE_AADHAAR = Regex("\\b(\\d{4}\\s?\\d{4}\\s?\\d{4})\\b")
    private val RE_MOBILE = Regex("(?:\\+91[\\-\\s]?|0)?([6-9]\\d{9})\\b")
    private val RE_EMAIL = Regex("\\b([a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,})\\b", I)
    private val RE_PINCODE = Regex("\\b([1-9]\\d{5})\\b")
    private val RE_CIBIL = Regex("\\b(?:cibil|credit\\s*score|score)\\D{0,8}(\\d{3})\\b", I)
    private val RE_AGE = Regex("\\b(?:age|aged|i am|i'm|im)\\s*(\\d{1,2})\\b|\\b(\\d{1,2})\\s*(?:years?\\s*old|yrs?\\s*old|yo)\\b", I)
    private val RE_NAME = Regex("\\b(?:my\\s+(?:full\\s+)?name\\s+is|name\\s*[:=]\\s*|i am|i'm|this is)\\s+([a-z][a-z'.]+(?:\\s+[a-z][a-z'.]+){0,2})", I)

    private val RE_CRORE = Regex("(?:₹|rs\\.?|inr)?\\s*\\b(\\d+(?:\\.\\d+)?)\\s*(?:crore|crores|cr)\\b", I)
    private val RE_LAKH = Regex("(?:₹|rs\\.?|inr)?\\s*\\b(\\d+(?:\\.\\d+)?)\\s*(?:lakh|lakhs|lac|lacs|lpa|l)\\b", I)
    private val RE_THOUSAND = Regex("(?:₹|rs\\.?|inr)?\\s*\\b(\\d+(?:\\.\\d+)?)\\s*(?:thousand|k)\\b", I)
    private val RE_CURRENCY_NUM = Regex("(?:₹|rs\\.?|inr)\\s*\\b(\\d{4,})", I)

    private val RE_TENURE_YEAR = Regex("(\\d+)\\s*(?:year|years|yr|yrs)\\b", I)
    private val RE_TENURE_MONTH = Regex("(\\d+)\\s*(?:month|months|mo|mos)\\b", I)

    private val INCOME_CUE = Regex("(salary|income|earn|per month|per annum|monthly|\\blpa\\b|\\bctc\\b|in hand|a month|\\bp\\.?m\\.?\\b|\\bp\\.?a\\.?\\b)", I)
    private val LOAN_CUE = Regex("(loan|borrow|amount|require|fund|finance|want|need|for a|for my)", I)
    private val ANNUAL_CUE = Regex("(per year|per annum|\\bp\\.?a\\.?\\b|annual|yearly|\\blpa\\b|\\bctc\\b)", I)
    private val MONTHLY_CUE = Regex("(per month|monthly|\\bp\\.?m\\.?\\b|a month|every month|in hand)", I)
    private val CLAUSE_SPLIT = Regex("\\s*(?:,|;|\\band\\b|\\bbut\\b)\\s*", I)

    /* ---------- amount parsing ---------- */

    fun parseAmount(text: String): Long? {
        val t = text.lowercase().replace(",", "")
        RE_CRORE.find(t)?.let { return (it.groupValues[1].toDouble() * 1e7).roundToLong() }
        RE_LAKH.find(t)?.let { return (it.groupValues[1].toDouble() * 1e5).roundToLong() }
        RE_THOUSAND.find(t)?.let { return (it.groupValues[1].toDouble() * 1e3).roundToLong() }
        RE_CURRENCY_NUM.find(t)?.let { return it.groupValues[1].toLong() }
        return null
    }

    private data class Income(val monthly: Long, val basis: String)

    private fun parseIncome(text: String): Income? {
        val t = text.lowercase()
        var isAnnual = ANNUAL_CUE.containsMatchIn(t)
        val isMonthly = MONTHLY_CUE.containsMatchIn(t)
        val amt = parseAmount(text) ?: return null
        if (Regex("\\blpa\\b").containsMatchIn(t)) isAnnual = true
        val monthly = if (isAnnual) (amt / 12.0).roundToLong() else amt
        val basis = if (isAnnual) "annual" else if (isMonthly) "monthly" else "assumed-monthly"
        return Income(monthly, basis)
    }

    fun parseTenure(text: String): Int? {
        RE_TENURE_YEAR.find(text)?.let { return it.groupValues[1].toInt() * 12 }
        RE_TENURE_MONTH.find(text)?.let { return it.groupValues[1].toInt() }
        return null
    }

    /* ---------- dictionary matching (longest keyword wins) ---------- */

    private fun matchDict(text: String, dict: List<DictEntry>): String? {
        val t = " ${text.lowercase()} "
        var bestId: String? = null
        var bestLen = 0
        for (entry in dict) for (w in entry.words) {
            if (t.contains(w) && w.length > bestLen) {
                bestLen = w.length; bestId = entry.id
            }
        }
        return bestId
    }

    private fun matchCity(text: String): String? {
        val t = " ${text.lowercase()} "
        for (c in CITIES.sortedByDescending { it.length }) {
            if (t.contains(" $c ") || t.contains(" $c,")) {
                return c.split(" ").joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
            }
        }
        return null
    }

    /* ---------- intents ---------- */

    private data class IntentRule(val id: String, val re: Regex)

    private val INTENT_RULES = listOf(
        IntentRule("greet", Regex("\\b(hi|hii|hello|hey|namaste|namaskar|good (morning|afternoon|evening)|start)\\b", I)),
        IntentRule("help", Regex("\\b(help|how (do|does|to)|what can you|guide|assist|confused|stuck)\\b", I)),
        IntentRule("recommend", Regex("\\b(recommend|suggest|which loan|best loan|what loan|option|product|eligible for)", I)),
        IntentRule("eligibility", Regex("\\b(eligib|qualify|how much (can|do) i get|max(imum)? (loan|amount)|approve)", I)),
        IntentRule("emi", Regex("\\b(emi|instal|monthly payment|repay|interest|calculate|how much per month)", I)),
        IntentRule("documents", Regex("\\b(document|papers|kyc|what do i need|proof)", I)),
        IntentRule("apply", Regex("\\b(apply|application|want (a|to) loan|need (a|money|loan)|take a loan|get a loan|proceed|submit)", I)),
        IntentRule("affirm", Regex("^(yes|yeah|yep|sure|ok|okay|correct|right|haan|yup|do it|go ahead|please)\\b", I)),
        IntentRule("deny", Regex("^(no|nope|not|nah|cancel|wrong|nahi)\\b", I))
    )

    private fun classifyIntents(text: String): MutableList<String> {
        val hits = mutableListOf<String>()
        for (r in INTENT_RULES) if (r.re.containsMatchIn(text)) hits.add(r.id)
        return hits
    }

    /* ---------- public parse ---------- */

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        val e = Entities()

        e.loanType = matchDict(text, LOAN_TYPES)
        e.employment = matchDict(text, EMPLOYMENT)
        e.city = matchCity(text)

        // amounts assigned per-clause: loan amount vs income don't collide
        var incomeVal: Income? = null
        var loanVal: Long? = null
        var orphanVal: Long? = null
        for (c in CLAUSE_SPLIT.split(text)) {
            val a = parseAmount(c) ?: continue
            val hasIncome = INCOME_CUE.containsMatchIn(c)
            val hasLoan = LOAN_CUE.containsMatchIn(c) || matchDict(c, LOAN_TYPES) != null
            when {
                hasIncome && incomeVal == null -> incomeVal = parseIncome(c) ?: Income(a, "assumed-monthly")
                hasLoan && loanVal == null -> loanVal = a
                orphanVal == null -> orphanVal = a
            }
        }
        if (loanVal == null && orphanVal != null) loanVal = orphanVal
        incomeVal?.let { e.monthlyIncome = it.monthly; e.incomeBasis = it.basis }
        loanVal?.let { e.amount = it }

        parseTenure(text)?.let { e.tenureMonths = it }

        RE_PAN.find(text)?.let { e.pan = it.groupValues[1].uppercase() }
        RE_AADHAAR.find(text)?.let { e.aadhaar = it.groupValues[1].replace(" ", "") }
        RE_EMAIL.find(text)?.let { e.email = it.groupValues[1].lowercase() }
        RE_MOBILE.find(text)?.let { e.mobile = it.groupValues[1] }
        RE_PINCODE.find(text)?.let { m ->
            val pin = m.groupValues[1]
            if (e.aadhaar == null && (e.mobile == null || !e.mobile!!.contains(pin))) e.pincode = pin
        }
        RE_CIBIL.find(text)?.let {
            val sc = it.groupValues[1].toInt()
            if (sc in 300..900) e.cibil = sc
        }
        RE_AGE.find(text)?.let {
            val raw2 = it.groupValues[1].ifEmpty { it.groupValues[2] }
            raw2.toIntOrNull()?.let { age -> if (age in 18..75) e.age = age }
        }
        RE_NAME.find(text)?.let {
            val nm = it.groupValues[1].trim()
            val first = nm.split(Regex("\\s+")).first().lowercase()
            val words = nm.split(Regex("\\s+"))
            if (!nm.first().isDigit() && first !in NAME_STOP &&
                matchDict(nm, EMPLOYMENT) == null && matchCity(nm) == null && words.size <= 3
            ) {
                e.fullName = words.joinToString(" ") { w -> w.replaceFirstChar { ch -> ch.uppercase() } }
            }
        }

        val intents = classifyIntents(text)
        if (!intents.contains("apply") &&
            (e.loanType != null || e.amount != null) &&
            Regex("\\b(want|need|looking|require|get|take|apply)\\b", I).containsMatchIn(text)
        ) {
            intents.add("apply")
        }

        return Parsed(text, intents, e)
    }
}
