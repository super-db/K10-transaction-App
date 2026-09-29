package com.k10.smsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.await
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.toEntity
import com.k10.smsbridge.diagnostics.DiagnosticEventLog
import com.k10.smsbridge.sync.TransactionSyncCoordinator
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pendingResult = goAsync()
        Graph.appScope.launch {
            try {
                DiagnosticEventLog.info("SMS_BROADCAST_RECEIVED", "sms", "Android delivered an incoming SMS broadcast")
                if (!Graph.rules.settings().serviceEnabled) {
                    DiagnosticEventLog.warning("SMS_SERVICE_DISABLED", "sms", "SMS broadcast received but transaction collection is disabled")
                    return@launch
                }
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    val received = parts.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                    val parsed = SmsParser.parse(sender, body, received, Graph.rules.current())
                    if (parsed.transaction == null && body.contains(Graph.rules.current().accountLast4)) {
                        DiagnosticEventLog.warning("SMS_CANDIDATE_REJECTED", "sms", parsed.reason)
                    }
                    parsed.transaction?.let {
                        val entity = it.toEntity()
                        val dao = Graph.database.transactions()
                        val inserted = dao.insert(entity) != -1L
                        val stored = if (inserted) entity else dao.findDuplicate(entity.duplicateKey)
                        if (inserted) {
                            DiagnosticEventLog.info("TX_LOCAL_SAVED", "local", "Eligible transaction saved locally from live SMS", entity.uniqueLocalId)
                            Graph.transactionEvents.tryEmit(Unit)
                        } else {
                            DiagnosticEventLog.info("TX_LOCAL_DUPLICATE", "local", "Eligible SMS was already stored locally", stored?.uniqueLocalId)
                        }

                        stored
                            ?.takeIf { transaction -> transaction.syncStatus !in setOf("SYNCED", "DUPLICATE", "EXCLUDED") }
                            ?.let { transaction ->
                                // First persist an independent WorkManager retry. Then use the SMS
                                // broadcast's short protected wake window for an immediate upload.
                                // This bypasses Samsung JobScheduler deferral while preserving a
                                // durable retry if the network call cannot finish.
                                runCatching {
                                    TransactionSyncCoordinator.enqueueRecovery(
                                        context,
                                        expedited = false,
                                        transactionLocalId = transaction.uniqueLocalId
                                    ).await()
                                }.onFailure { failure ->
                                    DiagnosticEventLog.error(
                                        "RECEIVER_BACKUP_ENQUEUE_FAILED",
                                        "sms_wake",
                                        failure.message ?: failure::class.java.simpleName,
                                        transaction.uniqueLocalId
                                    )
                                }
                                DiagnosticEventLog.info(
                                    "RECEIVER_UPLOAD_STARTED",
                                    "sms_wake",
                                    "Immediate server upload started inside the SMS wake window",
                                    transaction.uniqueLocalId
                                )
                                val outcome = TransactionSyncCoordinator.uploadOne(transaction, fastAttempt = true)
                                if (outcome.confirmedOnServer) {
                                    DiagnosticEventLog.info(
                                        "RECEIVER_UPLOAD_CONFIRMED",
                                        "sms_wake",
                                        "SMS wake-window upload confirmed by server",
                                        transaction.uniqueLocalId
                                    )
                                } else {
                                    DiagnosticEventLog.warning(
                                        "RECEIVER_UPLOAD_DEFERRED",
                                        "sms_wake",
                                        outcome.message ?: "Immediate upload did not finish; durable retry remains queued",
                                        transaction.uniqueLocalId
                                    )
                                    TransactionSyncCoordinator.enqueueRecovery(
                                        context,
                                        expedited = true,
                                        transactionLocalId = transaction.uniqueLocalId
                                    )
                                }
                            }
                    }
                }
            } catch (error: Throwable) {
                DiagnosticEventLog.error("SMS_RECEIVER_FAILED", "sms", error.message ?: error::class.java.simpleName)
            } finally {
                pendingResult.finish()
            }
        }
    }

}
