package com.k10.smsbridge.mobile

import android.content.Context
import com.k10.smsbridge.security.SecureTokenStore
import org.json.JSONArray
import org.json.JSONObject

data class NotificationPreferences(
    val transactionAlerts: Boolean = true,
    val voiceAnnouncements: Boolean = true,
    val approvalAlerts: Boolean = false
)

data class MobileSession(
    val token: String,
    val userId: String,
    val loginId: String,
    val displayName: String,
    val role: String,
    val historyTier: String,
    val permissions: Set<String>,
    val passwordChangeRequired: Boolean = false,
    val notificationPreferences: NotificationPreferences = NotificationPreferences()
) {
    val developer: Boolean get() = role == "developer"
    val transactionSupervisor: Boolean get() = role == "transaction_supervisor"
    fun has(permission: String) = permissions.contains(permission)
}

class MobileSessionStore(context: Context) {
    private val secure = SecureTokenStore(context, "mobile")
    private val prefs = context.getSharedPreferences("k10_pay_mobile_profile", Context.MODE_PRIVATE)

    fun save(session: MobileSession) {
        secure.save(session.token)
        prefs.edit().putString("profile", JSONObject().apply {
            put("userId", session.userId)
            put("loginId", session.loginId)
            put("displayName", session.displayName)
            put("role", session.role)
            put("historyTier", session.historyTier)
            put("permissions", JSONArray(session.permissions.toList()))
            put("passwordChangeRequired", session.passwordChangeRequired)
            put("notificationPreferences", JSONObject().apply {
                put("transactionAlerts", session.notificationPreferences.transactionAlerts)
                put("voiceAnnouncements", session.notificationPreferences.voiceAnnouncements)
                put("approvalAlerts", session.notificationPreferences.approvalAlerts)
            })
        }.toString()).apply()
    }

    fun load(): MobileSession? = runCatching {
        val token = secure.load().takeIf { it.isNotBlank() } ?: return null
        val json = JSONObject(prefs.getString("profile", null) ?: return null)
        val values = json.optJSONArray("permissions") ?: JSONArray()
        val notification = json.optJSONObject("notificationPreferences") ?: JSONObject()
        MobileSession(
            token = token,
            userId = json.optString("userId"),
            loginId = json.getString("loginId"),
            displayName = json.getString("displayName"),
            role = json.getString("role"),
            historyTier = json.optString("historyTier", if (json.optString("role") == "developer") "lifetime" else "today"),
            permissions = (0 until values.length()).map { values.getString(it) }.toSet(),
            passwordChangeRequired = json.optBoolean("passwordChangeRequired"),
            notificationPreferences = NotificationPreferences(
                transactionAlerts = notification.optBoolean("transactionAlerts", true),
                voiceAnnouncements = notification.optBoolean("voiceAnnouncements", true),
                approvalAlerts = notification.optBoolean("approvalAlerts", json.optString("role") == "developer")
            )
        )
    }.getOrNull()

    fun clear() {
        secure.save("")
        prefs.edit().clear().apply()
    }
}
