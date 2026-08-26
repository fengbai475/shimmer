package com.example.shimmer

import android.content.Context

/** 应用内持久化设置：照片有效期、截图方式。 */
object ShimmerPrefs {

    private const val FILE_NAME = "shimmer_settings"
    private const val KEY_VALIDITY_DAYS = "photo_validity_days"
    private const val KEY_SCREENSHOT_MODE = "screenshot_mode"
    private const val KEY_GRID_COLUMNS = "grid_columns"
    private const val KEY_SHOW_CAMERA = "show_camera"
    private const val KEY_SHOW_SCREENSHOT = "show_screenshot"

    const val DEFAULT_VALIDITY_DAYS = 30
    const val DEFAULT_GRID_COLUMNS = 3

    fun validityDays(context: Context): Int =
        prefs(context).getInt(KEY_VALIDITY_DAYS, DEFAULT_VALIDITY_DAYS)

    fun setValidityDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_VALIDITY_DAYS, days).apply()
    }

    fun screenshotMode(context: Context): String =
        prefs(context).getString(KEY_SCREENSHOT_MODE, ScreenshotMode.MEDIA_PROJECTION)
            ?: ScreenshotMode.MEDIA_PROJECTION

    fun setScreenshotMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_SCREENSHOT_MODE, mode).apply()
    }

    fun gridColumns(context: Context): Int =
        prefs(context).getInt(KEY_GRID_COLUMNS, DEFAULT_GRID_COLUMNS)

    fun setGridColumns(context: Context, columns: Int) {
        prefs(context).edit().putInt(KEY_GRID_COLUMNS, columns).apply()
    }

    fun showCamera(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CAMERA, true)

    fun setShowCamera(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CAMERA, show).apply()
    }

    fun showScreenshot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_SCREENSHOT, true)

    fun setShowScreenshot(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_SCREENSHOT, show).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

/** 快捷设置磁贴的截图方式。 */
object ScreenshotMode {
    const val MEDIA_PROJECTION = "media_projection"
    const val ACCESSIBILITY = "accessibility"
}
