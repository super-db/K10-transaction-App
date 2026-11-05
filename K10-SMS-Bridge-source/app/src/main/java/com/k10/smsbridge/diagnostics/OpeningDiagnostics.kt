package com.k10.smsbridge.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.time.Instant
import java.util.UUID

/** Small local timing summary. Never starts/cancels upload work or stores SMS/tokens. */
object OpeningDiagnostics {
    private var prefs:SharedPreferences?=null
    private var started=0L
    @Synchronized fun start(context:Context){
        val now=SystemClock.elapsedRealtime()
        if(started>0 && now-started<2_000)return
        prefs=context.applicationContext.getSharedPreferences("k10_last_opening",Context.MODE_PRIVATE)
        started=now
        prefs!!.edit().clear().putString("session",UUID.randomUUID().toString().take(12))
            .putString("time",Instant.now().toString()).putString("version",com.k10.smsbridge.BuildConfig.VERSION_NAME).apply()
    }
    @Synchronized fun stage(name:String,millis:Long,result:String="OK",maximum:Boolean=false){
        val p=prefs?:return
        if(p.contains("stage_$name")&&(!maximum||p.getLong("ms_$name",0)>=millis))return
        p.edit().putLong("ms_$name",millis).putString("stage_$name","${millis.coerceAtLeast(0)} ms · $result").apply()
    }
    @Synchronized fun serverTiming(value:String?){
        if(!value.isNullOrBlank())prefs?.edit()?.putString("server_timing",value.take(120))?.apply()
    }
    @Synchronized fun report(context:Context):String {
        val p=prefs?:context.applicationContext.getSharedPreferences("k10_last_opening",Context.MODE_PRIVATE)
        if(!p.contains("session"))return "No app-opening timing is available yet. Open Home, then return to diagnostics."
        return buildString {
            appendLine("App opening · ${p.getString("time","")}")
            appendLine("Session ${p.getString("session","")} · app ${p.getString("version","")}")
            for(name in listOf("Local history","Server history","Display merge","Local metadata repair"))
                appendLine("$name: ${p.getString("stage_$name","not completed / not needed")}")
            appendLine("Server processing: ${p.getString("server_timing","not reported by this backend")}")
            append("Stage timings overlap; do not add them together. Upload work is independent of this report.")
        }
    }
}
