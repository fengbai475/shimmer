package com.example.shimmer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 截图保存与结果通知。 */
object ScreenshotSaver {

    private const val CHANNEL_ID = "shimmer_screenshot_result"

    /** 把位图保存为应用相册内的一张照片，沿用有效期命名规则。 */
    fun save(context: Context, bitmap: Bitmap): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        runCatching { File(dir, ".nomedia").writeText("") }

        val days = ShimmerPrefs.validityDays(context)
        val name = "SCR_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) +
            "_${days}d.jpg"
        val file = File(dir, name)
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** 用低优先级通知告知截图结果（Android 13+ 未授予通知权限则静默）。 */
    fun notifyResult(context: Context, success: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.screenshot_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                context.getString(
                    if (success) R.string.screenshot_saved else R.string.screenshot_failed
                )
            )
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(1001, notification)
        }
    }
}
