package com.k10.smsbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.k10.smsbridge.ui.K10PayApp
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.k10.smsbridge.sync.SyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val prefs = remember { getSharedPreferences("k10_pay_appearance", MODE_PRIVATE) }
            val systemDark = isSystemInDarkTheme()
            var mode by remember { mutableStateOf(ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: if (prefs.contains("dark_mode")) if (prefs.getBoolean("dark_mode", systemDark)) "DARK" else "LIGHT" else "SYSTEM")) }
            val dark = when(mode){ThemeMode.SYSTEM->systemDark;ThemeMode.LIGHT->false;ThemeMode.DARK->true}
            SmoothK10Theme(dark) {
                K10PayApp(darkMode=dark,themeMode=mode,onThemeModeChange={value->mode=value;prefs.edit().putString("theme_mode",value.name).apply()})
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(SyncWorker.IMMEDIATE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
private fun SmoothK10Theme(dark:Boolean, content:@Composable ()->Unit){
    val target=if(dark) darkColorScheme(
        primary=Color(0xFFAEC6FF),onPrimary=Color(0xFF092F6B),
        primaryContainer=Color(0xFF28456F),onPrimaryContainer=Color(0xFFD8E5FF),
        secondary=Color(0xFFC0AFFF),secondaryContainer=Color(0xFF423671),
        background=Color(0xFF0E1118),onBackground=Color(0xFFE7EAF2),
        surface=Color(0xFF161A23),onSurface=Color(0xFFE7EAF2),
        surfaceVariant=Color(0xFF232A37),onSurfaceVariant=Color(0xFFC3CBD9)
    ) else lightColorScheme(
        primary=Color(0xFF1459B8),onPrimary=Color.White,
        primaryContainer=Color(0xFFD8E5FF),onPrimaryContainer=Color(0xFF002E69),
        secondary=Color(0xFF6750A4),secondaryContainer=Color(0xFFEADDFF),
        background=Color(0xFFF8FAFF),onBackground=Color(0xFF121722),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF121722),
        surfaceVariant=Color(0xFFECF1FA),onSurfaceVariant=Color(0xFF465165)
    )
    @Composable fun animated(value:Color)=animateColorAsState(value,tween(350),label="theme").value
    MaterialTheme(colorScheme=target.copy(
        primary=animated(target.primary),onPrimary=animated(target.onPrimary),
        background=animated(target.background),onBackground=animated(target.onBackground),
        surface=animated(target.surface),onSurface=animated(target.onSurface),
        surfaceVariant=animated(target.surfaceVariant),onSurfaceVariant=animated(target.onSurfaceVariant)
    ),content=content)
}
