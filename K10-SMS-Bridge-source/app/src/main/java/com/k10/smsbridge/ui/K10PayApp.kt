package com.k10.smsbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun K10PayApp(darkMode:Boolean,onToggleTheme:()->Unit,vm:MobileViewModel=viewModel()){
    val context=androidx.compose.ui.platform.LocalContext.current
    val notify=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(vm.session){
        if(vm.session!=null&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val session=vm.session
    if(session==null){LoginScreen(vm,darkMode,onToggleTheme);return}
    var screen by remember{mutableStateOf("home")}
    BackHandler(enabled=screen!="home"){screen="home"}
    Crossfade(targetState=screen,animationSpec=tween(260),label="screen"){destination->
        when(destination){
            "bridge"->BridgeApp()
            "exclusions"->ExclusionsScreen(vm){screen="home"}
            else->MobileHome(vm,darkMode,onToggleTheme,{screen="bridge"},{vm.loadExclusions();screen="exclusions"})
        }
    }
}

@Composable
private fun ThemeButton(dark:Boolean,toggle:()->Unit){
    FilledTonalIconButton(onClick=toggle){Text(if(dark)"☀" else "☾",style=MaterialTheme.typography.titleLarge)}
}

@Composable
private fun LoginScreen(vm:MobileViewModel,dark:Boolean,toggle:()->Unit){
    var id by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    Scaffold{padding->Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),verticalArrangement=Arrangement.Center){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("K10 Pay",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold);Text("Slice transaction assistant",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)};ThemeButton(dark,toggle)}
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(id,{id=it.uppercase()},label={Text("Login ID (for example DB, RR, AB)")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password,{password=it},label={Text("Finance password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        if(vm.accounts.isNotEmpty())Text("Available: "+vm.accounts.joinToString{"${it.name} (${it.id})"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
        if(vm.message.isNotBlank())Text(vm.message,color=MaterialTheme.colorScheme.error)
        Button({vm.login(id,password)},enabled=!vm.busy&&id.isNotBlank()&&password.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Signing in…" else "Sign in")}
    }}
}

@Composable
private fun MobileHome(vm:MobileViewModel,dark:Boolean,toggle:()->Unit,openBridge:()->Unit,openExclusions:()->Unit){
    val session=vm.session?:return
    Scaffold(topBar={Surface(shadowElevation=3.dp){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("K10 Pay",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(session.displayName,color=MaterialTheme.colorScheme.onSurfaceVariant)};ThemeButton(dark,toggle);TextButton(onClick=vm::logout){Text("Logout")}}}}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            item{Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.padding(18.dp)){Text("K10 SLICE ACCOUNT",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold);val total=vm.transactions.mapNotNull{it.amount}.sum();Crossfade(targetState=if(vm.transactions.any{it.amount!=null})money(total)else"Amount hidden",label="total"){Text(it,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)};Text("${vm.transactions.size} transaction(s) · ${vm.range}",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){Range("Today",true,vm.range=="today",Modifier.weight(1f)){vm.load("today")};Range("15d",session.has("finance.slice.history15"),vm.range=="15d",Modifier.weight(1f)){vm.load("15d")};Range("30d",session.has("finance.slice.history30"),vm.range=="30d",Modifier.weight(1f)){vm.load("30d")};Range("All",session.has("finance.slice.historyLifetime"),vm.range=="all",Modifier.weight(1f)){vm.load("all")}}}
            if(session.developer)item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(openBridge,Modifier.weight(1f)){Text("SMS Bridge")};OutlinedButton(openExclusions,Modifier.weight(1f)){Text("Excluded Payers")}}}
            if(vm.message.isNotBlank())item{Text(vm.message,color=MaterialTheme.colorScheme.error)}
            if(vm.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(!vm.busy&&vm.transactions.isEmpty())item{Text("No visible transactions in this range.",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(20.dp))}
            items(vm.transactions,key={it.id}){tx->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(tx.payerName,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium);Text(tx.amount?.let(::money)?:"Amount hidden",style=MaterialTheme.typography.headlineSmall);Text(formatTime(tx.occurredAt),color=MaterialTheme.colorScheme.onSurfaceVariant);Text(tx.paymentMethod,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}}}
        }
    }
}

@Composable
private fun Range(label:String,enabled:Boolean,selected:Boolean,modifier:Modifier,onClick:()->Unit){
    val color by animateColorAsState(if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,label="range")
    OutlinedButton(onClick,enabled=enabled,modifier=modifier,colors=ButtonDefaults.outlinedButtonColors(containerColor=color),contentPadding=PaddingValues(horizontal=4.dp)){Text(label)}
}

@Composable
private fun ExclusionsScreen(vm:MobileViewModel,back:()->Unit){
    var value by remember(vm.exclusions){mutableStateOf(vm.exclusions)}
    Scaffold(topBar={Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){TextButton(back){Text("‹ Back")};Text("Excluded Payers",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)}}}){padding->Column(Modifier.padding(padding).padding(16.dp)){Text("Transactions from these exact names are blocked from speech, notifications, history and totals.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(value,{value=it},label={Text("Names separated by commas")},minLines=4,modifier=Modifier.fillMaxWidth().padding(vertical=12.dp));Button({vm.saveExclusions(value)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Saving…"else"Save exclusions")};if(vm.message.isNotBlank())Text(vm.message,modifier=Modifier.padding(top=10.dp))}}
}
private fun money(value:Double)=NumberFormat.getCurrencyInstance(Locale("en","IN")).format(value)
private fun formatTime(value:String)=runCatching{DateTimeFormatter.ofPattern("dd MMM uuuu, h:mm a").format(Instant.parse(value).atZone(ZoneId.systemDefault()))}.getOrDefault(value)
