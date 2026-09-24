package com.k10.smsbridge.mobile

import android.content.Context

data class DeviceRegistrationSnapshot(
    val registered: Boolean,
    val updatedAt: Long,
    val message: String
)

object DeviceRegistrationStatus {
    private const val PREFS = "k10_device_registration_status"

    fun success(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("registered", true)
            .putLong("updated_at", System.currentTimeMillis())
            .putString("message", "Server accepted this device registration")
            .apply()
    }

    fun failure(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("registered", false)
            .putLong("updated_at", System.currentTimeMillis())
            .putString("message", message.take(180))
            .apply()
    }

    fun read(context: Context): DeviceRegistrationSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DeviceRegistrationSnapshot(
            registered = prefs.getBoolean("registered", false),
            updatedAt = prefs.getLong("updated_at", 0L),
            message = prefs.getString("message", "This phone has not registered with the server yet").orEmpty()
        )
    }
}
