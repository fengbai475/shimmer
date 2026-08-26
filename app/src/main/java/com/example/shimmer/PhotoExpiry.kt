package com.example.shimmer

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 照片有效期计算工具：拍摄时间、有效期天数、到期时间与剩余天数。 */
object PhotoExpiry {

    private const val DEFAULT_VALIDITY_DAYS = 30
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private val FILE_NAME_PATTERN = Regex("""^(IMG|SCR)_(\d{8})_(\d{6})_(\d{3})_(\d+)d\.jpg$""")
    private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /** 解析文件名中的拍摄时间与有效期天数；旧格式照片回退为修改时间 + 默认 30 天。 */
    fun info(file: File): Pair<Long, Int> {
        val match = FILE_NAME_PATTERN.matchEntire(file.name)
        return if (match != null) {
            val captureTime = DATE_FORMAT.parse(
                "${match.groupValues[2]}_${match.groupValues[3]}_${match.groupValues[4]}"
            )?.time ?: file.lastModified()
            val days = match.groupValues[5].toIntOrNull() ?: DEFAULT_VALIDITY_DAYS
            captureTime to days
        } else {
            file.lastModified() to DEFAULT_VALIDITY_DAYS
        }
    }

    /** 到期时间 = 拍摄当日 00:00 + (有效期天数 + 1) 天。 */
    fun expiryTime(captureTime: Long, validityDays: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = captureTime
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, validityDays + 1)
        }
        return calendar.timeInMillis
    }

    fun isExpired(file: File, now: Long = System.currentTimeMillis()): Boolean {
        val (captureTime, days) = info(file)
        return now >= expiryTime(captureTime, days)
    }

    /** 剩余天数：今天算 1 天，到期当天为 0。 */
    fun remainingDays(file: File, now: Long = System.currentTimeMillis()): Int {
        val (captureTime, days) = info(file)
        val expiry = expiryTime(captureTime, days)
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val diffDays = ((expiry - todayStart) / DAY_MILLIS).toInt()
        return (diffDays - 1).coerceAtLeast(0)
    }
}
