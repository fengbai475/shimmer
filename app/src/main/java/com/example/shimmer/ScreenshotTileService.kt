package com.example.shimmer

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 快捷设置磁贴：按当前选择的截图方式触发截图。 */
class ScreenshotTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (ShimmerPrefs.screenshotMode(this)) {
            ScreenshotMode.ACCESSIBILITY -> {
                if (ScreenshotAccessibilityService.isEnabled(this)) {
                    launch(ScreenshotMode.ACCESSIBILITY)
                } else {
                    openAccessibilitySettings()
                }
            }
            else -> launch(ScreenshotMode.MEDIA_PROJECTION)
        }
    }

    private fun launch(mode: String) {
        val intent = Intent(this, ScreenshotCaptureActivity::class.java)
            .putExtra(ScreenshotCaptureActivity.EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapseCompat(intent, requestCode = 0)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapseCompat(intent, requestCode = 1)
    }

    /** API 34+ 用 PendingIntent 版本，低版本退回（已弃用但可用的）Intent 版本。 */
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseCompat(intent: Intent, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(
                if (ShimmerPrefs.screenshotMode(this) == ScreenshotMode.ACCESSIBILITY) {
                    R.string.mode_accessibility
                } else {
                    R.string.mode_media_projection
                }
            )
        }
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
