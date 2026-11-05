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
    fun excludedPayerRemainsEligibleButIsMarkedForSilentStorage() {
        val rules = RuleConfig.DEFAULT.copy(excludedPayerNames = listOf("A Person"))
        val result = SmsParser.parse("SLICE", "Rs 2000 received in A/c xx7972 on 2 Sep from A Person via UPI.", receivedAt, rules)
        assertTrue(result.reason, result.eligible)
        assertTrue(result.transaction?.payerExcluded == true)
        assertEquals("A Person", result.transaction?.payerName)
    }

    @Test
    fun defaultRulesDoNotHideAPreviouslyHardCodedName() {
        val result = SmsParser.parse("SLICE", "Rs 10 received in A/c xx7972 on 2 Sep from Devasish Bhagawati via UPI.", receivedAt, RuleConfig.DEFAULT)
        assertTrue(result.reason, result.eligible)
        assertFalse(result.transaction?.payerExcluded == true)
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

    @Test fun suppliedImpsMessagesExtractNamesAndMethodsWithoutChangingLegacyKeys() {
        val cases=listOf(
            Pair("Rs. 2,500 received in a/c xx7972 on 04-Nov-26 from DEVASISH BHAGAWATI (IMPS Ref: 213261923086) - slice","DEVASISH BHAGAWATI"),
            Pair("Rs. 3,000 received in a/c xx7972 on 03-Nov-26 from LUHIT MIRI (IMPS Ref: 630781500718) - slice","LUHIT MIRI")
        )
        for((body,name) in cases){
            val tx=requireNotNull(SmsParser.parse("JD-SLCBNK-T",body,receivedAt,RuleConfig.DEFAULT).transaction)
            assertEquals(name,tx.payerName);assertEquals("IMPS",tx.paymentMethod)
            val canonical=listOf("JD-SLCBNK-T",tx.amountMinor,"Unknown payer".uppercase(),tx.accountLast4,tx.transactionDate,body.trim()).joinToString("|")
            val oldKey=java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString(""){"%02x".format(it)}
            assertEquals("Metadata correction must not create a second payment on rescan",oldKey,tx.duplicateKey)
        }
    }
    @Test fun impsNamesCanWrapAndCannotUseTheSendingAccount() {
        val wrapped="Rs. 3000 received in a/c xx7972 from LUHIT\nMIRI (IMPS Ref: 630781500718) - slice"
        assertEquals("LUHIT MIRI",SmsParser.recoverImpsMetadata(wrapped)?.payerName)
        assertEquals(null,SmsParser.recoverImpsMetadata("Rs. 400 received in a/c 5778 from A/c 7972 (IMPS Ref: 12345678) - slice"))
        assertEquals(null,SmsParser.recoverImpsMetadata(wrapped+" OTP 123456"))
    }

    private fun assertRejected(body: String) {
        assertFalse(SmsParser.parse("SLICE", body, receivedAt, RuleConfig.DEFAULT).eligible)
    }
}

