package com.k10.smsbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmedSyncResultTest {
    @Test
    fun rejectedRecordDoesNotPretendBackendIsDisconnected() {
        val result = ConfirmedSyncResult(
            successful = true,
            locallyAdded = 0,
            checked = 17,
            synced = 1,
            alreadyOnServer = 15,
            excluded = 0,
            rejected = 1,
            failed = 0,
            error = null
        )

        assertEquals("16 confirmed on server · 1 rejected. Check Scan existing SMS.", result.userMessage())
    }

    @Test
    fun authenticationFailureIsReportedDirectly() {
        val result = ConfirmedSyncResult(false, 0, 1, 0, 0, 0, 0, 1, "Authentication failed")
        assertEquals("Authentication failed", result.userMessage())
    }
}
