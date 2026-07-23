package com.setu.lending.domain

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Indian lending product catalog + eligibility & EMI engine.
 *
 * Direct Kotlin port of the (browser-tested) products.js logic. All money is in
 * Indian Rupees and formatting uses Indian digit grouping (lakh / crore).
 */

data class LoanProduct(
    val id: String,
    val name: String,
    val emoji: String,
    val blurb: String,
    val minAmount: Long,
    val maxAmount: Long,
    val minRate: Double,
    val maxRate: Double,
    val minMonths: Int,
    val maxMonths: Int,
    val defaultMonths: Int,
    val secured: Boolean,
    val minIncome: Long,
    val minAge: Int,
    val maxAge: Int,
    val docs: List<String>
)

data class Profile(
    val loanType: String? = null,
    val amount: Long? = null,
    val monthlyIncome: Long? = null,
    val existingEmi: Long = 0,
    val employment: String? = null,
    val cibil: Int? = null,
    val age: Int? = null
)

data class Assessment(
    val product: LoanProduct,
    val eligible: Boolean,
    val rate: Double,
    val tenureMonths: Int,
    val maxEligible: Long,
    val sizedAmount: Long,
    val emi: Long,
    val totalPayable: Long,
    val totalInterest: Long,
    val overAsk: Boolean,
    val reasons: List<String>,
    var score: Double = 0.0
)

object Products {

    val ALL: List<LoanProduct> = listOf(
        LoanProduct("personal", "Personal Loan", "💰",
            "Unsecured cash for any need — weddings, travel, medical, debt consolidation.",
            50_000, 40_00_000, 10.5, 24.0, 12, 60, 48, false, 15_000, 21, 60,
            listOf("PAN", "Aadhaar", "3 months bank statement", "Latest salary slips")),
        LoanProduct("home", "Home Loan", "🏠",
            "Buy, build or renovate your home. Long tenure, lowest rates, tax benefits.",
            5_00_000, 5_00_00_000, 8.4, 10.5, 60, 360, 240, true, 25_000, 21, 65,
            listOf("PAN", "Aadhaar", "Income proof", "Property papers", "Sale agreement")),
        LoanProduct("gold", "Gold Loan", "🥇",
            "Instant funds against gold jewellery. Minimal paperwork, quick disbursal.",
            25_000, 50_00_000, 9.0, 18.0, 3, 36, 12, true, 0, 18, 70,
            listOf("PAN / Aadhaar", "Gold to pledge")),
        LoanProduct("business", "Business Loan", "💼",
            "Working capital & growth funding for MSMEs and self-employed professionals.",
            1_00_000, 50_00_000, 14.0, 24.0, 12, 48, 36, false, 30_000, 24, 65,
            listOf("PAN", "GST returns", "6-12 months bank statement", "Business proof")),
        LoanProduct("twowheeler", "Two-Wheeler Loan", "🏍️",
            "Ride home your bike or scooter with up to 100% on-road financing.",
            30_000, 3_00_000, 11.0, 22.0, 12, 48, 36, true, 10_000, 21, 60,
            listOf("PAN", "Aadhaar", "Income proof", "Vehicle quotation")),
        LoanProduct("car", "Car Loan", "🚗",
            "New or used car financing with flexible tenure and competitive rates.",
            1_00_000, 1_00_00_000, 9.0, 14.0, 12, 84, 60, true, 20_000, 21, 65,
            listOf("PAN", "Aadhaar", "Income proof", "Car quotation")),
        LoanProduct("education", "Education Loan", "🎓",
            "Fund higher studies in India or abroad. Moratorium during the course.",
            50_000, 75_00_000, 8.5, 14.0, 12, 180, 84, false, 0, 18, 35,
            listOf("PAN", "Aadhaar", "Admission letter", "Fee structure", "Co-applicant KYC"))
    )

    private val byId = ALL.associateBy { it.id }
    fun byId(id: String?): LoanProduct? = id?.let { byId[it] }

    /* ---------- formatting (Indian numbering) ---------- */

    fun inr(value: Long?): String {
        if (value == null) return "—"
        val sign = if (value < 0) "-" else ""
        val s = kotlin.math.abs(value).toString()
        if (s.length <= 3) return "$sign₹$s"
        val last3 = s.substring(s.length - 3)
        var rest = s.substring(0, s.length - 3)
        // group the remaining digits in pairs, right to left
        val sb = StringBuilder()
        var count = 0
        for (i in rest.length - 1 downTo 0) {
            sb.append(rest[i])
            count++
            if (count % 2 == 0 && i != 0) sb.append(',')
        }
        rest = sb.reverse().toString()
        return "$sign₹$rest,$last3"
    }

    fun inr(value: Double): String = inr(value.roundToLong())

    fun inrShort(value: Long?): String {
        if (value == null) return "—"
        return when {
            value >= 1_00_00_000 -> "₹" + trim(value / 1_00_00_000.0) + " Cr"
            value >= 1_00_000 -> "₹" + trim(value / 1_00_000.0) + " L"
            value >= 1_000 -> "₹" + (value / 1000) + "k"
            else -> inr(value)
        }
    }

    private fun trim(d: Double): String {
        val r = (d * 10).roundToLong() / 10.0
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }

    /* ---------- finance maths ---------- */

    fun emi(principal: Double, annualRatePct: Double, months: Int): Double {
        if (months <= 0) return 0.0
        val r = annualRatePct / 12.0 / 100.0
        if (r == 0.0) return principal / months
        val f = (1 + r).pow(months)
        return principal * r * f / (f - 1)
    }

    fun principalFromEmi(budgetEmi: Double, annualRatePct: Double, months: Int): Double {
        if (months <= 0) return 0.0
        val r = annualRatePct / 12.0 / 100.0
        if (r == 0.0) return budgetEmi * months
        val f = (1 + r).pow(months)
        return budgetEmi * (f - 1) / (r * f)
    }

    /* ---------- credit / risk ---------- */

    private fun offeredRate(p: LoanProduct, profile: Profile): Double {
        val span = p.maxRate - p.minRate
        var rate = p.minRate
        val cibil = profile.cibil
        rate += when {
            cibil == null -> span * 0.4
            cibil >= 780 -> 0.0
            cibil >= 750 -> span * 0.15
            cibil >= 700 -> span * 0.45
            cibil >= 650 -> span * 0.75
            else -> span // subprime -> top of band
        }
        if (cibil != null && cibil < 650) rate = p.maxRate
        if (profile.employment == "self-employed" || profile.employment == "business") rate += 0.75
        return minOf(p.maxRate, (rate * 100).roundToLong() / 100.0)
    }

    private fun foirCap(profile: Profile): Double {
        val c = profile.cibil ?: return 0.45
        return when {
            c >= 750 -> 0.55
            c >= 700 -> 0.50
            c >= 650 -> 0.42
            else -> 0.35
        }
    }

    private fun safeTenure(p: LoanProduct, profile: Profile): Int {
        var months = p.defaultMonths
        profile.age?.let { age ->
            val monthsToExit = (p.maxAge - age) * 12
            if (monthsToExit > 0) months = minOf(months, monthsToExit)
        }
        return maxOf(p.minMonths, minOf(p.maxMonths, months))
    }

    fun assess(p: LoanProduct, profile: Profile): Assessment {
        val reasons = mutableListOf<String>()
        var eligible = true
        val income = profile.monthlyIncome ?: 0
        val existingEmi = profile.existingEmi

        profile.age?.let { age ->
            if (age < p.minAge || age > p.maxAge) {
                eligible = false
                reasons.add("Age must be ${p.minAge}–${p.maxAge} for this product.")
            }
        }
        if (p.minIncome > 0 && income > 0 && income < p.minIncome) {
            eligible = false
            reasons.add("Needs monthly income of at least ${inr(p.minIncome)}.")
        }
        if (profile.cibil != null && profile.cibil < 640 && !p.secured) {
            eligible = false
            reasons.add("Credit score below 640 rarely qualifies for an unsecured loan.")
        }

        val rate = offeredRate(p, profile)
        val months = safeTenure(p, profile)

        var maxEligible = p.maxAmount
        if (income > 0) {
            val emiBudget = maxOf(0.0, income * foirCap(profile) - existingEmi)
            val byIncome = principalFromEmi(emiBudget, rate, months)
            maxEligible = minOf(p.maxAmount.toDouble(), byIncome).toLong()
            if (emiBudget <= 0) {
                eligible = false
                reasons.add("Existing EMIs already use up your repayment capacity.")
            }
        }
        maxEligible = maxOf(0L, floor(maxEligible / 1000.0).toLong() * 1000)

        val requested = profile.amount ?: 0
        val overAsk = requested > 0 && requested > maxEligible && income > 0
        if (overAsk) {
            reasons.add("For ${inr(requested)} you may need a longer tenure or co-applicant; eligible now up to ${inr(maxEligible)}.")
        }

        var sizing = if (requested > 0) minOf(requested, if (maxEligible > 0) maxEligible else requested)
        else minOf(if (maxEligible > 0) maxEligible else p.minAmount, p.maxAmount)
        if (sizing < p.minAmount) sizing = p.minAmount
        val monthlyEmi = emi(sizing.toDouble(), rate, months)

        return Assessment(
            product = p,
            eligible = eligible,
            rate = rate,
            tenureMonths = months,
            maxEligible = maxEligible,
            sizedAmount = sizing,
            emi = monthlyEmi.roundToLong(),
            totalPayable = (monthlyEmi * months).roundToLong(),
            totalInterest = (monthlyEmi * months - sizing).roundToLong(),
            overAsk = overAsk,
            reasons = reasons
        )
    }

    fun recommend(profile: Profile): List<Assessment> {
        val results = ALL.map { assess(it, profile) }
        results.forEach { r ->
            var score = 0.0
            if (profile.loanType != null && r.product.id == profile.loanType) score += 1000
            if (r.eligible) score += 300
            if (r.overAsk) score -= 120
            score += minOf(150.0, r.maxEligible / 50_000.0)
            score += (28 - r.rate) * 4
            if (!r.product.secured) score += 15
            r.score = score
        }
        return results.sortedByDescending { it.score }
    }
}
