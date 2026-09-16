package com.k10.smsbridge.notifications
import android.app.*
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.*
import com.google.firebase.messaging.*
import com.k10.smsbridge.*
import com.k10.smsbridge.mobile.MobileApi
import kotlinx.coroutines.*
class K10MessagingService:FirebaseMessagingService(){
 override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26){val manager=getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"K10 Pay transactions",NotificationManager.IMPORTANCE_HIGH).apply{description="Native alerts for authorised K10 Slice transactions";enableVibration(true)});manager.createNotificationChannel(NotificationChannel(APPROVAL_CHANNEL,"K10 Pay account approvals",NotificationManager.IMPORTANCE_HIGH).apply{description="New staff account requests awaiting Developer approval";enableVibration(true)})}}
 override fun onMessageReceived(message:RemoteMessage){val data=message.data;val approval=data["type"]=="account_approval";if(!approval&&data["type"]!="slice_transaction")return;val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);val body=data["body"]?:if(approval)"A staff account is waiting for approval" else "A new payment was received";val notification=NotificationCompat.Builder(this,if(approval)APPROVAL_CHANNEL else CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(data["title"]?:if(approval)"New staff approval" else "K10 Pay transaction").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS).setAutoCancel(true).setContentIntent(intent).build();runCatching{NotificationManagerCompat.from(this).notify((data["requestId"]?:data["transactionId"]?:message.messageId?:"k10").hashCode(),notification)}}
 override fun onNewToken(token:String){val session=Graph.mobileSession.load()?:return;val deviceId=Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID)?:"k10-unknown-device";CoroutineScope(Dispatchers.IO).launch{runCatching{MobileApi.register(session,token,deviceId,session.developer)}}}
 companion object{private const val CHANNEL="k10_native_transactions";private const val APPROVAL_CHANNEL="k10_account_approvals"}
}
