package com.k10.smsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.toEntity
import com.k10.smsbridge.sync.SyncWorker
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Graph.rules.settings().serviceEnabled) return
        val pendingResult = goAsync()
        Graph.appScope.launch {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    val received = parts.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                    SmsParser.parse(sender, body, received, Graph.rules.current()).transaction?.let {
                        val entity = it.toEntity()
                        if (Graph.database.transactions().insert(entity) != -1L) {
                            val preferences = Graph.mobileSession.load()?.notificationPreferences
                            if (!it.payerExcluded && preferences?.voiceAnnouncements != false) {
                                Graph.announcer.announceReceived(entity.amountMinor)
                            }
                            if (!it.payerExcluded && preferences?.transactionAlerts != false) {
                                Graph.notifier.notifyReceived(entity.amountMinor, entity.payerName, entity.uniqueLocalId)
                            }
                            enqueueSync(context)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun enqueueSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SyncWorker.IMMEDIATE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
