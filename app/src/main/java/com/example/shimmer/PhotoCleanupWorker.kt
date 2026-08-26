package com.example.shimmer

import android.content.Context
import android.os.Environment
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/**
 * 每天凌晨清理过期照片。
 * 有效期按拍摄时间计算，拍摄当天不计入天数：
 * 例如选 1 天，则拍摄当天和明天保留，后天凌晨删除。
 */
class PhotoCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val dir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: return Result.success()
        if (!dir.exists()) {
            return Result.success()
        }

        val now = System.currentTimeMillis()
        dir.listFiles { file -> file.isFile && file.extension.equals("jpg", ignoreCase = true) }
            ?.forEach { file ->
                if (PhotoExpiry.isExpired(file, now)) {
                    file.delete()
                }
            }
        return Result.success()
    }
}
