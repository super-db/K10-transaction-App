package com.k10.smsbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.k10.smsbridge.ui.K10PayApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { getSharedPreferences("k10_pay_appearance", MODE_PRIVATE) }
            val systemDark = isSystemInDarkTheme()
            var dark by remember { mutableStateOf(prefs.getBoolean("dark_mode", systemDark)) }
            SmoothK10Theme(dark) {
                K10PayApp(darkMode = dark, onToggleTheme = {
                    dark = !dark
                    prefs.edit().putBoolean("dark_mode", dark).apply()
                })
            }
        }
    }
}

@Composable
private fun SmoothK10Theme(dark:Boolean, content:@Composable ()->Unit){
    val target=if(dark) darkColorScheme(
        primary=Color(0xFFB9A4FF),onPrimary=Color(0xFF2D1268),
        background=Color(0xFF121018),onBackground=Color(0xFFEAE6F0),
        surface=Color(0xFF1B1822),onSurface=Color(0xFFEAE6F0),
        surfaceVariant=Color(0xFF2B2634),onSurfaceVariant=Color(0xFFD0C8D8)
    ) else lightColorScheme(
        primary=Color(0xFF6750A4),onPrimary=Color.White,
        background=Color(0xFFFFF9FF),onBackground=Color(0xFF1D1B20),
        surface=Color(0xFFFFF9FF),onSurface=Color(0xFF1D1B20),
        surfaceVariant=Color(0xFFE9E2EC),onSurfaceVariant=Color(0xFF49454F)
    )
    @Composable fun animated(value:Color)=animateColorAsState(value,tween(350),label="theme").value
    MaterialTheme(colorScheme=target.copy(
        primary=animated(target.primary),onPrimary=animated(target.onPrimary),
        background=animated(target.background),onBackground=animated(target.onBackground),
        surface=animated(target.surface),onSurface=animated(target.onSurface),
        surfaceVariant=animated(target.surfaceVariant),onSurfaceVariant=animated(target.onSurfaceVariant)
    ),content=content)
}
