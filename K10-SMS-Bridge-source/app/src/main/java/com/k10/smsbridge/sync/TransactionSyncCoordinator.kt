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
            Graph.transactionEvents.tryEmit(Unit)
            return@withLock TransactionSyncOutcome(latest.uniqueLocalId, "FAILED", message, null, false)
        }

        dao.markUploading(latest.uniqueLocalId)
        Graph.transactionEvents.tryEmit(Unit)
        val result = runCatching { BackendClient.upload(settings.backendUrl.trimEnd('/'), token, latest, fastAttempt) }
            .getOrElse { error ->
                val message = BackendClient.safeFailure(error)
                dao.updateUploadResult(latest.uniqueLocalId, "FAILED", message, null, null)
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
        val message = result.message ?: when (localStatus) {
            "SYNCED" -> "Stored on server"
            "DUPLICATE" -> "Already stored on server"
            "EXCLUDED" -> "Excluded payer"
            "REJECTED" -> "Server rejected this transaction"
            else -> "Upload failed${result.httpCode?.let { " (HTTP $it)" }.orEmpty()}"
        }
        dao.updateUploadResult(latest.uniqueLocalId, localStatus, message, result.serverTransactionId, result.httpCode)
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

    fun enqueueRecovery(context: Context, expedited: Boolean = true) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            SyncWorker.IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            builder.build()
        )

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
