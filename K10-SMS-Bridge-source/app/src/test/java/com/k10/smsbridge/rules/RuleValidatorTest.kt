package com.k10.smsbridge.rules

import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidatorTest {
    @Test
    fun defaultRulesAreValid() {
        assertTrue(RuleValidator.validate(RuleConfig.DEFAULT).isEmpty())
    }

    @Test
    fun cannotAuthorizePersonalSender() {
        val errors = RuleValidator.validate(RuleConfig.DEFAULT.copy(version = "v1.1", allowedSenderIds = listOf("MOM")))
        assertTrue(errors.any { it.contains("non-Slice") })
    }

    @Test
    fun cannotRemoveAccountValidation() {
        val errors = RuleValidator.validate(RuleConfig.DEFAULT.copy(version = "v1.1", accountLast4 = ""))
        assertTrue(errors.any { it.contains("four digits") })
    }

    @Test
    fun cannotRemoveCreditOrOtpDebitGuards() {
        val unsafe = RuleConfig.DEFAULT.copy(
            version = "v1.1",
            requiredKeywords = listOf("hello"),
            excludedKeywords = emptyList()
        )
        val errors = RuleValidator.validate(unsafe)
        assertTrue(errors.any { it.contains("approved credit") })
        assertTrue(errors.any { it.contains("OTP and debited") })
    }
}
