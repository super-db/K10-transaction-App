package com.k10.smsbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.k10.smsbridge.ThemeMode
import com.k10.smsbridge.mobile.ManagedAccount
import com.k10.smsbridge.mobile.NotificationPreferences
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun K10PayApp(darkMode:Boolean,themeMode:ThemeMode,onThemeModeChange:(ThemeMode)->Unit,vm:MobileViewModel=viewModel()){
    val onToggleTheme={onThemeModeChange(if(darkMode)ThemeMode.LIGHT else ThemeMode.DARK)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val notify=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(vm.session){if(vm.session!=null&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notify.launch(Manifest.permission.POST_NOTIFICATIONS)}
    val session=vm.session
    if(session==null){AuthScreen(vm,darkMode,onToggleTheme);return}
    if(session.passwordChangeRequired){ForcedPasswordScreen(vm,darkMode,onToggleTheme);return}
    var screen by remember{mutableStateOf("home")}
    BackHandler(enabled=screen!="home"){screen="home"}
    AnimatedContent(targetState=screen,transitionSpec={fadeIn(tween(220))+slideInHorizontally{it/8} togetherWith fadeOut(tween(160))},label="screen"){destination->
        when(destination){
            "bridge"->BridgeApp()
            "exclusions"->ExclusionsScreen(vm){screen="settings"}
            "passwords"->ManagePasswordsScreen(vm){screen="settings"}
            "roles"->StaffAccessScreen(vm){screen="settings"}
            "approvals"->ApprovalScreen(vm){screen="home"}
            "settings"->SettingsScreen(vm,themeMode,onThemeModeChange,{screen="home"},{screen="bridge"},{vm.loadExclusions();screen="exclusions"},{if(vm.session?.developer==true)vm.refreshApprovals(false);screen="passwords"},{vm.refreshApprovals(false);screen="roles"})
            else->MobileHome(vm,{screen="approvals"},{screen="settings"})
        }
    }
}

@Composable private fun ThemeButton(dark:Boolean,toggle:()->Unit){FilledTonalIconButton(onClick=toggle){Text(if(dark)"☀" else "☾",style=MaterialTheme.typography.titleLarge)}}

@Composable
private fun AuthScreen(vm:MobileViewModel,dark:Boolean,toggle:()->Unit){
    var mode by remember{mutableStateOf("choose")}
    BackHandler(enabled=mode!="choose"){vm.clearMessage();mode="choose"}
    Scaffold{padding->LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.Center){item{
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){K10Wordmark(MaterialTheme.typography.displaySmall);Text("Secure Slice transaction assistant",color=MaterialTheme.colorScheme.primary)};ThemeButton(dark,toggle)}
        Spacer(Modifier.height(30.dp))
        AnimatedContent(mode,label="auth-mode"){value->when(value){
            "developer"->DeveloperLogin(vm){mode="forgot"}
            "staff"->StaffLogin(vm,{mode="signup"},{mode="forgot"})
            "signup"->SignupForm(vm){mode="staff"}
            "forgot"->ForgotPasswordForm(vm){mode="choose"}
            else->Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Choose how you want to continue",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Button({vm.clearMessage();mode="developer"},Modifier.fillMaxWidth().height(54.dp)){Text("Developer")};OutlinedButton({vm.clearMessage();mode="staff"},Modifier.fillMaxWidth().height(54.dp)){Text("Staff login")};TextButton({vm.clearMessage();mode="signup"},Modifier.align(Alignment.CenterHorizontally)){Text("New staff? Create account")};TextButton({vm.clearMessage();mode="forgot"},Modifier.align(Alignment.CenterHorizontally)){Text("Forgot password?")}}
        }}
    }}}
}

@Composable private fun DeveloperLogin(vm:MobileViewModel,forgot:()->Unit){var password by remember{mutableStateOf("")};Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Developer password",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Use your private Developer password. A temporary password must be replaced after the first sign-in.",color=MaterialTheme.colorScheme.onSurfaceVariant);PasswordField(password,{password=it},"Password");Message(vm);Button({vm.login("DB",password,true)},enabled=!vm.busy&&password.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Signing in…" else "Continue")};TextButton(forgot,Modifier.align(Alignment.CenterHorizontally)){Text("Forgot password?")}}}

@Composable private fun StaffLogin(vm:MobileViewModel,signup:()->Unit,forgot:()->Unit){var phone by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Staff login",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);OutlinedTextField(phone,{phone=it.filter(Char::isDigit).take(15)},label={Text("Phone number")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),singleLine=true,modifier=Modifier.fillMaxWidth());PasswordField(password,{password=it},"Password");Message(vm);Button({vm.login(phone,password,false)},enabled=!vm.busy&&phone.length>=10&&password.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Signing in…" else "Sign in")};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(signup){Text("Create account")};TextButton(forgot){Text("Forgot password?")}}}}

@Composable private fun SignupForm(vm:MobileViewModel,done:()->Unit){var name by remember{mutableStateOf("")};var phone by remember{mutableStateOf("")};var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Create staff account",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Developer approval is required before the first sign-in.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(name,{name=it.take(100)},label={Text("Full name")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(phone,{phone=it.filter(Char::isDigit).take(15)},label={Text("Phone number")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(email,{email=it.trim().take(254)},label={Text("Email for password recovery")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,modifier=Modifier.fillMaxWidth());PasswordField(password,{password=it},"Create password (minimum 8 characters)");PasswordField(confirm,{confirm=it},"Confirm password");if(confirm.isNotEmpty()&&confirm!=password)Text("Passwords do not match",color=MaterialTheme.colorScheme.error);Message(vm);Button({vm.signup(name,phone,email,password)},enabled=!vm.busy&&name.isNotBlank()&&phone.length>=10&&email.contains('@')&&password.length>=8&&password==confirm,modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Sending request…" else "Request approval")};TextButton(done,Modifier.align(Alignment.CenterHorizontally)){Text("Back to staff login")}}}

@Composable private fun ForgotPasswordForm(vm:MobileViewModel,done:()->Unit){var email by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var sent by remember{mutableStateOf(false)};Column(verticalArrangement=Arrangement.spacedBy(11.dp)){Text("Reset password",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("We send a one-time code to the registered email. Your existing password is never emailed.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(email,{email=it.trim()},label={Text("Registered email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,enabled=!sent,modifier=Modifier.fillMaxWidth());if(sent){OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("6-digit code")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),singleLine=true,modifier=Modifier.fillMaxWidth());PasswordField(password,{password=it},"New password (minimum 8 characters)")};Message(vm);if(!sent)Button({vm.requestReset(email){sent=true}},enabled=!vm.busy&&email.contains('@'),modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Sending…" else "Send reset code")}else Button({vm.completeReset(email,code,password,done)},enabled=!vm.busy&&code.length==6&&password.length>=8,modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Resetting…" else "Set new password")};TextButton(done,Modifier.align(Alignment.CenterHorizontally)){Text("Back to login")}}}

@Composable private fun ForcedPasswordScreen(vm:MobileViewModel,dark:Boolean,toggle:()->Unit){var current by remember{mutableStateOf("")};var next by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};BackHandler{};Scaffold{padding->Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),verticalArrangement=Arrangement.Center){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Create your private password",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Required before K10 Pay opens",color=MaterialTheme.colorScheme.primary)};ThemeButton(dark,toggle)};Spacer(Modifier.height(22.dp));PasswordField(current,{current=it},"Current / temporary password");Spacer(Modifier.height(10.dp));PasswordField(next,{next=it},"New password (minimum 8 characters)");Spacer(Modifier.height(10.dp));PasswordField(confirm,{confirm=it},"Confirm new password");if(confirm.isNotEmpty()&&confirm!=next)Text("Passwords do not match",color=MaterialTheme.colorScheme.error);Message(vm);Button({vm.changePassword(current,next){ }},enabled=!vm.busy&&current.isNotBlank()&&next.length>=8&&next==confirm,modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Text(if(vm.busy)"Saving…" else "Save and sign in again")}}}}

@Composable private fun MobileHome(vm:MobileViewModel,openApprovals:()->Unit,openSettings:()->Unit){val session=vm.session?:return;Scaffold(topBar={Surface(shadowElevation=3.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){K10Wordmark(MaterialTheme.typography.headlineMedium);Text("${session.displayName} · ${roleName(session.role)}",color=MaterialTheme.colorScheme.onSurfaceVariant)};if(session.developer)BadgedBox(badge={if(vm.pendingApprovals>0)Badge{Text(vm.pendingApprovals.coerceAtMost(99).toString())}}){IconButton(openApprovals){Text("●",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleLarge)}};IconButton(openSettings){Text("⚙",style=MaterialTheme.typography.titleLarge)}}}}){padding->LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){Column(Modifier.padding(20.dp)){Text("K10 SLICE ACCOUNT",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold);val total=vm.transactions.mapNotNull{it.amount}.sum();Crossfade(targetState=if(vm.transactions.any{it.amount!=null})money(total)else"Amount hidden",label="total"){Text(it,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)};Text("Received · ${rangeName(vm.range)} · ${vm.transactions.size} transaction(s)",color=MaterialTheme.colorScheme.onSurfaceVariant)}}};item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){Range("Today",true,vm.range=="today",Modifier.weight(1f)){vm.load("today")};Range("15d",session.has("finance.slice.history15"),vm.range=="15d",Modifier.weight(1f)){vm.load("15d")};Range("30d",session.has("finance.slice.history30"),vm.range=="30d",Modifier.weight(1f)){vm.load("30d")};Range("All",session.has("finance.slice.historyLifetime"),vm.range=="all",Modifier.weight(1f)){vm.load("all")}}};item{Text("Recent transactions",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)};if(vm.message.isNotBlank())item{Message(vm)};if(vm.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())};if(!vm.busy&&vm.transactions.isEmpty())item{Text("No visible transactions in this range.",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(20.dp))};items(vm.transactions,key={it.id}){tx->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.secondaryContainer){Text(tx.payerName.take(2).uppercase(),Modifier.padding(12.dp),fontWeight=FontWeight.Bold)};Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(tx.payerName,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium);Text("${formatTime(tx.occurredAt)} · ${tx.paymentMethod}",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)};Text(tx.amount?.let(::money)?:"Hidden",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)}}}}}}

@Composable
private fun SettingsScreen(vm:MobileViewModel,themeMode:ThemeMode,onThemeModeChange:(ThemeMode)->Unit,back:()->Unit,bridge:()->Unit,exclusions:()->Unit,passwords:()->Unit,roles:()->Unit){
    val session=vm.session?:return
    val preferences=session.notificationPreferences
    Scaffold(topBar={AppBar("Settings",back)}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{SettingsCard("Account & security","Your K10 Pay account is independent from the Finance Dashboard."){
                SettingsAction("Change my password",onClick=passwords)
                SettingsAction("Recovery email","Registered during signup"){}
                SettingsAction("Sign out"){vm.logout()}
            }}
            item{SettingsCard("Access","Managed by the K10 Pay Developer."){
                SettingsValue("Role",roleName(session.role))
                SettingsValue("History",historyName(session.historyTier))
                SettingsValue("Amounts","Visible")
                if(session.developer)SettingsAction("Manage staff roles & history",onClick=roles)
            }}
            item{SettingsCard("Notifications","These switches control this account on every registered phone."){
                ToggleSetting("Transaction alerts",preferences.transactionAlerts){vm.savePreferences(preferences.copy(transactionAlerts=it))}
                ToggleSetting("Voice announcements",preferences.voiceAnnouncements){vm.savePreferences(preferences.copy(voiceAnnouncements=it))}
                if(session.developer)ToggleSetting("New staff approvals",preferences.approvalAlerts){vm.savePreferences(preferences.copy(approvalAlerts=it))}
            }}
            item{SettingsCard("Appearance","Blue and white in light mode; blue-violet accents in dark mode."){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){ThemeMode.values().forEach{mode->FilterChip(selected=themeMode==mode,onClick={onThemeModeChange(mode)},label={Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))},modifier=Modifier.weight(1f))}}
            }}
            if(session.developer)item{SettingsCard("Transaction collection","Rules remain strict for destination account xx7972."){
                SettingsAction("Excluded payer names",onClick=exclusions)
                SettingsAction("SMS bridge & matching rules",onClick=bridge)
            }}
            item{SettingsCard("App updates","Secure updates are checked from the K10 Pay server."){
                vm.availableUpdate?.let{Text("Version ${it.latestVersionName} is available",fontWeight=FontWeight.Bold);Button(vm::downloadUpdate,enabled=!vm.busy,modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text(if(vm.busy)"Downloading…" else "Download & install")}}?:OutlinedButton({vm.checkForUpdate()},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("Check for updates")}
            }}
            if(vm.message.isNotBlank())item{Message(vm)}
        }
    }
}

@Composable private fun StaffAccessScreen(vm:MobileViewModel,back:()->Unit){
    LaunchedEffect(Unit){vm.refreshApprovals(false)}
    Scaffold(topBar={AppBar("Roles & permissions",back)}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Text("Transaction Viewers receive alerts and see today. Transaction Supervisors receive the same alerts and can be granted 15 days, 30 days, or lifetime history.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(vm.managedAccounts.filter{it.role!="developer"},key={it.id}){account->StaffAccessCard(account,vm)}
            if(vm.message.isNotBlank())item{Message(vm)}
        }
    }
}

@Composable private fun StaffAccessCard(account:ManagedAccount,vm:MobileViewModel){
    var supervisor by remember(account.id,account.role){mutableStateOf(account.role=="transaction_supervisor")}
    var history by remember(account.id,account.historyTier){mutableStateOf(if(account.historyTier=="today")"15d" else account.historyTier)}
    var active by remember(account.id,account.active){mutableStateOf(account.active)}
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(account.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Text(if(account.phone.isBlank())account.email else account.phone,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleSetting("Transaction Supervisor",supervisor){supervisor=it}
        if(supervisor){Text("History access",fontWeight=FontWeight.SemiBold);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("15d","30d","lifetime").forEach{value->FilterChip(selected=history==value,onClick={history=value},label={Text(if(value=="lifetime")"All" else value)},modifier=Modifier.weight(1f))}}}
        ToggleSetting("Account enabled",active){active=it}
        Button({vm.updateStaff(account,if(supervisor)"transaction_supervisor" else "transaction_viewer",if(supervisor)history else "today",active)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("Save access")}
    }}
}

@Composable private fun ApprovalScreen(vm:MobileViewModel,back:()->Unit){LaunchedEffect(Unit){vm.refreshApprovals(false)};Scaffold(topBar={AppBar("Approval inbox",back)}){padding->LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("New staff accounts remain blocked until you approve them.",color=MaterialTheme.colorScheme.onSurfaceVariant)};if(vm.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())};if(vm.requests.isEmpty())item{Card(Modifier.fillMaxWidth()){Text("No pending staff requests",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}};items(vm.requests,key={it.id}){request->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(request.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(request.phone);Text(request.email,color=MaterialTheme.colorScheme.onSurfaceVariant);Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({vm.review(request.id,true)},Modifier.weight(1f),enabled=!vm.busy){Text("Approve")};OutlinedButton({vm.review(request.id,false)},Modifier.weight(1f),enabled=!vm.busy){Text("Reject")}}}}};if(vm.message.isNotBlank())item{Message(vm)}}}}

@Composable
private fun ManagePasswordsScreen(vm:MobileViewModel,back:()->Unit){
    var current by remember{mutableStateOf("")}
    var next by remember{mutableStateOf("")}
    var target by remember{mutableStateOf<ManagedAccount?>(null)}
    var temporary by remember{mutableStateOf("")}
    Scaffold(topBar={AppBar("Manage password",back)}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{
                SettingsCard("Change my password","Changing it signs out all existing sessions."){
                    PasswordField(current,{current=it},"Current password")
                    Spacer(Modifier.height(8.dp))
                    PasswordField(next,{next=it},"New password (minimum 8 characters)")
                    Button({vm.changePassword(current,next){ }},enabled=!vm.busy&&current.isNotBlank()&&next.length>=8,modifier=Modifier.fillMaxWidth().padding(top=10.dp)){Text("Change my password")}
                }
            }
            if(vm.session?.developer==true){
                item{
                    Text("Reset staff password",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("This revokes the staff member's sessions and requires them to change the temporary password next time.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(vm.managedAccounts.filter{it.role!="developer"},key={it.id}){account->
                    Card(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(16.dp)){
                            Text(account.name,fontWeight=FontWeight.Bold)
                            Text(if(account.phone.isBlank())account.email else account.phone,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton({target=account},Modifier.fillMaxWidth().padding(top=8.dp)){Text("Set temporary password")}
                        }
                    }
                }
            }
            if(vm.message.isNotBlank())item{Message(vm)}
        }
    }
    target?.let{account->
        AlertDialog(
            onDismissRequest={target=null},
            title={Text("Reset ${account.name}")},
            text={Column{Text("Enter a temporary password. They must replace it after signing in.");PasswordField(temporary,{temporary=it},"Temporary password")}},
            confirmButton={Button({vm.adminReset(account.id,temporary);target=null;temporary=""},enabled=temporary.length>=8){Text("Reset password")}},
            dismissButton={TextButton({target=null}){Text("Cancel")}}
        )
    }
}

@Composable private fun ExclusionsScreen(vm:MobileViewModel,back:()->Unit){var value by remember(vm.exclusions){mutableStateOf(vm.exclusions)};Scaffold(topBar={AppBar("Excluded payers",back)}){padding->Column(Modifier.padding(padding).padding(16.dp)){Text("Transactions from these exact names are blocked from speech, notifications, history and totals.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(value,{value=it},label={Text("Names separated by commas")},minLines=4,modifier=Modifier.fillMaxWidth().padding(vertical=12.dp));Button({vm.saveExclusions(value)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"Saving…"else"Save exclusions")};Message(vm)}}}

@Composable private fun K10Wordmark(style:androidx.compose.ui.text.TextStyle){Row(verticalAlignment=Alignment.CenterVertically){Text("K10",style=style,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onBackground);Text(" Pay",style=style,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}}
@Composable private fun AppBar(title:String,back:()->Unit){Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){TextButton(back){Text("‹ Back")};Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)}}}
@Composable private fun SettingsCard(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp));content()}}}
@Composable private fun SettingsAction(label:String,detail:String?=null,onClick:()->Unit){TextButton(onClick,Modifier.fillMaxWidth(),contentPadding=PaddingValues(vertical=10.dp)){Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(label,color=MaterialTheme.colorScheme.onSurface,fontWeight=FontWeight.Medium);detail?.let{Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}};Text("›",style=MaterialTheme.typography.titleLarge)}}
@Composable private fun SettingsValue(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=10.dp)){Text(label,Modifier.weight(1f));Text(value,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)}}
@Composable private fun ToggleSetting(label:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onChange)}}
@Composable private fun PasswordField(value:String,onChange:(String)->Unit,label:String){OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())}
@Composable private fun Message(vm:MobileViewModel){if(vm.message.isNotBlank())Text(vm.message,color=if(vm.messageIsError)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,modifier=Modifier.padding(vertical=8.dp))}
@Composable private fun Range(label:String,enabled:Boolean,selected:Boolean,modifier:Modifier,onClick:()->Unit){OutlinedButton(onClick,enabled=enabled,modifier=modifier,colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),contentPadding=PaddingValues(horizontal=4.dp)){Text(label)}}
private fun money(value:Double)=NumberFormat.getCurrencyInstance(Locale("en","IN")).format(value)
private fun formatTime(value:String)=runCatching{DateTimeFormatter.ofPattern("dd MMM uuuu, h:mm a").format(Instant.parse(value).atZone(ZoneId.systemDefault()))}.getOrDefault(value)
private fun roleName(value:String)=when(value){"developer"->"Developer";"transaction_supervisor"->"Transaction Supervisor";else->"Transaction Viewer"}
private fun historyName(value:String)=when(value){"15d"->"Up to 15 days";"30d"->"Up to 30 days";"lifetime"->"Lifetime";else->"Today only"}
private fun rangeName(value:String)=when(value){"15d"->"last 15 days";"30d"->"last 30 days";"all"->"lifetime";else->"today"}
