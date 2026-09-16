package com.k10.smsbridge.rules

import android.content.Context
import org.json.JSONObject
import java.time.Instant

class RuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("matching_rules", Context.MODE_PRIVATE)

    fun current(): RuleConfig {
        val stored = prefs.getString(KEY_RULES, null) ?: return RuleConfig.DEFAULT
        return runCatching { RuleConfig.fromJson(JSONObject(stored)) }
            .getOrNull()
            ?.takeIf { RuleValidator.validate(it).isEmpty() }
            ?: RuleConfig.DEFAULT
    }

    fun lastSyncEpochMillis(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)
    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun applyRemote(jsonText: String): Result<RuleConfig> = runCatching {
        require(jsonText.toByteArray().size <= 32_768) { "Configuration exceeds 32 KiB" }
        val active = current()
        // Payer exclusions are managed by K10 Pay's dedicated server endpoint.
        // Matching-rule updates must never silently reintroduce an old hard-coded name.
        val candidate = RuleConfig.fromJson(JSONObject(jsonText)).copy(excludedPayerNames = active.excludedPayerNames)
        val errors = RuleValidator.validate(candidate)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        require(isNewer(candidate.version, active.version) || candidate == active) {
            "Configuration must have a newer version; an existing version cannot be replaced"
        }
        prefs.edit()
            .putString(KEY_RULES, candidate.toJson().toString())
            .putLong(KEY_LAST_SYNC, Instant.now().toEpochMilli())
            .remove(KEY_LAST_ERROR)
            .apply()
        candidate
    }.onFailure { prefs.edit().putString(KEY_LAST_ERROR, it.message).apply() }

    fun settings(): BridgeSettings = BridgeSettings(
        backendUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL).orEmpty().ifBlank { DEFAULT_BACKEND_URL },
        serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
    )

    fun saveSettings(value: BridgeSettings, accountLast4: String, senderIds: List<String>) {
        val url = value.backendUrl.trim().trimEnd('/')
        require(url.isEmpty() || url.startsWith("https://")) { "Backend URL must use HTTPS" }
        val updated = current().copy(
            accountLast4 = accountLast4.trim(),
            allowedSenderIds = senderIds.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        )
        val errors = RuleValidator.validate(updated)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        prefs.edit()
            .putString(KEY_BACKEND_URL, url)
            .putBoolean(KEY_SERVICE_ENABLED, value.serviceEnabled)
            .putString(KEY_RULES, updated.toJson().toString())
            .apply()
    }

    fun setExcludedPayers(names: List<String>) {
        val updated = current().copy(excludedPayerNames = names.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() })
        prefs.edit().putString(KEY_RULES, updated.toJson().toString()).apply()
    }

    private fun isNewer(candidate: String, active: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.').map { it.toInt() }.let { it + List(3 - it.size) { 0 } }
        val left = parts(candidate)
        val right = parts(active)
        return (0..2).firstNotNullOfOrNull { i -> (left[i] - right[i]).takeIf { it != 0 } }?.let { it > 0 } ?: false
    }

    companion object {
        private const val KEY_RULES = "last_known_good_rules"
        private const val KEY_LAST_SYNC = "last_rules_sync"
        private const val KEY_LAST_ERROR = "last_rules_error"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        const val DEFAULT_BACKEND_URL = "https://finances.k10classes.com"
    }
}

data class BridgeSettings(val backendUrl: String, val serviceEnabled: Boolean)
