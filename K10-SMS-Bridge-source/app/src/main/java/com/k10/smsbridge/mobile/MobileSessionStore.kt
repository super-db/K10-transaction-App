package com.k10.smsbridge.mobile
import android.content.Context
import com.k10.smsbridge.security.SecureTokenStore
import org.json.JSONArray
import org.json.JSONObject
data class MobileSession(val token:String,val loginId:String,val displayName:String,val role:String,val permissions:Set<String>,val passwordChangeRequired:Boolean=false){val developer:Boolean get()=role=="developer";fun has(permission:String)=permissions.contains(permission)}
class MobileSessionStore(context:Context){
 private val secure=SecureTokenStore(context,"mobile");private val prefs=context.getSharedPreferences("k10_pay_mobile_profile",Context.MODE_PRIVATE)
 fun save(session:MobileSession){secure.save(session.token);prefs.edit().putString("profile",JSONObject().apply{put("loginId",session.loginId);put("displayName",session.displayName);put("role",session.role);put("permissions",JSONArray(session.permissions.toList()));put("passwordChangeRequired",session.passwordChangeRequired)}.toString()).apply()}
 fun load():MobileSession?=runCatching{val token=secure.load().takeIf{it.isNotBlank()}?:return null;val json=JSONObject(prefs.getString("profile",null)?:return null);val values=json.optJSONArray("permissions")?:JSONArray();MobileSession(token,json.getString("loginId"),json.getString("displayName"),json.getString("role"),(0 until values.length()).map{values.getString(it)}.toSet(),json.optBoolean("passwordChangeRequired"))}.getOrNull()
 fun clear(){secure.save("");prefs.edit().clear().apply()}
}
