package com.k10.smsbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.TransactionEntity
import com.k10.smsbridge.diagnostics.DiagnosticEventLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class TransactionSyncOutcome(
    val localId: String,
    val status: String,
    val message: String?,
    val httpCode: Int?,
    val confirmedOnServer: Boolean
)

/** One upload path shared by SMS reception, manual recovery and WorkManager retries. */
object TransactionSyncCoordinator {
    private val mutex = Mutex()

    suspend fun uploadOne(item: TransactionEntity, fastAttempt: Boolean = false): TransactionSyncOutcome = mutex.withLock {
        val dao = Graph.database.transactions()
        val latest = dao.findByLocalId(item.uniqueLocalId) ?: item
        if (latest.syncStatus in setOf("SYNCED", "DUPLICATE", "EXCLUDED")) {
            return@withLock TransactionSyncOutcome(latest.uniqueLocalId, latest.syncStatus, latest.serverMessage, latest.lastHttpCode, true)
        }

        val settings = Graph.rules.settings()
        val token = Graph.tokenStore.load()
        if (settings.backendUrl.isBlank() || token.isBlank()) {
            val message = if (settings.backendUrl.isBlank()) "Backend URL is not configured" else "API token is not configured"
            dao.updateUploadResult(latest.uniqueLocalId, "FAILED", message, null, null)
            DiagnosticEventLog.error("UPLOAD_CONFIGURATION_MISSING", "upload", message, latest.uniqueLocalId)
            Graph.transactionEvents.tryEmit(Unit)
            return@withLock TransactionSyncOutcome(latest.uniqueLocalId, "FAILED", message, null, false)
        }

        dao.markUploading(latest.uniqueLocalId)
        DiagnosticEventLog.info("UPLOAD_STARTED", "upload", "Transaction upload started", latest.uniqueLocalId)
        Graph.transactionEvents.tryEmit(Unit)
        val result = runCatching { BackendClient.upload(settings.backendUrl.trimEnd('/'), token, latest, fastAttempt) }
            .getOrElse { error ->
                val message = BackendClient.safeFailure(error)
                dao.updateUploadResult(latest.uniqueLocalId, "FAILED", message, null, null)
                DiagnosticEventLog.error("UPLOAD_CONNECTION_FAILED", "upload", message, latest.uniqueLocalId)
                Graph.transactionEvents.tryEmit(Unit)
                return@withLock TransactionSyncOutcome(latest.uniqueLocalId, "FAILED", message, null, false)
            }

        val localStatus = when (result.status.lowercase()) {
            "success" -> "SYNCED"
            "duplicate" -> "DUPLICATE"
            "excluded" -> "EXCLUDED"
            "rejected", "validation_error" -> "REJECTED"
            else -> "FAILED"
        }
        val baseMessage = result.message ?: when (localStatus) {
            "SYNCED" -> "Stored on server"
            "DUPLICATE" -> "Already stored on server"
            "EXCLUDED" -> "Excluded payer"
            "REJECTED" -> "Server rejected this transaction"
            else -> "Upload failed${result.httpCode?.let { " (HTTP $it)" }.orEmpty()}"
        }
        val diagnosticSuffix = listOfNotNull(
            result.errorCode?.let { "Code $it" },
            result.traceId?.let { "Ref $it" }
        ).joinToString(" · ")
        val message = if (diagnosticSuffix.isBlank()) baseMessage else "$baseMessage · $diagnosticSuffix"
        dao.updateUploadResult(latest.uniqueLocalId, localStatus, message, result.serverTransactionId, result.httpCode)
        when (localStatus) {
            "SYNCED", "DUPLICATE", "EXCLUDED" -> DiagnosticEventLog.info(
                if (localStatus == "SYNCED") "SERVER_CONFIRMED" else "SERVER_$localStatus",
                "server",
                "$baseMessage${result.serverConfirmedAt?.let { " · confirmed $it" }.orEmpty()}",
                latest.uniqueLocalId,
                result.traceId ?: result.serverTransactionId
            )
            else -> DiagnosticEventLog.error(
                result.errorCode ?: "UPLOAD_HTTP_${result.httpCode ?: 0}",
                "upload",
                message,
                latest.uniqueLocalId,
                result.traceId
            )
        }
        Graph.transactionEvents.tryEmit(Unit)
        TransactionSyncOutcome(
            latest.uniqueLocalId,
            localStatus,
            message,
            result.httpCode,
            localStatus in setOf("SYNCED", "DUPLICATE", "EXCLUDED")
        )
    }

    suspend fun uploadPending(): List<TransactionSyncOutcome> {
        val pending = Graph.database.transactions().pending()
        return pending.map { uploadOne(it) }
    }

    fun enqueueRecovery(context: Context, expedited: Boolean = true, transactionLocalId: String? = null) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            SyncWorker.IMMEDIATE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            builder.build()
        )
        Graph.appScope.launch {
            DiagnosticEventLog.info("UPLOAD_WORK_ENQUEUED", "work", if (expedited) "Expedited upload work queued" else "Upload work queued", transactionLocalId)
        }

        // WorkManager's built-in retry backoff starts at ten minutes. Keep a
        // separate short recovery check so a temporary network/server failure
        // can still reach staff phones inside the five-minute product target.
        val recovery = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.SHORT_RECOVERY_NAME,
            ExistingWorkPolicy.KEEP,
            recovery
        )
    }
}
