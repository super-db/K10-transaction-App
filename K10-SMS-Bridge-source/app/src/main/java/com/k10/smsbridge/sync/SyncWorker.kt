package com.k10.smsbridge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.k10.smsbridge.Graph
import com.k10.smsbridge.sms.SmsInboxCatchUp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Graph.rules.settings()
        if (!settings.serviceEnabled) return Result.success()

        val interactive = inputData.getBoolean(KEY_INTERACTIVE, false)
        val fullHistory = inputData.getBoolean(KEY_FULL_HISTORY, false)
        val recheckAll = inputData.getBoolean(KEY_RECHECK_ALL, false)
        val dao = Graph.database.transactions()
        val rules = Graph.rules.current()
        val scanned = SmsInboxCatchUp.importMissed(applicationContext, rules, dao, fullHistory)
        if (settings.backendUrl.isBlank()) {
            return configuredFailure(interactive, scanned, "Backend URL is not configured")
        }
        val token = Graph.tokenStore.load()
        if (token.isBlank()) {
            return configuredFailure(interactive, scanned, "API token is not configured")
        }

        runCatching { BackendClient.fetchRules(settings.backendUrl, token) }
            .onSuccess { Graph.rules.applyRemote(it) }

        if (recheckAll) {
            val excluded = rules.excludedPayerNames.map { normalizePayer(it) }.toSet()
            dao.allForServerCheck().forEach { item ->
                if (normalizePayer(item.payerName) !in excluded) {
                    dao.updateStatus(item.uniqueLocalId, "PENDING", "Checking server presence")
                }
            }
        }

        var temporaryFailures = 0
        var authenticationFailures = 0
        var unexpectedFailures = 0
        var rejected = 0
        var cloudStateChanged = false
        var synced = 0
        var duplicates = 0
        var excluded = 0
        val pending = dao.pending()
        pending.forEach { item ->
            runCatching { BackendClient.upload(settings.backendUrl, token, item) }
                .onSuccess { response ->
                    val localStatus = when (response.status.lowercase()) {
                        "success" -> "SYNCED"
                        "duplicate" -> "DUPLICATE"
                        "excluded" -> "EXCLUDED"
                        "rejected", "validation_error" -> "REJECTED"
                        "authentication_error" -> "FAILED"
                        else -> "FAILED"
                    }
                    dao.updateStatus(item.uniqueLocalId, localStatus, response.message)
                    when (localStatus) {
                        "SYNCED" -> synced++
                        "DUPLICATE" -> duplicates++
                        "EXCLUDED" -> excluded++
                        "REJECTED" -> rejected++
                        "FAILED" -> if (response.status.equals("authentication_error", true)) authenticationFailures++ else unexpectedFailures++
                    }
                    if (localStatus != "FAILED") cloudStateChanged = true
                }
                .onFailure {
                    dao.updateStatus(item.uniqueLocalId, "FAILED", "Temporary connection error")
                    temporaryFailures++
                }
        }
        if (cloudStateChanged) Graph.transactionEvents.tryEmit(Unit)
        val output = Data.Builder()
            .putInt(OUTPUT_SCANNED, scanned)
            .putInt(OUTPUT_ATTEMPTED, pending.size)
            .putInt(OUTPUT_SYNCED, synced)
            .putInt(OUTPUT_DUPLICATES, duplicates)
            .putInt(OUTPUT_EXCLUDED, excluded)
            .putInt(OUTPUT_REJECTED, rejected)
            .putInt(OUTPUT_FAILED, temporaryFailures + authenticationFailures + unexpectedFailures)
            .apply {
                when {
                    authenticationFailures > 0 -> putString(OUTPUT_ERROR, "Authentication failed. Re-save the SMS Bridge API token.")
                    temporaryFailures > 0 -> putString(OUTPUT_ERROR, "The server could not be reached. Check the internet connection and retry.")
                    unexpectedFailures > 0 -> putString(OUTPUT_ERROR, "The server returned an unexpected sync response.")
                }
            }
            .build()
        return when {
            temporaryFailures + authenticationFailures + unexpectedFailures == 0 -> Result.success(output)
            interactive -> Result.failure(output)
            temporaryFailures > 0 -> Result.retry()
            else -> Result.failure(output)
        }
    }

    private fun configuredFailure(interactive: Boolean, scanned: Int, message: String): Result {
        val output = Data.Builder().putInt(OUTPUT_SCANNED, scanned).putString(OUTPUT_ERROR, message).build()
        return if (interactive) Result.failure(output) else Result.success(output)
    }

    private fun normalizePayer(value: String) = value.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    companion object {
        const val PERIODIC_NAME = "k10-periodic-rules-and-sync"
        const val IMMEDIATE_NAME = "k10-immediate-transaction-sync"
        const val STARTUP_NAME = "k10-startup-catch-up-and-sync"
        const val USER_SYNC_NAME = "k10-user-confirmed-sync"
        const val KEY_INTERACTIVE = "interactive"
        const val KEY_FULL_HISTORY = "full_history"
        const val KEY_RECHECK_ALL = "recheck_all"
        const val OUTPUT_SCANNED = "scanned"
        const val OUTPUT_ATTEMPTED = "attempted"
        const val OUTPUT_SYNCED = "synced"
        const val OUTPUT_DUPLICATES = "duplicates"
        const val OUTPUT_EXCLUDED = "excluded"
        const val OUTPUT_REJECTED = "rejected"
        const val OUTPUT_FAILED = "failed"
        const val OUTPUT_ERROR = "error"
    }
}
