package com.k10.smsbridge.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.TransactionDao
import com.k10.smsbridge.data.toEntity
import com.k10.smsbridge.rules.RuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recovers eligible SMS broadcasts that an OEM may delay or suppress in the background. */
object SmsInboxCatchUp {
    private const val PREFS = "automatic_sms_capture"
    private const val LAST_SCAN = "last_scan_epoch"
    private const val FIRST_SCAN_LOOKBACK_MILLIS = 24 * 60 * 60 * 1000L
    private const val OVERLAP_MILLIS = 60 * 1000L
    private const val ALERT_WINDOW_MILLIS = 10 * 60 * 1000L

    suspend fun importMissed(context: Context, rules: RuleConfig, dao: TransactionDao): Int = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext 0
        }

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getLong(LAST_SCAN, now - FIRST_SCAN_LOOKBACK_MILLIS)
        val from = (previous - OVERLAP_MILLIS).coerceAtLeast(0L)
        var added = 0

        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?",
                arrayOf(from.toString(), now.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                val senderIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext()) {
                    val parsed = SmsParser.parse(
                        cursor.getString(senderIndex).orEmpty(),
                        cursor.getString(bodyIndex).orEmpty(),
                        cursor.getLong(dateIndex),
                        rules
                    ).transaction ?: continue
                    val entity = parsed.toEntity()
                    if (dao.insert(entity) != -1L) {
                        added++
                        if (entity.smsReceivedTimestamp >= now - ALERT_WINDOW_MILLIS) {
                            val preferences = Graph.mobileSession.load()?.notificationPreferences
                            if (!parsed.payerExcluded && preferences?.voiceAnnouncements != false) {
                                Graph.announcer.announceReceived(entity.amountMinor)
                            }
                            if (!parsed.payerExcluded && preferences?.transactionAlerts != false) {
                                Graph.notifier.notifyReceived(entity.amountMinor, entity.payerName, entity.uniqueLocalId)
                            }
                        }
                    }
                }
            }
            prefs.edit().putLong(LAST_SCAN, now).apply()
        } catch (_: SecurityException) {
            return@withContext 0
        }
        if (added > 0) Graph.transactionEvents.tryEmit(Unit)
        added
    }
}
