package com.example.shimmer

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍截图服务：用户在系统设置中开启后，
 * 快捷设置磁贴可触发静默截图，无授权弹窗、无录屏指示。
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TAKE_SCREENSHOT) {
            takeScreenshotAfterShadeCloses()
        }
        return START_NOT_STICKY
    }

    /** 等快捷设置下拉栏完全收起后再截屏；失败时通过通知反馈。 */
    fun takeScreenshotAfterShadeCloses() {
        if (Build.VERSION.SDK_INT < 30) {
            ScreenshotSaver.notifyResult(this, false)
            return
        }
        handler.postDelayed({
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val buffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val saved = bitmap?.let {
                                ScreenshotSaver.save(this@ScreenshotAccessibilityService, it)
                            }
                            bitmap?.recycle()
                            buffer.close()
                            ScreenshotSaver.notifyResult(
                                this@ScreenshotAccessibilityService,
                                saved != null
                            )
                        }

                        override fun onFailure(errorCode: Int) {
                            ScreenshotSaver.notifyResult(this@ScreenshotAccessibilityService, false)
                        }
                    }
                )
            }.onFailure {
                ScreenshotSaver.notifyResult(this, false)
            }
        }, 600)
    }

    companion object {
        const val ACTION_TAKE_SCREENSHOT = "com.example.shimmer.action.TAKE_SCREENSHOT"

        /** 系统已连接的无障碍服务实例，供透明 Activity 直接触发截图。 */
        var instance: ScreenshotAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ScreenshotAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
