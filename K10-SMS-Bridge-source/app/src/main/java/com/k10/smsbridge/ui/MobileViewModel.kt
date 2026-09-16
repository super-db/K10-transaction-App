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
 var accounts:List<MobileAccount> by mutableStateOf(emptyList());private set
 var transactions:List<MobileTransaction> by mutableStateOf(emptyList());private set
 var range by mutableStateOf("today");private set
 var busy by mutableStateOf(false);private set
 var message by mutableStateOf("");private set
 var exclusions by mutableStateOf("");private set
 private val deviceId=Settings.Secure.getString(app.contentResolver,Settings.Secure.ANDROID_ID)?:"k10-unknown-device"
 init{loadAccounts();session?.let{load("today");registerDevice(it)}}
 fun loadAccounts(){viewModelScope.launch{runCatching{MobileApi.accounts()}.onSuccess{accounts=it}.onFailure{message=it.message?:"Could not load accounts"}}}
 fun login(id:String,password:String){if(busy)return;viewModelScope.launch{busy=true;message="";runCatching{MobileApi.login(id.trim().uppercase(),password)}.onSuccess{Graph.mobileSession.save(it);session=it;registerDevice(it);load("today")}.onFailure{message=it.message?:"Sign in failed"};busy=false}}
 fun load(value:String){val current=session?:return;if(busy)return;viewModelScope.launch{busy=true;message="";runCatching{MobileApi.transactions(current,value)}.onSuccess{transactions=it;range=value}.onFailure{message=it.message?:"Could not load transactions"};busy=false}}
 fun loadExclusions(){val current=session?:return;viewModelScope.launch{busy=true;runCatching{MobileApi.exclusions(current)}.onSuccess{exclusions=it.joinToString(", ")}.onFailure{message=it.message?:"Could not load exclusions"};busy=false}}
 fun saveExclusions(value:String){val current=session?:return;viewModelScope.launch{busy=true;runCatching{MobileApi.saveExclusions(current,value.split(',').map{it.trim()}.filter{it.isNotBlank()})}.onSuccess{exclusions=it.joinToString(", ");message="Excluded payer list saved"}.onFailure{message=it.message?:"Could not save exclusions"};busy=false}}
 fun logout(){val current=session;session=null;transactions=emptyList();Graph.mobileSession.clear();if(current!=null)viewModelScope.launch{runCatching{MobileApi.unregister(current,deviceId)}}}
 private fun registerDevice(current:MobileSession){FirebaseMessaging.getInstance().token.addOnSuccessListener{token->viewModelScope.launch{runCatching{MobileApi.register(current,token,deviceId,current.developer)}}}}
}