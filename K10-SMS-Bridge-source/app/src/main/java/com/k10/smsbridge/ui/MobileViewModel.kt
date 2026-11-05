package com.k10.smsbridge.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.TransactionEntity
import com.k10.smsbridge.mobile.*
import com.k10.smsbridge.sync.ManualRefreshWorker
import com.k10.smsbridge.sync.ManualRefreshRange
import androidx.work.*
import android.os.SystemClock
import com.k10.smsbridge.diagnostics.OpeningDiagnostics
import com.k10.smsbridge.diagnostics.DiagnosticEventLog
import com.k10.smsbridge.sms.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class MobileViewModel(app:Application):AndroidViewModel(app){
    var session:MobileSession? by mutableStateOf(Graph.mobileSession.load());private set
    var transactions:List<MobileTransaction> by mutableStateOf(emptyList());private set
    var todayAmount by mutableDoubleStateOf(0.0);private set
    var todayCount by mutableIntStateOf(0);private set
    var range by mutableStateOf("today");private set
    var busy by mutableStateOf(false);private set
    var loadingTransactions by mutableStateOf(false);private set
    var reconcilingTransactions by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var messageIsError by mutableStateOf(false);private set
    var exclusions by mutableStateOf("");private set
    var excludedPayers:List<ExcludedPayer> by mutableStateOf(emptyList());private set
    var excludedTransactions:List<MobileTransaction> by mutableStateOf(emptyList());private set
    var students:List<StudentOption> by mutableStateOf(emptyList());private set
    var requests:List<AccountRequest> by mutableStateOf(emptyList());private set
    var managedAccounts:List<ManagedAccount> by mutableStateOf(emptyList());private set
    var availableUpdate:AppUpdateInfo? by mutableStateOf(null);private set
    val pendingApprovals:Int get()=requests.size
    private val deviceId=Settings.Secure.getString(app.contentResolver,Settings.Secure.ANDROID_ID)?:"k10-unknown-device"
    var reconciliationProgress by mutableStateOf("");private set
    var showRefreshProgress by mutableStateOf(false);private set
    var refreshFailed by mutableStateOf(false);private set
    private var manualRefreshId:java.util.UUID?=null
    fun hideRefreshProgress(){showRefreshProgress=false}
    private var historyJob:Job?=null
    private var activeHistoryRange:String?=null
    private var refreshAfterHistory=false
    private var loadSequence=0L
    private var cloudTransactions:List<MobileTransaction> = emptyList()
    private var localTransactions:List<TransactionEntity> = emptyList()
    private var localObserver:Job? = null

    init{
        observeManualRefresh()
        viewModelScope.launch(Dispatchers.IO){
            val started=SystemClock.elapsedRealtime()
            var repaired=0
            runCatching {
                val dao=Graph.database.transactions()
                dao.snapshot().forEach{row->
                    if(row.payerName.equals("Unknown payer",true)||row.paymentMethod.equals("UNKNOWN",true)){
                        val recovered=SmsParser.recoverImpsMetadata(row.rawEligibleSms,row.accountLast4)?:return@forEach
                        val name=if(row.payerName.equals("Unknown payer",true))recovered.payerName else row.payerName
                        val method=if(row.paymentMethod.equals("UNKNOWN",true))recovered.paymentMethod else row.paymentMethod
                        dao.repairMetadata(row.uniqueLocalId,name,method,row.payerName,row.paymentMethod);repaired++
                    }
                }
            }.onFailure{DiagnosticEventLog.error("LOCAL_METADATA_REPAIR_FAILED","sms_parse",it::class.java.simpleName)}
            OpeningDiagnostics.stage("Local metadata repair",SystemClock.elapsedRealtime()-started,"$repaired corrected; IDs and sync state preserved")
            if(repaired>0)DiagnosticEventLog.info("IMPS_METADATA_REPAIRED","sms_parse","$repaired local records corrected without re-upload")
        }
        session?.let{if(!it.passwordChangeRequired){startLocalObserver(it);load("today",false);refreshPreferences(false);registerDevice(it);if(it.developer){refreshApprovals(false);loadExclusions(false);loadStudents(false)}}}
        viewModelScope.launch{Graph.transactionEvents.collect{if(session?.passwordChangeRequired==false){rebuildTransactions();load(range,false,true)}}}
        checkForUpdate(false)
    }

    fun clearMessage(){message="";messageIsError=false}
    private fun success(value:String){message=value;messageIsError=false}
    private fun failure(error:Throwable,fallback:String){message=error.message?:fallback;messageIsError=true}

    fun login(id:String,password:String,developer:Boolean){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.login(id.trim(),password,developer)}.onSuccess{Graph.mobileSession.save(it);session=it;busy=false;if(!it.passwordChangeRequired){startLocalObserver(it);registerDevice(it);load("today",false);refreshPreferences(false);if(it.developer){refreshApprovals(false);loadExclusions(false);loadStudents(false)}}}.onFailure{failure(it,"Sign in failed")};busy=false}}
    fun signup(name:String,phone:String,email:String,password:String){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.signup(name.trim(),phone.trim(),email.trim(),password)}.onSuccess(::success).onFailure{failure(it,"Account request failed")};busy=false}}
    fun requestReset(email:String,onSent:()->Unit={}){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.requestReset(email.trim())}.onSuccess{success(it);onSent()}.onFailure{failure(it,"Reset email could not be sent")};busy=false}}
    fun completeReset(email:String,code:String,password:String,onDone:()->Unit){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.completeReset(email.trim(),code.trim(),password)}.onSuccess{success(it);onDone()}.onFailure{failure(it,"Password reset failed")};busy=false}}
    fun changePassword(current:String,next:String,onDone:()->Unit){val active=session?:return;if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.changePassword(active,current,next)}.onSuccess{logout(false);success(it);onDone()}.onFailure{failure(it,"Password change failed")};busy=false}}
    fun load(value:String,showMessage:Boolean=true,ensureFresh:Boolean=false){
        val current=session?:return;if(current.passwordChangeRequired)return
        range=value;rebuildTransactions()
        if(historyJob?.isActive==true&&activeHistoryRange==value){refreshAfterHistory=refreshAfterHistory||ensureFresh;return}
        val requestId=++loadSequence;activeHistoryRange=value
        historyJob=viewModelScope.launch{
            val started=SystemClock.elapsedRealtime();loadingTransactions=true;if(showMessage)clearMessage()
            DiagnosticEventLog.info("HISTORY_REQUEST_STARTED","startup","Server history requested · $value · request $requestId")
            var historySucceeded=false
            runCatching{MobileApi.transactions(current,value)}
                .onSuccess{page->historySucceeded=true;if(requestId==loadSequence){cloudTransactions=page.transactions;rebuildTransactions(page.todayAmount,page.todayCount)}}
                .onFailure{if(requestId==loadSequence){rebuildTransactions();failure(it,"Could not load server transactions. Local transactions are still shown.")};DiagnosticEventLog.error("HISTORY_REQUEST_FAILED","startup","History request failed · ${it::class.java.simpleName}")}
            val elapsed=SystemClock.elapsedRealtime()-started
            OpeningDiagnostics.stage("Server history",elapsed,if(historySucceeded)"completed" else "request failed; local history retained")
            DiagnosticEventLog.info("HISTORY_REQUEST_COMPLETED","startup","Server history took $elapsed ms · request $requestId")
            if(requestId==loadSequence){loadingTransactions=false;activeHistoryRange=null;historyJob=null
                if(refreshAfterHistory){refreshAfterHistory=false;load(range,false)}
            }
        }
    }

    fun refresh(){load(range,false)}
    private fun observeManualRefresh(){
        viewModelScope.launch{
            WorkManager.getInstance(getApplication()).getWorkInfosForUniqueWorkFlow(ManualRefreshWorker.NAME).collect{infos->
                if(session?.developer!=true)return@collect
                val active=infos.firstOrNull{!it.state.isFinished}
                val info=active?:infos.firstOrNull{it.id==manualRefreshId}?:return@collect
                if(active!=null){
                    if(manualRefreshId!=active.id){manualRefreshId=active.id;showRefreshProgress=true}
                    reconcilingTransactions=true;refreshFailed=false
                    val p=info.progress
                    reconciliationProgress=p.getString("message")?:if(info.state==WorkInfo.State.ENQUEUED)"Waiting for network or Android scheduling…" else "Preparing refresh…"
                    reconciliationProgress+="\nRange: "+p.getString("range").orEmpty()+" · SMS scanned: "+p.getInt("scanned",0)+" · Recovered: "+p.getInt("recovered",0)+"\nServer: "+p.getInt("checked",0)+" / "+p.getInt("total",0)+" · Present: "+p.getInt("present",0)+" · Uploaded: "+p.getInt("uploaded",0)+" · Failed: "+p.getInt("failed",0)
                }else if(reconcilingTransactions){
                    reconcilingTransactions=false
                    refreshFailed=info.state!=WorkInfo.State.SUCCEEDED
                    reconciliationProgress=info.outputData.getString("message")?:"Refresh stopped before completion. See diagnostics."
                    load(range,false,true)
                    if(!refreshFailed){
                        val completedId=info.id
                        viewModelScope.launch{delay(2_000);if(!reconcilingTransactions&&manualRefreshId==completedId)showRefreshProgress=false}
                    }
                }
            }
        }
    }

    fun reconcileAll(){
        val current=session?:return
        if(reconcilingTransactions){showRefreshProgress=true;return}
        if(!current.developer){
            // Staff have no inbox/bridge duties; retain their role-limited server history path.
            load(range,true,true);return
        }
        val selected=ManualRefreshRange.create(range)
        reconcilingTransactions=true;refreshFailed=false;showRefreshProgress=true
        reconciliationProgress="Preparing "+selected.key+" refresh…";clearMessage()
        viewModelScope.launch{
            try{
                val manager=WorkManager.getInstance(getApplication())
                val existing=withContext(Dispatchers.IO){manager.getWorkInfosForUniqueWork(ManualRefreshWorker.NAME).get().firstOrNull{!it.state.isFinished}}
                if(existing!=null){manualRefreshId=existing.id;return@launch}
                val request=OneTimeWorkRequestBuilder<ManualRefreshWorker>()
                    .setInputData(workDataOf("range" to selected.key,"from" to selected.from,"until" to selected.until))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                manualRefreshId=request.id
                withContext(Dispatchers.IO){manager.enqueueUniqueWork(ManualRefreshWorker.NAME,ExistingWorkPolicy.KEEP,request).result.get()}
            }catch(error:Exception){
                reconcilingTransactions=false;refreshFailed=true
                reconciliationProgress="Refresh could not be queued: "+(error.message?:"REFRESH_ENQUEUE_FAILED")
            }
        }
    }
    fun refreshApprovals(showMessage:Boolean=true){val current=session?.takeIf{it.developer}?:return;viewModelScope.launch{if(showMessage)busy=true;runCatching{MobileApi.approvalInbox(current)}.onSuccess{requests=it.requests;managedAccounts=it.accounts;if(showMessage)success("Approval inbox refreshed")}.onFailure{failure(it,"Could not load account requests")};busy=false}}
    fun review(requestId:String,approve:Boolean){val current=session?:return;if(busy)return;viewModelScope.launch{busy=true;runCatching{MobileApi.review(current,requestId,approve)}.onSuccess{success(it);busy=false;refreshApprovals(false)}.onFailure{failure(it,"Could not review request")};busy=false}}
    fun adminReset(userId:String,password:String){val current=session?:return;if(busy)return;viewModelScope.launch{busy=true;runCatching{MobileApi.adminReset(current,userId,password)}.onSuccess{success(it);refreshApprovals(false)}.onFailure{failure(it,"Staff password reset failed")};busy=false}}
    fun updateStaff(account:ManagedAccount,role:String,historyTier:String,active:Boolean){val current=session?.takeIf{it.developer}?:return;if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.updateStaff(current,account.id,role,historyTier,active)}.onSuccess{success(it);busy=false;refreshApprovals(false)}.onFailure{failure(it,"Could not update staff access")};busy=false}}
    fun loadExclusions(showMessage:Boolean=true){val current=session?.takeIf{it.developer}?:return;viewModelScope.launch{if(showMessage)busy=true;runCatching{MobileApi.exclusions(current)}.onSuccess{excludedPayers=it;val names=it.map{payer->payer.name};Graph.rules.setExcludedPayers(names);exclusions=names.joinToString(", ")}.onFailure{if(showMessage)failure(it,"Could not load exclusions")};if(showMessage)busy=false}}
    fun loadExcludedTransactions(payer:String){val current=session?.takeIf{it.developer}?:return;excludedTransactions=emptyList();viewModelScope.launch{loadingTransactions=true;runCatching{MobileApi.excludedTransactions(current,payer)}.onSuccess{excludedTransactions=it}.onFailure{failure(it,"Could not load excluded transactions")};loadingTransactions=false}}
    fun loadStudents(showMessage:Boolean=true){val current=session?.takeIf{it.developer}?:return;viewModelScope.launch{runCatching{MobileApi.students(current)}.onSuccess{students=it}.onFailure{if(showMessage)failure(it,"Could not load students")}}}
    fun tagTransaction(transactionId:String,studentId:String?){val current=session?.takeIf{it.developer}?:return;if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.tagTransaction(current,transactionId,studentId)}.onSuccess{success(it);load(range,false)}.onFailure{failure(it,"Could not save student tag")};busy=false}}
    fun addExcludedPayer(value:String){saveExcludedNames(excludedPayers.map{it.name}+value.trim())}
    fun removeExcludedPayer(value:String){saveExcludedNames(excludedPayers.map{it.name}.filterNot{it.equals(value,true)})}
    private fun saveExcludedNames(names:List<String>){val current=session?.takeIf{it.developer}?:return;if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.saveExclusions(current,names.filter{it.isNotBlank()})}.onSuccess{excludedPayers=it;val saved=it.map{payer->payer.name};Graph.rules.setExcludedPayers(saved);exclusions=saved.joinToString(", ");success("Excluded payer list saved and applied")}.onFailure{failure(it,"Only payer names found in qualified transactions can be excluded")};busy=false}}
    fun refreshPreferences(showMessage:Boolean=true){val current=session?:return;viewModelScope.launch{if(showMessage)busy=true;runCatching{MobileApi.preferences(current)}.onSuccess{value->val updated=current.copy(notificationPreferences=value);Graph.mobileSession.save(updated);session=updated;if(showMessage)success("Notification settings refreshed")}.onFailure{if(showMessage)failure(it,"Could not load notification settings")};if(showMessage)busy=false}}
    fun savePreferences(value:NotificationPreferences){val current=session?:return;if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.savePreferences(current,value)}.onSuccess{saved->val updated=current.copy(notificationPreferences=saved);Graph.mobileSession.save(updated);session=updated;success("Notification settings saved")}.onFailure{failure(it,"Could not save notification settings")};busy=false}}
    fun checkForUpdate(showMessage:Boolean=true){viewModelScope.launch{runCatching{MobileApi.appVersion()}.onSuccess{availableUpdate=it.takeIf{info->info.latestVersionCode>com.k10.smsbridge.BuildConfig.VERSION_CODE};if(showMessage)success(if(availableUpdate==null)"K10 Pay is up to date" else "K10 Pay ${it.latestVersionName} is available")}.onFailure{if(showMessage)failure(it,"Could not check for updates")}}}
    fun downloadUpdate(){val info=availableUpdate?:return;if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{AppUpdater.download(getApplication(),info)}.onSuccess{apk->if(AppUpdater.install(getApplication(),apk))success("Update downloaded. Confirm installation to continue.")else success("Allow K10 Pay to install updates, then tap Update again.")}.onFailure{failure(it,"Could not download the update")};busy=false}}
    fun logout(unregister:Boolean=true){val current=session;loadSequence++;historyJob?.cancel();historyJob=null;activeHistoryRange=null;refreshAfterHistory=false;localObserver?.cancel();localObserver=null;session=null;cloudTransactions=emptyList();localTransactions=emptyList();transactions=emptyList();excludedTransactions=emptyList();students=emptyList();requests=emptyList();managedAccounts=emptyList();Graph.mobileSession.clear();if(unregister&&current!=null)viewModelScope.launch{runCatching{MobileApi.unregister(current,deviceId)}}}
    private fun registerDevice(current:MobileSession){
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener{token->viewModelScope.launch{
                runCatching{MobileApi.register(current,token,deviceId,current.developer)}
                    .onSuccess{DeviceRegistrationStatus.success(getApplication())}
                    .onFailure{DeviceRegistrationStatus.failure(getApplication(),it.message?:"Device registration failed")}
            }}
            .addOnFailureListener{DeviceRegistrationStatus.failure(getApplication(),it.message?:"Firebase token request failed")}
    }

    private fun startLocalObserver(current:MobileSession){
        localObserver?.cancel()
        if(!current.developer){localTransactions=emptyList();rebuildTransactions();return}
        val started=SystemClock.elapsedRealtime()
        localObserver=viewModelScope.launch{
            Graph.database.transactions().observeAll().collect{rows->localTransactions=rows;OpeningDiagnostics.stage("Local history",SystemClock.elapsedRealtime()-started,"${rows.size} local records");rebuildTransactions()}
        }
    }

    private fun rebuildTransactions(serverTodayAmount:Double?=null,serverTodayCount:Int?=null){
        val renderStarted=SystemClock.elapsedRealtime()
        try {
        val current=session?:return
        if(!current.developer){
            transactions=cloudTransactions
            if(serverTodayAmount!=null)todayAmount=serverTodayAmount
            if(serverTodayCount!=null)todayCount=serverTodayCount
            return
        }
        val excluded=Graph.rules.current().excludedPayerNames.map(::normalizePayer).toSet()
        val localVisible=localTransactions.filter{normalizePayer(it.payerName) !in excluded && it.syncStatus!="EXCLUDED"}
        val localForRange=localVisible.filter{inRange(it.smsReceivedTimestamp,range)}
        val merged=cloudTransactions.toMutableList()
        localForRange.forEach{local->
            val index=merged.indexOfFirst{cloud->sameTransaction(local,cloud)}
            val mapped=local.toMobileTransaction()
            if(index>=0){
                val cloud=merged[index]
                merged[index]=cloud.copy(
                    status=local.syncStatus,
                    clientTransactionId=local.uniqueLocalId,
                    duplicateKey=local.duplicateKey,
                    originalSmsMasked=cloud.originalSmsMasked.ifBlank{local.rawEligibleSms},
                    localOnly=false,
                    serverMessage=local.serverMessage
                )
            }else merged+=mapped
        }
        transactions=merged.distinctBy{it.duplicateKey?:it.clientTransactionId?:it.id}.sortedByDescending{parseInstant(it.occurredAt)}
        val start=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayMerged=mergeForSummary(cloudTransactions,localVisible.filter{it.smsReceivedTimestamp>=start})
        todayAmount=todayMerged.sumOf{it.amount?:0.0}
        todayCount=todayMerged.size
        } finally { OpeningDiagnostics.stage("Display merge",SystemClock.elapsedRealtime()-renderStarted,"slowest local/server merge",maximum=true) }
    }

    private fun mergeForSummary(cloud:List<MobileTransaction>,local:List<TransactionEntity>):List<MobileTransaction>{
        val result=cloud.filter{parseInstant(it.occurredAt)>=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}.toMutableList()
        local.forEach{item->if(result.none{sameTransaction(item,it)})result+=item.toMobileTransaction()}
        return result.distinctBy{it.duplicateKey?:it.clientTransactionId?:it.id}
    }

    private fun TransactionEntity.toMobileTransaction()=MobileTransaction(
        id=serverTransactionId?:"local:$uniqueLocalId",
        payerName=payerName,
        amount=amountMinor/100.0,
        occurredAt=Instant.ofEpochMilli(smsReceivedTimestamp).toString(),
        paymentMethod=paymentMethod,
        status=syncStatus,
        originalSmsMasked=rawEligibleSms,
        clientTransactionId=uniqueLocalId,
        duplicateKey=duplicateKey,
        localOnly=serverTransactionId==null,
        serverMessage=serverMessage
    )

    private fun sameTransaction(local:TransactionEntity,cloud:MobileTransaction):Boolean{
        if(cloud.clientTransactionId==local.uniqueLocalId)return true
        if(local.serverTransactionId!=null&&cloud.id==local.serverTransactionId)return true
        if(cloud.duplicateKey!=null&&cloud.duplicateKey==local.duplicateKey)return true
        val amountMatches=cloud.amount?.let{abs(it-local.amountMinor/100.0)<0.005}?:false
        val timeMatches=abs(parseInstant(cloud.occurredAt)-local.smsReceivedTimestamp)<120_000
        return amountMatches&&timeMatches&&normalizePayer(cloud.payerName)==normalizePayer(local.payerName)
    }

    private fun inRange(timestamp:Long,value:String):Boolean{
        val zone=ZoneId.systemDefault()
        val cutoff=when(value){
            "15d"->LocalDate.now().minusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()
            "30d"->LocalDate.now().minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
            "all"->Long.MIN_VALUE
            else->LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        }
        return timestamp>=cutoff
    }

    private fun parseInstant(value:String)=runCatching{Instant.parse(value).toEpochMilli()}.getOrDefault(0L)
    private fun normalizePayer(value:String)=value.trim().lowercase().replace(Regex("[^a-z0-9]+")," ").trim()
}

