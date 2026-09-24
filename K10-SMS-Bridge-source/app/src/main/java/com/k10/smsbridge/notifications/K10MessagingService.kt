package com.k10.smsbridge.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.k10.smsbridge.Graph
import com.k10.smsbridge.MainActivity
import com.k10.smsbridge.mobile.MobileApi
import com.k10.smsbridge.mobile.DeviceRegistrationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

class K10MessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "K10 Pay transactions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Native alerts for authorised K10 Slice transactions"
                enableVibration(true)
            })
            manager.createNotificationChannel(NotificationChannel(APPROVAL_CHANNEL, "K10 Pay account approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New staff account requests awaiting Developer approval"
                enableVibration(true)
            })
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] == "diagnostic_probe") {
            acknowledge(data["probeId"] ?: message.messageId ?: return, "diagnostic_probe")
            return
        }
        val approval = data["type"] == "account_approval"
        if (!approval && data["type"] != "slice_transaction") return
        val localPreferences = Graph.mobileSession.load()?.notificationPreferences
        val alertEnabled = if (approval) localPreferences?.approvalAlerts ?: true
        else data["alertEnabled"]?.toBooleanStrictOrNull() ?: localPreferences?.transactionAlerts ?: true
        val voiceEnabled = !approval && (data["voiceEnabled"]?.toBooleanStrictOrNull() ?: localPreferences?.voiceAnnouncements ?: true)
        if (!alertEnabled && !voiceEnabled) return

        // The backend outbox id is the acknowledgement key. Older payloads did
        // not include it, so retain transaction/request ids as rollout fallbacks.
        val deliveryId = data["deliveryId"] ?: data["requestId"] ?: data["transactionId"] ?: message.messageId ?: return
        val deliveries = getSharedPreferences("k10_pay_deliveries", Context.MODE_PRIVATE)
        if (deliveries.contains(deliveryId)) return
        if (voiceEnabled) data["amount"]?.toDoubleOrNull()?.let { Graph.announcer.announceReceived((it * 100).roundToLong()) }
        if (alertEnabled) showNotification(data, approval, deliveryId)
        val now = System.currentTimeMillis()
        val editor = deliveries.edit().putLong(deliveryId, now)
        if (deliveries.all.size > 500) {
            val cutoff = now - 30L * 24 * 60 * 60 * 1000
            deliveries.all.filterValues { (it as? Long ?: now) < cutoff }.keys.forEach(editor::remove)
        }
        editor.apply()
        Graph.transactionEvents.tryEmit(Unit)
        acknowledge(deliveryId, data["type"].orEmpty())
    }

    private fun acknowledge(deliveryId: String, type: String) {
        Graph.mobileSession.load()?.let { session ->
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "k10-unknown-device"
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { MobileApi.acknowledgeDelivery(session, deviceId, deliveryId, type) }
            }
        }
    }

    private fun showNotification(data: Map<String, String>, approval: Boolean, deliveryId: String) {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = data["body"] ?: if (approval) "A staff account is waiting for approval" else "A new payment was received"
        val notification = NotificationCompat.Builder(this, if (approval) APPROVAL_CHANNEL else CHANNEL)
            .setSmallIcon(com.k10.smsbridge.R.drawable.ic_k10_notification)
            .setContentTitle(data["title"] ?: if (approval) "New staff approval" else "K10 Pay transaction")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true).setContentIntent(intent).build()
        runCatching { NotificationManagerCompat.from(this).notify(deliveryId.hashCode(), notification) }
    }

    override fun onNewToken(token: String) {
        val session = Graph.mobileSession.load() ?: return
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "k10-unknown-device"
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { MobileApi.register(session, token, deviceId, session.developer) }
                .onSuccess { DeviceRegistrationStatus.success(this@K10MessagingService) }
                .onFailure { DeviceRegistrationStatus.failure(this@K10MessagingService, it.message ?: "Device registration failed") }
        }
    }

    companion object {
        private const val CHANNEL = "k10_native_transactions"
        private const val APPROVAL_CHANNEL = "k10_account_approvals"
    }
}
