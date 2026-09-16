package com.k10.smsbridge.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdater {
    suspend fun download(context: Context, info: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        require(info.downloadUrl.startsWith("https://")) { "The update URL must use HTTPS." }
        val directory = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }
        val target = File(directory, "K10-Pay-${info.latestVersionName}.apk")
        val temporary = File(directory, "${target.name}.download")
        val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive")
        }
        try {
            check(connection.responseCode in 200..299) { "Update download failed (${connection.responseCode})." }
            connection.inputStream.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        if (info.sha256.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(info.sha256, ignoreCase = true)) { "The downloaded update failed its security check." }
        }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Could not prepare the downloaded update." }
        target
    }

    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }
}
