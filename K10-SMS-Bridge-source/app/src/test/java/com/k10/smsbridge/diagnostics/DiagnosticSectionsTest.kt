package com.k10.smsbridge.diagnostics
import org.junit.Assert.assertEquals
import org.junit.Test
class DiagnosticSectionsTest {
    @Test fun separatesOpeningParsingUploadsAndNotifications(){
        assertEquals("App opening",DiagnosticSections.category("startup"))
        assertEquals("SMS parsing",DiagnosticSections.category("sms_parse"))
        assertEquals("Uploads",DiagnosticSections.category("sms_wake"))
        assertEquals("Notifications",DiagnosticSections.category("firebase"))
    }
}
