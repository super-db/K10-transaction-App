package com.k10.smsbridge.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.k10.smsbridge.Graph
import com.k10.smsbridge.mobile.*
import kotlinx.coroutines.launch

class MobileViewModel(app:Application):AndroidViewModel(app){
    var session:MobileSession? by mutableStateOf(Graph.mobileSession.load());private set
    var transactions:List<MobileTransaction> by mutableStateOf(emptyList());private set
    var range by mutableStateOf("today");private set
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var messageIsError by mutableStateOf(false);private set
    var exclusions by mutableStateOf("");private set
    var requests:List<AccountRequest> by mutableStateOf(emptyList());private set
    var managedAccounts:List<ManagedAccount> by mutableStateOf(emptyList());private set
    val pendingApprovals:Int get()=requests.size
    private val deviceId=Settings.Secure.getString(app.contentResolver,Settings.Secure.ANDROID_ID)?:"k10-unknown-device"

    init{session?.let{if(!it.passwordChangeRequired){load("today");registerDevice(it);if(it.developer)refreshApprovals(false)}}}

    fun clearMessage(){message="";messageIsError=false}
    private fun success(value:String){message=value;messageIsError=false}
    private fun failure(error:Throwable,fallback:String){message=error.message?:fallback;messageIsError=true}

    fun login(id:String,password:String,developer:Boolean){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.login(id.trim(),password,developer)}.onSuccess{Graph.mobileSession.save(it);session=it;busy=false;if(!it.passwordChangeRequired){registerDevice(it);load("today");if(it.developer)refreshApprovals(false)}}.onFailure{failure(it,"Sign in failed")};busy=false}}
    fun signup(name:String,phone:String,email:String,password:String){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.signup(name.trim(),phone.trim(),email.trim(),password)}.onSuccess(::success).onFailure{failure(it,"Account request failed")};busy=false}}
    fun requestReset(email:String,onSent:()->Unit={}){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.requestReset(email.trim())}.onSuccess{success(it);onSent()}.onFailure{failure(it,"Reset email could not be sent")};busy=false}}
    fun completeReset(email:String,code:String,password:String,onDone:()->Unit){if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.completeReset(email.trim(),code.trim(),password)}.onSuccess{success(it);onDone()}.onFailure{failure(it,"Password reset failed")};busy=false}}
    fun changePassword(current:String,next:String,onDone:()->Unit){val active=session?:return;if(busy)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.changePassword(active,current,next)}.onSuccess{logout(false);success(it);onDone()}.onFailure{failure(it,"Password change failed")};busy=false}}
    fun load(value:String){val current=session?:return;if(busy||current.passwordChangeRequired)return;viewModelScope.launch{busy=true;clearMessage();runCatching{MobileApi.transactions(current,value)}.onSuccess{transactions=it;range=value}.onFailure{failure(it,"Could not load transactions")};busy=false}}
    fun refreshApprovals(showMessage:Boolean=true){val current=session?.takeIf{it.developer}?:return;viewModelScope.launch{if(showMessage)busy=true;runCatching{MobileApi.approvalInbox(current)}.onSuccess{requests=it.requests;managedAccounts=it.accounts;if(showMessage)success("Approval inbox refreshed")}.onFailure{failure(it,"Could not load account requests")};busy=false}}
    fun review(requestId:String,approve:Boolean){val current=session?:return;if(busy)return;viewModelScope.launch{busy=true;runCatching{MobileApi.review(current,requestId,approve)}.onSuccess{success(it);busy=false;refreshApprovals(false)}.onFailure{failure(it,"Could not review request")};busy=false}}
    fun adminReset(userId:String,password:String){val current=session?:return;if(busy)return;viewModelScope.launch{busy=true;runCatching{MobileApi.adminReset(current,userId,password)}.onSuccess{success(it);refreshApprovals(false)}.onFailure{failure(it,"Staff password reset failed")};busy=false}}
    fun loadExclusions(){val current=session?:return;viewModelScope.launch{busy=true;runCatching{MobileApi.exclusions(current)}.onSuccess{exclusions=it.joinToString(", ")}.onFailure{failure(it,"Could not load exclusions")};busy=false}}
    fun saveExclusions(value:String){val current=session?:return;viewModelScope.launch{busy=true;runCatching{MobileApi.saveExclusions(current,value.split(',').map{it.trim()}.filter{it.isNotBlank()})}.onSuccess{exclusions=it.joinToString(", ");success("Excluded payer list saved")}.onFailure{failure(it,"Could not save exclusions")};busy=false}}
    fun logout(unregister:Boolean=true){val current=session;session=null;transactions=emptyList();requests=emptyList();managedAccounts=emptyList();Graph.mobileSession.clear();if(unregister&&current!=null)viewModelScope.launch{runCatching{MobileApi.unregister(current,deviceId)}}}
    private fun registerDevice(current:MobileSession){FirebaseMessaging.getInstance().token.addOnSuccessListener{token->viewModelScope.launch{runCatching{MobileApi.register(current,token,deviceId,current.developer)}}}}
}
