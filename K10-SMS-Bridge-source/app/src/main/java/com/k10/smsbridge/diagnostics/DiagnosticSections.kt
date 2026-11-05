package com.k10.smsbridge.diagnostics

object DiagnosticSections {
    val names=listOf("All","App opening","SMS parsing","Uploads","Notifications")
    fun category(stage:String):String=when(stage.lowercase()){
        "startup","history","ui"->"App opening"
        "sms","sms_parse","sms_recovery","local"->"SMS parsing"
        "firebase","fcm","notifications","notification","voice"->"Notifications"
        "upload","work","server","sms_wake"->"Uploads"
        else->"Other"
    }
}
