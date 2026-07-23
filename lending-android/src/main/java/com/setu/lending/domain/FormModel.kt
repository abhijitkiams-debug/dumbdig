package com.setu.lending.domain

/**
 * Declarative description of the loan application form. The assistant only ever
 * reads/writes field values by id — it never reaches into the UI widgets — so
 * the same voice engine could drive any form that exposes fields like these.
 */

enum class FieldType { TEXT, NUMBER, CHOICE, CHECKBOX }

data class Choice(val value: String, val label: String)

data class FormField(
    val id: String,
    val label: String,
    val group: String,
    val type: FieldType,
    val required: Boolean,
    val hint: String? = null,
    val choices: List<Choice> = emptyList()
)

object FormSchema {

    val EMPLOYMENT_CHOICES = listOf(
        Choice("salaried", "Salaried"),
        Choice("self-employed", "Self-employed / Professional"),
        Choice("business", "Business owner"),
        Choice("student", "Student")
    )

    val LOAN_CHOICES = listOf(
        Choice("personal", "Personal Loan"),
        Choice("home", "Home Loan"),
        Choice("gold", "Gold Loan"),
        Choice("business", "Business Loan"),
        Choice("car", "Car Loan"),
        Choice("twowheeler", "Two-Wheeler Loan"),
        Choice("education", "Education Loan")
    )

    val FIELDS: List<FormField> = listOf(
        FormField("fullName", "Full name", "Personal details", FieldType.TEXT, true, "As per PAN"),
        FormField("mobile", "Mobile number", "Personal details", FieldType.TEXT, true, "10-digit mobile"),
        FormField("email", "Email", "Personal details", FieldType.TEXT, false),
        FormField("age", "Age", "Personal details", FieldType.NUMBER, true),
        FormField("city", "City", "Personal details", FieldType.TEXT, true),
        FormField("pincode", "Pincode", "Personal details", FieldType.TEXT, false),

        FormField("pan", "PAN", "KYC", FieldType.TEXT, true, "ABCDE1234F"),
        FormField("aadhaar", "Aadhaar", "KYC", FieldType.TEXT, false, "Stored masked"),

        FormField("employment", "Employment type", "Employment & income", FieldType.CHOICE, true, choices = EMPLOYMENT_CHOICES),
        FormField("monthlyIncome", "Monthly income (₹)", "Employment & income", FieldType.NUMBER, true),
        FormField("existingEmi", "Existing EMIs (₹/mo)", "Employment & income", FieldType.NUMBER, false),
        FormField("cibil", "CIBIL score", "Employment & income", FieldType.NUMBER, false, "300–900 (optional)"),

        FormField("loanType", "Loan type", "Loan requirement", FieldType.CHOICE, true, choices = LOAN_CHOICES),
        FormField("amount", "Loan amount (₹)", "Loan requirement", FieldType.NUMBER, true),
        FormField("tenure", "Tenure (months)", "Loan requirement", FieldType.NUMBER, false),
        FormField("purpose", "Purpose", "Loan requirement", FieldType.TEXT, false),

        FormField("consent", "Consent", "Consent", FieldType.CHECKBOX, true)
    )

    val byId = FIELDS.associateBy { it.id }
    val groups: List<String> = FIELDS.map { it.group }.distinct()

    fun labelFor(id: String): String = byId[id]?.label ?: id

    /** Priority order the assistant walks when choosing the next thing to ask. */
    val ASK_ORDER = listOf(
        "loanType", "amount", "monthlyIncome", "employment", "fullName",
        "mobile", "age", "pan", "city", "pincode", "email", "aadhaar",
        "cibil", "tenure", "consent"
    )
}

/** Completion snapshot over the required fields. */
data class Completion(val done: Int, val required: Int, val pct: Int, val missing: List<FormField>)

fun computeCompletion(values: Map<String, String>): Completion {
    var done = 0
    var req = 0
    val missing = mutableListOf<FormField>()
    for (f in FormSchema.FIELDS) {
        if (!f.required) continue
        req++
        val v = values[f.id]
        if (!v.isNullOrBlank() && v != "false") done++ else missing.add(f)
    }
    val pct = if (req == 0) 0 else Math.round(done * 100.0 / req).toInt()
    return Completion(done, req, pct, missing)
}
