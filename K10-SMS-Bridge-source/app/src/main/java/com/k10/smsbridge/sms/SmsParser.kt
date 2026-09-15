package com.k10.smsbridge.sms

import com.k10.smsbridge.rules.RuleConfig
import com.k10.smsbridge.rules.RuleValidator
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

data class ParsedSms(
    val uniqueLocalId: String = UUID.randomUUID().toString(),
    val duplicateKey: String,
    val payerName: String,
    val amountMinor: Long,
    val transactionDate: LocalDate,
    val smsReceivedTimestamp: Long,
    val accountLast4: String,
    val paymentMethod: String,
    val senderId: String,
    val rawEligibleSms: String
)

data class ParseResult(val transaction: ParsedSms?, val reason: String) {
    val eligible: Boolean get() = transaction != null
}

object SmsParser {
    private val creditSignal = Regex("\\b(received|credited|deposited)\\b", RegexOption.IGNORE_CASE)
    private val accountReference = Regex(
        "(?:a/c|acct|account)(?:\\s*(?:no\\.?|number))?[\\s.:*xX-]*(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE
    )

    fun parse(sender: String, body: String, receivedAt: Long, rules: RuleConfig): ParseResult {
        if (!rules.enabled) return ParseResult(null, "Matching rules are disabled")
        val normalizedSender = sender.trim().uppercase()
        if (RuleValidator.hasImmutableExclusion(body)) return ParseResult(null, "Message contains a locally blocked OTP, debit, or promotional term")
        if (!RuleValidator.hasImmutableCreditSignal(body)) return ParseResult(null, "Message is not a credit transaction")
        if (rules.excludedKeywords.any { body.contains(it, true) }) return ParseResult(null, "Message contains an excluded term")
        if (rules.requiredKeywords.none { body.contains(it, true) }) return ParseResult(null, "A required credit keyword is missing")
        if (!hasExpectedDestinationAccount(body, rules.accountLast4)) {
            return ParseResult(null, "Configured account is not the receiving account")
        }

        val amountText = captureFromTemplates(body, rules.amountPatterns, "{amount}", "[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
            ?: safeAmountFallback(body)
            ?: return ParseResult(null, "No supported banking amount pattern matched")
        val amountMinor = runCatching {
            BigDecimal(amountText.replace(",", "")).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        }.getOrNull()?.takeIf { it > 0 } ?: return ParseResult(null, "Amount is invalid")

        val payer = captureFromTemplates(body, rules.payerPatterns, "{payer}", "[\\p{L}][\\p{L} .'-]{0,79}")
            ?.trim()?.trimEnd('.') ?: "Unknown payer"
        if (rules.excludedPayerNames.any { normalizePayer(it) == normalizePayer(payer) }) {
            return ParseResult(null, "Payer is excluded by K10 rules")
        }
        val dateText = captureFromTemplates(body, rules.datePatterns, "{date}", "[0-9]{1,2}(?:[ ./-][A-Za-z]{3,9}|[./-][0-9]{1,2})(?:[ ./-][0-9]{2,4})?")
        val transactionDate = dateText?.let { parseDate(it, receivedAt) }
            ?: Instant.ofEpochMilli(receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val method = captureFromTemplates(body, rules.paymentMethodPatterns, "{payment_method}", "[A-Za-z][A-Za-z0-9 -]{1,30}")
            ?.trim()?.trimEnd('.')?.uppercase() ?: "UNKNOWN"
        val key = duplicateKey(normalizedSender, amountMinor, payer, rules.accountLast4, transactionDate, body)
        return ParseResult(
            ParsedSms(
                duplicateKey = key,
                payerName = payer,
                amountMinor = amountMinor,
                transactionDate = transactionDate,
                smsReceivedTimestamp = receivedAt,
                accountLast4 = rules.accountLast4,
                paymentMethod = method,
                senderId = normalizedSender,
                rawEligibleSms = body
            ),
            "Eligible"
        )
    }

    fun isFinanciallyScopedCandidate(sender: String, body: String, rules: RuleConfig): Boolean =
        hasExpectedDestinationAccount(body, rules.accountLast4) &&
            RuleValidator.hasImmutableCreditSignal(body) &&
            !RuleValidator.hasImmutableExclusion(body)

    /** Requires the expected account to be the destination nearest the credit signal, not a source account. */
    internal fun hasExpectedDestinationAccount(body: String, expectedLast4: String): Boolean {
        for (credit in creditSignal.findAll(body)) {
            val after = accountReference.find(body, credit.range.last + 1)
            if (after != null && after.range.first - credit.range.last <= 90) {
                val connector = body.substring(credit.range.last + 1, after.range.first).trim().lowercase()
                if (!Regex("(?:^|\\s)(?:from|by)\\s*$").containsMatchIn(connector)) {
                    return after.groupValues[1] == expectedLast4
                }
            }

            val beforeText = body.substring(0, credit.range.first)
            val before = accountReference.findAll(beforeText).lastOrNull()
            if (before != null && credit.range.first - before.range.last <= 90) {
                val sourcePrefix = beforeText.substring((before.range.first - 12).coerceAtLeast(0), before.range.first)
                if (!Regex("(?:from|by)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(sourcePrefix)) {
                    return before.groupValues[1] == expectedLast4
                }
            }
        }
        return false
    }

    private fun normalizePayer(value: String): String = value
        .lowercase(Locale.ENGLISH)
        .replace(Regex("^(?:mr|mrs|ms|miss|shri|sri|dr)\\.?\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun safeAmountFallback(body: String): String? {
        val amount = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
        val currency = "(?:₹|Rs\\.?|INR)"
        val credit = "(?:received|credited|deposited)"
        return Regex("$currency\\s*$amount\\s*(?:has\\s+been\\s+)?$credit", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)
            ?: Regex("$credit(?:\\s+with)?\\s*$currency\\s*$amount", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.getOrNull(1)
    }

    private fun captureFromTemplates(body: String, templates: List<String>, placeholder: String, capture: String): String? {
        for (template in templates) {
            val index = template.indexOf(placeholder)
            if (index < 0) continue
            fun literal(value: String): String = value.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
            val before = literal(template.substring(0, index))
            val after = literal(template.substring(index + placeholder.length))
            val regex = Regex("$before\\s*($capture)\\s*$after", setOf(RegexOption.IGNORE_CASE))
            regex.find(body)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun parseDate(value: String, receivedAt: Long): LocalDate? {
        val received = Instant.ofEpochMilli(receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val clean = value.trim().replace(Regex("\\s+"), " ")
        val formats = listOf(
            "d MMM uuuu", "d MMMM uuuu", "d-MMM-uuuu", "d/MMM/uuuu",
            "d-M-uuuu", "d/M/uuuu", "d.M.uuuu", "d-M-uu", "d/M/uu"
        )
        formats.forEach { pattern ->
            try { return LocalDate.parse(clean, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) }
            catch (_: DateTimeParseException) { }
        }
        val withoutYear = listOf("d MMM", "d MMMM")
        withoutYear.forEach { pattern ->
            try {
                var candidate = LocalDate.parse("$clean ${received.year}", DateTimeFormatter.ofPattern("$pattern uuuu", Locale.ENGLISH))
                if (candidate.isAfter(received.plusDays(31))) candidate = candidate.minusYears(1)
                return candidate
            } catch (_: DateTimeParseException) { }
        }
        return null
    }

    private fun duplicateKey(sender: String, amountMinor: Long, payer: String, account: String, date: LocalDate, raw: String): String {
        val canonical = listOf(sender, amountMinor, payer.trim().uppercase(), account, date, raw.trim()).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
