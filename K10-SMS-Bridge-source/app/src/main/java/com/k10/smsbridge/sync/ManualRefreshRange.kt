package com.k10.smsbridge.sync

import java.time.Instant
import java.time.ZoneId

data class ManualRefreshRange(val key:String,val from:Long,val until:Long){
    val scansInbox:Boolean get()=key!="all"
    fun contains(timestamp:Long)=timestamp>=from&&timestamp<=until
    companion object{
        fun create(key:String,now:Long=System.currentTimeMillis()):ManualRefreshRange{
            require(key in setOf("today","15d","30d","all"))
            val date=Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
            val days=when(key){"15d"->15L;"30d"->30L;else->1L}
            val start=if(key=="all")0L else date.minusDays(days-1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
            return ManualRefreshRange(key,start,now)
        }
    }
}
