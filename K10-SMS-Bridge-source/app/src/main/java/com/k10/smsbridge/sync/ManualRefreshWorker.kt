package com.k10.smsbridge.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import com.k10.smsbridge.Graph
import com.k10.smsbridge.MainActivity
import com.k10.smsbridge.sms.SmsInboxCatchUp
import com.k10.smsbridge.diagnostics.DiagnosticEventLog
import kotlinx.coroutines.CancellationException

/** Manual reconciliation only. Live reception and SyncWorker retry paths are deliberately separate. */
class ManualRefreshWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun getForegroundInfo():ForegroundInfo=foreground("Preparing selected-range refresh")
    private fun foreground(message:String):ForegroundInfo{
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("manual_refresh","Transaction refresh progress",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(applicationContext,"manual_refresh")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("K10 Pay · Transaction refresh")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(7973,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(7973,notification)
    }
    override suspend fun doWork():Result{
        val range=ManualRefreshRange(inputData.getString("range")?:"today",inputData.getLong("from",0),inputData.getLong("until",System.currentTimeMillis()))
        var scanned=0;var recovered=0;var checked=0;var total=0;var present=0;var uploaded=0;var failed=0
        fun data(message:String)=workDataOf("message" to message,"scanned" to scanned,"recovered" to recovered,"checked" to checked,"total" to total,"present" to present,"uploaded" to uploaded,"failed" to failed,"range" to range.key)
        suspend fun progress(message:String){
            setProgress(data(message))
            runCatching{setForeground(foreground(message))}.onFailure{if(it is CancellationException)throw it}
        }
        try{
            check(Graph.mobileSession.load()?.developer==true){"REFRESH_DEVELOPER_REQUIRED"}
            check(Graph.rules.settings().serviceEnabled){"REFRESH_SERVICE_DISABLED"}
            progress("Preparing "+range.key+" refresh")
            DiagnosticEventLog.info("MANUAL_REFRESH_STARTED","manual_refresh","Range "+range.key+" · "+id.toString())
            val settings=Graph.rules.settings();val token=Graph.tokenStore.load()
            check(settings.backendUrl.isNotBlank()&&token.isNotBlank()){"REFRESH_API_NOT_CONFIGURED"}
            val dao=Graph.database.transactions()
            if(range.scansInbox){
                check(ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED){"REFRESH_SMS_PERMISSION_REQUIRED"}
                progress("Scanning selected-range SMS")
                recovered=SmsInboxCatchUp.importMissed(applicationContext,Graph.rules.current(),dao,rangeFrom=range.from,rangeUntil=range.until){count->
                    scanned=count;setProgressAsync(data("Scanning selected-range SMS"))
                }
            }
            val rows=dao.allForServerCheck().filter{range.contains(it.smsReceivedTimestamp)}
            total=rows.size
            progress("Checking server presence")
            for(chunk in rows.chunked(40)){
                val results=BackendClient.presence(settings.backendUrl.trimEnd('/'),token,chunk).associateBy{it.localId}
                for(item in chunk){
                    val result=results.getValue(item.uniqueLocalId)
                    if(result.present){
                        present++
                        dao.updateUploadResult(item.uniqueLocalId,if(result.excluded)"EXCLUDED" else "DUPLICATE","Verified on server",result.serverId,200)
                    }else{
                        // Demote only after the server explicitly reports absence, never during a check.
                        val latest=dao.findByLocalId(item.uniqueLocalId)?:item
                        // An independent live upload may have completed since this batch request began.
                        if(latest.statusUpdatedAt>item.statusUpdatedAt&&latest.syncStatus in setOf("SYNCED","DUPLICATE","EXCLUDED")){
                            present++
                        }else{
                            dao.updateStatus(item.uniqueLocalId,"PENDING","Server reports missing; upload queued")
                            val outcome=TransactionSyncCoordinator.uploadOne(item)
                            if(outcome.confirmedOnServer)uploaded++ else{
                                failed++
                                TransactionSyncCoordinator.enqueueRecovery(applicationContext,expedited=false,transactionLocalId=item.uniqueLocalId)
                            }
                        }
                    }
                    checked++
                    setProgress(data("Checking and syncing selected range"))
                }
                Graph.transactionEvents.tryEmit(Unit)
                progress("Server check: "+checked+" / "+total)
            }
            val summary="SMS scanned: "+scanned+" · Recovered: "+recovered+"\nServer checked: "+checked+" · Already present: "+present+" · Uploaded: "+uploaded+" · Failed: "+failed
            DiagnosticEventLog.info("MANUAL_REFRESH_COMPLETED","manual_refresh",summary+" · "+id.toString())
            return if(failed==0)Result.success(data(summary)) else Result.failure(data(summary+"\nFailed uploads remain queued for retry."))
        }catch(error:Exception){
            if(error is CancellationException)throw error
            val message=error.message?:"REFRESH_UNEXPECTED_FAILURE"
            DiagnosticEventLog.error("MANUAL_REFRESH_FAILED","manual_refresh",message+" · "+id.toString())
            return Result.failure(data("Refresh incomplete: "+message+"\nExisting confirmations were retained. Retry when connected."))
        }finally{Graph.transactionEvents.tryEmit(Unit)}
    }
    companion object{const val NAME="k10-manual-range-refresh"}
}
