package com.k10.smsbridge.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.k10.smsbridge.MainActivity
import java.text.NumberFormat
import java.util.Locale

class TransactionNotifier(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "K10 Slice transactions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when money is received in the K10 Slice account"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun notifyReceived(amountMinor: Long, payerName: String, transactionId: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val amount = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(amountMinor / 100.0)
        val message = "$amount received on K10 Slice Account"
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Payment received${payerName.takeIf { it.isNotBlank() && it != "Unknown payer" }?.let { " from $it" }.orEmpty()}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(transactionId.hashCode(), notification) }
    }

    companion object {
        private const val CHANNEL_ID = "k10_slice_transactions"
    }
}
