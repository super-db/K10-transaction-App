package com.k10.smsbridge.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MobileTransaction(val id:String,val payerName:String,val amount:Double?,val occurredAt:String,val paymentMethod:String,val status:String)
data class AccountRequest(val id:String,val name:String,val phone:String,val email:String,val requestedAt:String)
data class ManagedAccount(val id:String,val name:String,val phone:String,val email:String,val role:String,val active:Boolean,val passwordChangeRequired:Boolean)
data class ApprovalInbox(val requests:List<AccountRequest>,val accounts:List<ManagedAccount>)

object MobileApi{
    const val BASE_URL="https://finances.k10classes.com"

    suspend fun login(loginId:String,password:String,developer:Boolean):MobileSession = withContext(Dispatchers.IO){
        val payload=JSONObject().put("loginId",if(developer)"DB" else loginId).put("password",password).put("developer",developer)
        val(code,body)=request("/api/mobile/auth","POST",null,payload.toString());val json=json(body)
        if(code !in 200..299)error(json.optString("error","Sign in failed"));session(json)
    }
    suspend fun signup(name:String,phone:String,email:String,password:String):String = withContext(Dispatchers.IO){
        val payload=JSONObject().put("name",name).put("phone",phone).put("email",email).put("password",password)
        val(code,body)=request("/api/mobile/signup","POST",null,payload.toString());val json=json(body)
        if(code !in 200..299)error(json.optString("error","Account request failed"));json.optString("message","Request sent to Developer for approval.")
    }
    suspend fun requestReset(email:String):String = withContext(Dispatchers.IO){
        val(code,body)=request("/api/mobile/password-reset","POST",null,JSONObject().put("action","request").put("email",email).toString());val json=json(body)
        if(code !in 200..299)error(json.optString("error","Reset email could not be sent"));json.optString("message","Check your email for the reset code.")
    }
    suspend fun completeReset(email:String,code:String,password:String):String = withContext(Dispatchers.IO){
        val payload=JSONObject().put("action","complete").put("email",email).put("code",code).put("password",password)
        val(codeValue,body)=request("/api/mobile/password-reset","POST",null,payload.toString());val json=json(body)
        if(codeValue !in 200..299)error(json.optString("error","Password reset failed"));json.optString("message","Password changed. Sign in now.")
    }
    suspend fun changePassword(session:MobileSession,current:String,next:String):String = withContext(Dispatchers.IO){
        val payload=JSONObject().put("action","change").put("currentPassword",current).put("password",next)
        val(code,body)=request("/api/mobile/passwords","POST",session.token,payload.toString());val json=json(body)
        if(code !in 200..299)error(json.optString("error","Password change failed"));json.optString("message","Password changed. Sign in again.")
    }
    suspend fun approvalInbox(session:MobileSession):ApprovalInbox = withContext(Dispatchers.IO){
        val(code,body)=request("/api/mobile/account-requests","GET",session.token,null);val json=json(body)
        if(code !in 200..299)error(json.optString("error","Could not load account requests"))
        val requests=json.optJSONArray("requests")?:JSONArray();val accounts=json.optJSONArray("accounts")?:JSONArray()
        ApprovalInbox(
            (0 until requests.length()).map{requests.getJSONObject(it)}.map{AccountRequest(it.getString("id"),it.getString("name"),it.getString("phone"),it.getString("email"),it.optString("requestedAt"))},
            (0 until accounts.length()).map{accounts.getJSONObject(it)}.map{ManagedAccount(it.getString("id"),it.optString("name"),it.optString("phone"),it.optString("email"),it.optString("role"),it.optBoolean("active",true),it.optBoolean("passwordChangeRequired"))}
        )
    }
    suspend fun review(session:MobileSession,requestId:String,approve:Boolean):String = withContext(Dispatchers.IO){
        val payload=JSONObject().put("requestId",requestId).put("action",if(approve)"approve" else "reject")
        val(code,body)=request("/api/mobile/account-requests","POST",session.token,payload.toString());val json=json(body)
        if(code !in 200..299)error(json.optString("error","Could not review request"));json.optString("message",if(approve)"Account approved" else "Request rejected")
    }
    suspend fun adminReset(session:MobileSession,userId:String,password:String):String = withContext(Dispatchers.IO){
        val payload=JSONObject().put("action","adminReset").put("userId",userId).put("password",password)
        val(code,body)=request("/api/mobile/passwords","POST",session.token,payload.toString());val json=json(body)
        if(code !in 200..299)error(json.optString("error","Staff password reset failed"));json.optString("message","Temporary password saved. Staff must change it at next sign-in.")
    }
    suspend fun transactions(session:MobileSession,range:String):List<MobileTransaction> = withContext(Dispatchers.IO){
        val(code,body)=request("/api/mobile/transactions?range=$range","GET",session.token,null);val json=json(body)
        if(code !in 200..299)error(json.optString("error","Could not load transactions"));val array=json.optJSONArray("transactions")?:JSONArray()
        (0 until array.length()).map{array.getJSONObject(it)}.map{MobileTransaction(it.getString("id"),it.optString("payerName","Unknown payer"),if(it.isNull("amount"))null else it.optDouble("amount"),it.optString("occurredAt"),it.optString("paymentMethod","UNKNOWN"),it.optString("status","new"))}
    }
    suspend fun register(session:MobileSession,fcmToken:String,deviceId:String,bridge:Boolean)=withContext(Dispatchers.IO){val payload=JSONObject().put("fcmToken",fcmToken).put("deviceId",deviceId).put("appVersion","3.1.0").put("bridgeDevice",bridge);val(code,body)=request("/api/mobile/devices","POST",session.token,payload.toString());if(code !in 200..299)error(json(body).optString("error","Device registration failed"))}
    suspend fun unregister(session:MobileSession,deviceId:String)=withContext(Dispatchers.IO){request("/api/mobile/devices","DELETE",session.token,JSONObject().put("deviceId",deviceId).toString())}
    suspend fun exclusions(session:MobileSession):List<String> = withContext(Dispatchers.IO){val(code,body)=request("/api/mobile/exclusions","GET",session.token,null);val json=json(body);if(code !in 200..299)error(json.optString("error","Could not load exclusions"));val array=json.optJSONArray("names")?:JSONArray();(0 until array.length()).map{array.getString(it)}}
    suspend fun saveExclusions(session:MobileSession,names:List<String>):List<String> = withContext(Dispatchers.IO){val(code,body)=request("/api/mobile/exclusions","PUT",session.token,JSONObject().put("names",JSONArray(names)).toString());val json=json(body);if(code !in 200..299)error(json.optString("error","Could not save exclusions"));val array=json.optJSONArray("names")?:JSONArray();(0 until array.length()).map{array.getString(it)}}

    private fun session(json:JSONObject):MobileSession{
        val user=json.getJSONObject("user")
        val permissions=user.optJSONArray("permissions")?:JSONArray()
        return MobileSession(json.getString("token"),user.getString("loginId"),user.getString("displayName"),user.getString("role"),(0 until permissions.length()).map{permissions.getString(it)}.toSet(),user.optBoolean("passwordChangeRequired"))
    }
    private fun json(body:String)=runCatching{JSONObject(body)}.getOrElse{JSONObject().put("error","Server returned an invalid response. Please try again.")}
    private fun request(path:String,method:String,token:String?,body:String?):Pair<Int,String>{val connection=(URL(BASE_URL+path).openConnection() as HttpURLConnection).apply{requestMethod=method;connectTimeout=15_000;readTimeout=20_000;setRequestProperty("Accept","application/json");if(token!=null)setRequestProperty("Authorization","Bearer $token");if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json");outputStream.use{it.write(body.toByteArray())}}};return try{val code=connection.responseCode;val stream=if(code in 200..399)connection.inputStream else connection.errorStream;code to(stream?.bufferedReader()?.use{it.readText()}?:"")}finally{connection.disconnect()}}
}
