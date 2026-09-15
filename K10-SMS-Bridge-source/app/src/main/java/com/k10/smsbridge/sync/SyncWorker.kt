package com.k10.smsbridge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.k10.smsbridge.Graph
import com.k10.smsbridge.sms.SmsInboxCatchUp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Graph.rules.settings()
        if (!settings.serviceEnabled) return Result.success()

        SmsInboxCatchUp.importMissed(applicationContext, Graph.rules.current(), Graph.database.transactions())
        if (settings.backendUrl.isBlank()) return Result.success()
        val token = Graph.tokenStore.load()
        if (token.isBlank()) return Result.success()

        runCatching { BackendClient.fetchRules(settings.backendUrl, token) }
            .onSuccess { Graph.rules.applyRemote(it) }

        var retryNeeded = false
        Graph.database.transactions().pending().forEach { item ->
            runCatching { BackendClient.upload(settings.backendUrl, token, item) }
                .onSuccess { response ->
                    val localStatus = when (response.status.lowercase()) {
                        "success" -> "SYNCED"
                        "duplicate" -> "DUPLICATE"
                        "rejected", "validation_error", "excluded" -> "REJECTED"
                        "authentication_error" -> "FAILED"
                        else -> "FAILED"
                    }
                    Graph.database.transactions().updateStatus(item.uniqueLocalId, localStatus, response.message)
                    if (localStatus == "FAILED") retryNeeded = true
                }
                .onFailure {
                    Graph.database.transactions().updateStatus(item.uniqueLocalId, "FAILED", "Temporary connection error")
                    retryNeeded = true
                }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "k10-periodic-rules-and-sync"
        const val IMMEDIATE_NAME = "k10-immediate-transaction-sync"
        const val STARTUP_NAME = "k10-startup-catch-up-and-sync"
    }
}
