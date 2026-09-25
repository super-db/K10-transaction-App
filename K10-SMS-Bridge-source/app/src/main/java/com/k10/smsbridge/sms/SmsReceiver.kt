package com.k10.smsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.toEntity
import com.k10.smsbridge.diagnostics.DiagnosticEventLog
import com.k10.smsbridge.sync.TransactionSyncCoordinator
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Graph.rules.settings().serviceEnabled) return
        val pendingResult = goAsync()
        Graph.appScope.launch {
            try {
                DiagnosticEventLog.info("SMS_BROADCAST_RECEIVED", "sms", "Android delivered an incoming SMS broadcast")
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    val received = parts.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                    SmsParser.parse(sender, body, received, Graph.rules.current()).transaction?.let {
                        val entity = it.toEntity()
                        if (Graph.database.transactions().insert(entity) != -1L) {
                            DiagnosticEventLog.info("TX_LOCAL_SAVED", "local", "Eligible transaction saved locally from live SMS", entity.uniqueLocalId)
                            Graph.transactionEvents.tryEmit(Unit)
                            // Queue durable work before leaving the broadcast. Payment alerts are
                            // deliberately server-confirmed and are delivered later through FCM.
                            TransactionSyncCoordinator.enqueueRecovery(context, expedited = true, transactionLocalId = entity.uniqueLocalId)
                        } else {
                            DiagnosticEventLog.info("TX_LOCAL_DUPLICATE", "local", "Eligible SMS was already stored locally", entity.uniqueLocalId)
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
