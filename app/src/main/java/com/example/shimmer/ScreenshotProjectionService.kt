package com.example.shimmer

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * mediaProjection 前台服务：
 * Android 14+ 要求先获得授权，再启动本服务；服务启动后创建投影并抓一帧保存。
 */
class ScreenshotProjectionService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var captureAttempts = 0
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val resultData = intent?.getResultData()
        if (resultData != null) {
            startCapture(resultData)
        } else {
            finishCapture(false)
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "shimmer_projection"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.projection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle(getString(R.string.projection_notification_title))
            .setContentText(getString(R.string.projection_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCapture(data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val newProjection = mpm.getMediaProjection(Activity.RESULT_OK, data) ?: run {
            finishCapture(false)
            return
        }
        projection = newProjection
        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    finishCapture(false)
                }
            },
            null
        )

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        handlerThread = HandlerThread("shimmer-capture").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        virtualDisplay = newProjection.createVirtualDisplay(
            "ShimmerScreenshot",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )
        // 等画面稳定后抓帧
        handler!!.postDelayed({ captureFrame(width, height) }, 700)
    }

    private fun captureFrame(width: Int, height: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image != null) {
            val bitmap = imageToBitmap(image)
            image.close()
            val saved = ScreenshotSaver.save(this, bitmap)
            bitmap.recycle()
            finishCapture(saved != null)
        } else if (captureAttempts < 10) {
            captureAttempts++
            handler?.postDelayed({ captureFrame(width, height) }, 200)
        } else {
            finishCapture(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }
        }
    }

    private fun finishCapture(success: Boolean) {
        if (finished) return
        finished = true
        ScreenshotSaver.notifyResult(this, success)
        projection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        handlerThread?.quitSafely()
        stopSelf()
    }

    private fun Intent.getResultData(): Intent? = when {
        Build.VERSION.SDK_INT >= 33 -> getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else -> @Suppress("DEPRECATION") getParcelableExtra(EXTRA_RESULT_DATA)
    }

    companion object {
        const val EXTRA_RESULT_DATA = "media_projection_result_data"
    }
}
