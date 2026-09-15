package com.k10.smsbridge.sms

import com.k10.smsbridge.rules.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SmsParserTest {
    private val receivedAt = LocalDateTime.of(2026, 9, 6, 20, 15).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun exactRequiredSampleIsEligible() {
        val result = SmsParser.parse(
            "SLICE",
            "Rs 2000 received on in A/c xx7972 on 2 Sep from RUBUL Sonowal via UPI.",
            receivedAt,
            RuleConfig.DEFAULT
        )

        assertTrue(result.reason, result.eligible)
        val transaction = requireNotNull(result.transaction)
        assertEquals(200_000, transaction.amountMinor)
        assertEquals("7972", transaction.accountLast4)
        assertEquals("RUBUL Sonowal", transaction.payerName)
        assertEquals("2026-09-02", transaction.transactionDate.toString())
        assertEquals("UPI", transaction.paymentMethod)
    }

    @Test fun rejectsOtpEvenWhenOtherTermsMatch() = assertRejected("Rs 2000 received in A/c xx7972 on 2 Sep from A Person via UPI. OTP 123456")
    @Test fun rejectsDebit() = assertRejected("Rs 2000 received then debited in A/c xx7972 on 2 Sep from A Person via UPI.")
    @Test fun rejectsWrongAccount() = assertRejected("Rs 2000 received in A/c xx1234 on 2 Sep from A Person via UPI.")

    @Test
    fun rejectsExpectedAccountWhenItIsTheSendingAccount() = assertRejected(
        "Rs. 400 received in A/c 5778 from A/c 7972 on 15-Sep-26. (Ref ID: 625800183145). Avl Bal Rs. 4,126.93. - slice"
    )

    @Test
    fun acceptsExpectedAccountBeforeCreditedWording() {
        val result = SmsParser.parse("SLICE", "Your A/c XX7972 has been credited with INR 400.00 via UPI.", receivedAt, RuleConfig.DEFAULT)
        assertTrue(result.reason, result.eligible)
    }

    @Test
    fun acceptsUnlistedSenderWhenFinancialSafeguardsMatch() {
        val result = SmsParser.parse("AX-BANK", "Rs 2000 received in A/c xx7972 on 2 Sep from A Person via UPI.", receivedAt, RuleConfig.DEFAULT)
        assertTrue(result.reason, result.eligible)
    }

    @Test
    fun acceptsRotatingSlicePrefixWithoutAnAppUpdate() {
        val result = SmsParser.parse(
            "JD-SLICE",
            "Rs 2000 received in A/c xx7972 on 2 Sep from A Person via UPI.",
            receivedAt,
            RuleConfig.DEFAULT
        )
        assertTrue(result.reason, result.eligible)
    }

    @Test
    fun acceptsCreditedWordingAndUsesSmsDateWhenTransactionDateIsAbsent() {
        val result = SmsParser.parse(
            "AX-BANK",
            "INR 2,000 credited to A/c XX7972 via UPI.",
            receivedAt,
            RuleConfig.DEFAULT
        )
        assertTrue(result.reason, result.eligible)
        assertEquals(200_000L, result.transaction?.amountMinor)
        assertEquals("2026-09-06", result.transaction?.transactionDate.toString())
    }

    @Test
    fun rejectsCasualMessageEvenWithAccountAndCreditWords() {
        val result = SmsParser.parse("FRIEND", "I received the account 7972 notes", receivedAt, RuleConfig.DEFAULT)
        assertFalse(result.eligible)
    }

    @Test
    fun decemberMessageReceivedInJanuaryUsesPreviousYear() {
        val january = LocalDateTime.of(2027, 1, 2, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = SmsParser.parse("SLICE", "Rs 10 received in A/c xx7972 on 31 Dec from A Person via UPI.", january, RuleConfig.DEFAULT)
        assertEquals("2026-12-31", result.transaction?.transactionDate.toString())
    }

    @Test
    fun duplicateKeyIsDeterministic() {
        val body = "Rs 2000 received in A/c xx7972 on 2 Sep from RUBUL Sonowal via UPI."
        val first = SmsParser.parse("SLICE", body, receivedAt, RuleConfig.DEFAULT).transaction
        val second = SmsParser.parse("SLICE", body, receivedAt, RuleConfig.DEFAULT).transaction
        assertEquals(first?.duplicateKey, second?.duplicateKey)
    }

    private fun assertRejected(body: String) {
        assertFalse(SmsParser.parse("SLICE", body, receivedAt, RuleConfig.DEFAULT).eligible)
    }
}
