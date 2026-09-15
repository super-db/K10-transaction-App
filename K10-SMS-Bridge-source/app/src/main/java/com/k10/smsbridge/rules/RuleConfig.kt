package com.k10.smsbridge.rules

import org.json.JSONArray
import org.json.JSONObject

data class RuleConfig(
    val version: String,
    val bankName: String,
    val allowedSenderIds: List<String>,
    val accountLast4: String,
    val requiredKeywords: List<String>,
    val optionalKeywords: List<String>,
    val excludedKeywords: List<String>,
    val amountPatterns: List<String>,
    val payerPatterns: List<String>,
    val datePatterns: List<String>,
    val paymentMethodPatterns: List<String>,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("bank_name", bankName)
        put("allowed_sender_ids", JSONArray(allowedSenderIds))
        put("account_last4", accountLast4)
        put("required_keywords", JSONArray(requiredKeywords))
        put("optional_keywords", JSONArray(optionalKeywords))
        put("excluded_keywords", JSONArray(excludedKeywords))
        put("amount_patterns", JSONArray(amountPatterns))
        put("payer_extraction_patterns", JSONArray(payerPatterns))
        put("date_extraction_patterns", JSONArray(datePatterns))
        put("payment_method_patterns", JSONArray(paymentMethodPatterns))
        put("enabled", enabled)
    }

    companion object {
        val DEFAULT = RuleConfig(
            version = "v1.0",
            bankName = "Slice",
            allowedSenderIds = listOf("SLICE", "XX-SLICE"),
            accountLast4 = "7972",
            requiredKeywords = listOf("received"),
            optionalKeywords = listOf("via UPI"),
            excludedKeywords = listOf("debited", "OTP"),
            amountPatterns = listOf("Rs {amount} received", "₹{amount} received"),
            payerPatterns = listOf("from {payer} via", "from {payer}."),
            datePatterns = listOf("on {date} from"),
            paymentMethodPatterns = listOf("via {payment_method}"),
            enabled = true
        )

        fun fromJson(json: JSONObject): RuleConfig = RuleConfig(
            version = json.getString("version"),
            bankName = json.optString("bank_name", "Slice"),
            allowedSenderIds = json.strings("allowed_sender_ids"),
            accountLast4 = json.getString("account_last4"),
            requiredKeywords = json.strings("required_keywords"),
            optionalKeywords = json.optStrings("optional_keywords"),
            excludedKeywords = json.strings("excluded_keywords"),
            amountPatterns = json.strings("amount_patterns"),
            payerPatterns = json.optStrings("payer_extraction_patterns").ifEmpty { DEFAULT.payerPatterns },
            datePatterns = json.optStrings("date_extraction_patterns").ifEmpty { DEFAULT.datePatterns },
            paymentMethodPatterns = json.optStrings("payment_method_patterns").ifEmpty { DEFAULT.paymentMethodPatterns },
            enabled = json.optBoolean("enabled", true)
        )
    }
}

private fun JSONObject.strings(name: String): List<String> = getJSONArray(name).toStrings()
private fun JSONObject.optStrings(name: String): List<String> = optJSONArray(name)?.toStrings().orEmpty()
private fun JSONArray.toStrings(): List<String> = (0 until length()).map { getString(it) }

object RuleValidator {
    private val version = Regex("^v\\d+\\.\\d+(?:\\.\\d+)?$")
    private val sender = Regex("^[A-Z0-9-]{3,20}$")
    private val immutableCreditWords = setOf("received", "credited", "deposited")
    val immutableExcludedWords = setOf(
        "otp", "one time password", "debited", "debit", "withdrawn", "purchase",
        "spent", "loan offer", "pre-approved", "cashback offer", "promotional"
    )

    fun validate(config: RuleConfig): List<String> = buildList {
        if (!version.matches(config.version)) add("version must look like v1.3")
        if (!config.bankName.equals("Slice", ignoreCase = true)) add("bank_name must be Slice")
        if (config.allowedSenderIds.isEmpty() || config.allowedSenderIds.size > 20) add("sender list is empty or too large")
        if (config.allowedSenderIds.any { !isLocallyApprovedSender(it) }) add("sender list contains a non-Slice or invalid sender")
        if (!Regex("^\\d{4}$").matches(config.accountLast4)) add("account_last4 must be exactly four digits")
        if (config.requiredKeywords.isEmpty()) add("at least one required credit keyword is required")
        if (config.requiredKeywords.none { word -> immutableCreditWords.any { it.equals(word.trim(), true) } }) {
            add("required_keywords must retain an approved credit keyword")
        }
        if (!config.excludedKeywords.map { it.lowercase() }.containsAll(listOf("otp", "debited"))) {
            add("excluded_keywords must include OTP and debited")
        }
        checkTemplates(config.amountPatterns, "{amount}", "amount_patterns", this)
        if (config.amountPatterns.any { pattern ->
                !(pattern.contains("Rs", true) || pattern.contains('₹')) ||
                    immutableCreditWords.none { pattern.contains(it, true) }
            }) add("each amount pattern must retain a currency marker and approved credit word")
        checkTemplates(config.payerPatterns, "{payer}", "payer patterns", this)
        checkTemplates(config.datePatterns, "{date}", "date patterns", this)
        checkTemplates(config.paymentMethodPatterns, "{payment_method}", "payment patterns", this)
    }

    fun isLocallyApprovedSender(value: String): Boolean {
        val normalized = value.trim().uppercase()
        return sender.matches(normalized) && normalized.split('-').lastOrNull() == "SLICE"
    }

    fun hasImmutableCreditSignal(body: String): Boolean =
        immutableCreditWords.any { body.contains(it, ignoreCase = true) }

    fun hasImmutableExclusion(body: String): Boolean =
        immutableExcludedWords.any { body.contains(it, ignoreCase = true) }

    private fun checkTemplates(values: List<String>, placeholder: String, label: String, errors: MutableList<String>) {
        if (values.isEmpty() || values.size > 20) errors += "$label must contain 1-20 entries"
        if (values.any {
                it.length !in 3..160 || it.count { c -> c == '{' } != 1 ||
                    it.count { c -> c == '}' } != 1 || !it.contains(placeholder) ||
                    it.indexOf(placeholder) != it.lastIndexOf(placeholder)
            }) {
            errors += "$label contains an unsafe template"
        }
    }
}
