package com.example.shimmer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 透明中转 Activity：
 * - media_projection 模式：先请求系统授权，授权成功后把授权数据交给前台服务去截图
 * - accessibility 模式：收拢下拉栏后通知无障碍服务静默截图
 */
class ScreenshotCaptureActivity : ComponentActivity() {

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startProjectionService(result.data!!)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: ScreenshotMode.MEDIA_PROJECTION
        if (mode == ScreenshotMode.ACCESSIBILITY) {
            triggerAccessibilityScreenshot()
            finish()
            return
        }

        // Android 14+ 顺序要求：先拿到授权，再启动 mediaProjection 前台服务；
        // 用 createConfigForDefaultDisplay() 让授权弹窗只保留“整个屏幕”，去掉“单个应用”选项
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val consentIntent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        consentLauncher.launch(consentIntent)
    }

    private fun startProjectionService(resultData: Intent) {
        val intent = Intent(this, ScreenshotProjectionService::class.java)
            .putExtra(ScreenshotProjectionService.EXTRA_RESULT_DATA, resultData)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun triggerAccessibilityScreenshot() {
        ScreenshotAccessibilityService.instance?.takeScreenshotAfterShadeCloses()
            ?: startService(
                Intent(this, ScreenshotAccessibilityService::class.java)
                    .setAction(ScreenshotAccessibilityService.ACTION_TAKE_SCREENSHOT)
            )
    }

    companion object {
        const val EXTRA_MODE = "screenshot_mode"
    }
}
