package com.example.shimmer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.provider.Settings
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.example.shimmer.ui.theme.ShimmerTheme
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        schedulePhotoCleanup()
        enableEdgeToEdge()
        setContent {
            ShimmerTheme {
                CameraScreen()
            }
        }
    }

    /** 注册每天凌晨的周期清理任务（首次运行前先延迟到下一个凌晨 2 点）。 */
    private fun schedulePhotoCleanup() {
        val request = PeriodicWorkRequestBuilder<PhotoCleanupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNextEarlyMorning(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PHOTO_CLEANUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun delayToNextEarlyMorning(): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, EARLY_MORNING_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return next.timeInMillis - now.timeInMillis
    }

    companion object {
        private const val PHOTO_CLEANUP_WORK_NAME = "photo_cleanup"
        private const val EARLY_MORNING_HOUR = 2
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val photosDir = remember {
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var isCameraMode by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var photoFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var validityDays by remember { mutableStateOf(ShimmerPrefs.validityDays(context)) }
    var showValidityDialog by remember { mutableStateOf(false) }
    var viewerPhoto by remember { mutableStateOf<File?>(null) }
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    var screenshotMode by remember { mutableStateOf(ShimmerPrefs.screenshotMode(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var gridColumns by remember { mutableStateOf(ShimmerPrefs.gridColumns(context)) }
    var showCamera by remember { mutableStateOf(ShimmerPrefs.showCamera(context)) }
    var showScreenshot by remember { mutableStateOf(ShimmerPrefs.showScreenshot(context)) }
    var showAdjustDialog by remember { mutableStateOf(false) }

    // 按来源开关过滤后的照片列表（相册网格和查看器共用，默认两者都显示）
    val visiblePhotos = photoFiles.filter { file ->
        if (file.name.startsWith("SCR_")) {
            showScreenshot
        } else {
            showCamera
        }
    }

    // 应用内提示条：显示在底部控制区上方，不依赖系统 Toast
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastKey by remember { mutableStateOf(0) }
    val showMessage: (String) -> Unit = { message ->
        toastMessage = message
        toastKey += 1
    }
    LaunchedEffect(toastKey) {
        if (toastMessage != null) {
            delay(2000)
            toastMessage = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted && isCameraMode) {
            showMessage(context.getString(R.string.permission_needed))
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val galleryWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingSaveFile
        pendingSaveFile = null
        if (granted && file != null) {
            showMessage(
                context.getString(
                    if (saveToGallery(context, file)) R.string.saved_to_gallery
                    else R.string.save_failed
                )
            )
        } else {
            showMessage(context.getString(R.string.storage_permission_needed))
        }
    }

    // 首次进入默认是相册页，切到摄影页时才申请相机权限
    LaunchedEffect(isCameraMode) {
        if (isCameraMode && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 回到相册页时释放相机，避免后台占用
    LaunchedEffect(isCameraMode) {
        if (!isCameraMode) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                runCatching { cameraProviderFuture.get().unbindAll() }
            }, mainExecutor)
        }
    }

    // 相机权限就绪且预览视图创建后，绑定 CameraX 的 Preview 和 ImageCapture
    LaunchedEffect(hasCameraPermission, previewView) {
        if (!hasCameraPermission || previewView == null) return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                imageCapture = capture
            } catch (e: Exception) {
                showMessage(context.getString(R.string.camera_start_failed, e.message))
            }
        }, mainExecutor)
    }

    // 相册页时刷新已拍摄照片列表
    LaunchedEffect(isCameraMode, refreshKey) {
        if (!isCameraMode) {
            photoFiles = loadPhotos(photosDir)
        }
    }

    // 从后台回到前台（如截图后返回）时刷新相册列表，新截图立即可见
    DisposableEffect(lifecycleOwner, isCameraMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !isCameraMode) {
                photoFiles = loadPhotos(photosDir)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 监听相册目录：出现新文件（截图/拍摄）立即刷新，不依赖前后台时机
    DisposableEffect(photosDir) {
        photosDir.mkdirs()
        val fileObserver = object : FileObserver(
            photosDir.absolutePath,
            FileObserver.CREATE or FileObserver.MOVED_TO
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (event == FileObserver.CREATE || event == FileObserver.MOVED_TO) {
                    mainExecutor.execute {
                        photoFiles = loadPhotos(photosDir)
                    }
                }
            }
        }
        fileObserver.startWatching()
        onDispose { fileObserver.stopWatching() }
    }

    // 系统返回键：从摄影页退回相册页
    BackHandler(enabled = isCameraMode) {
        isCameraMode = false
    }

    // 查看大图时返回键退出
    BackHandler(enabled = viewerPhoto != null) {
        viewerPhoto = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // 上半部分：相册页或摄影页
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isCameraMode) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                post { previewView = this }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PermissionHint(onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) })
                }
            } else {
                GalleryPage(
                    photos = visiblePhotos,
                    columns = gridColumns,
                    filteredOut = photoFiles.isNotEmpty() && visiblePhotos.isEmpty(),
                    onPhotoClick = { viewerPhoto = it }
                )
            }
        }

        // 底部控制条：外层容器高度固定，按钮位置大小不变；
        // 白条相册页只覆盖按钮中心线以下的 64dp，拍摄页覆盖满 128dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .background(
                    if (isCameraMode) Color.Transparent else Color(0xFFF5F5F5)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isCameraMode) 128.dp else 64.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.White)
            )

            ShutterButton(
                onClick = {
                    if (!isCameraMode) {
                        isCameraMode = true
                    } else {
                        val capture = imageCapture
                        if (capture == null) {
                            showMessage(context.getString(R.string.camera_not_ready))
                            return@ShutterButton
                        }
                        capturePhoto(
                            context,
                            photosDir,
                            capture,
                            validityDays,
                            mainExecutor,
                            onSaved = {
                                refreshKey += 1
                                photoFiles = loadPhotos(photosDir)
                            },
                            onMessage = showMessage
                        )
                    }
                },
                modifier = Modifier.align(Alignment.Center),
                showPlus = !isCameraMode
            )

            // 相册页右半区中央：“设置”纯文字
            if (!isCameraMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(64.dp)
                        .align(Alignment.BottomEnd)
                        .padding(start = 42.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        color = ShutterColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                if (
                                    Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                                showSettings = true
                            }
                    )
                }
            }

            // 相册页左半区中央：“调整”纯文字
            if (!isCameraMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(64.dp)
                        .align(Alignment.BottomStart)
                        .padding(end = 42.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.adjust_title),
                        color = ShutterColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { showAdjustDialog = true }
                    )
                }
            }

            // 相册入口只在摄影页显示，相册页本身无需入口
            if (isCameraMode) {
                ViewerActionButton(
                    text = stringResource(R.string.album_label),
                    onClick = { isCameraMode = false },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 36.dp)
                )
            }

            // 拍摄页右侧：显示当前保存天数，点击弹出居中选择弹窗
            if (isCameraMode) {
                ViewerActionButton(
                    text = stringResource(R.string.validity_days, validityDays),
                    onClick = { showValidityDialog = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 36.dp)
                )
            }
        }
    }

    viewerPhoto?.let { selected ->
        if (visiblePhotos.isNotEmpty()) {
            PhotoViewer(
                photos = visiblePhotos,
                initialIndex = visiblePhotos.indexOf(selected).coerceAtLeast(0),
                onClose = { viewerPhoto = null },
                onSave = { file ->
                    if (
                        Build.VERSION.SDK_INT <= 28 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingSaveFile = file
                        galleryWriteLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        showMessage(
                            context.getString(
                                if (saveToGallery(context, file)) R.string.saved_to_gallery
                                else R.string.save_failed
                            )
                        )
                    }
                },
                onDelete = { file ->
                    val ok = file.delete()
                    if (ok) {
                        // 删除的是最后一张（或已删空）才退出查看，否则列表顺延自动切到下一张
                        val wasLast = photoFiles.lastOrNull() == file
                        photoFiles = loadPhotos(photosDir)
                        refreshKey += 1
                        if (wasLast || photoFiles.isEmpty()) {
                            viewerPhoto = null
                        }
                    }
                    showMessage(
                        context.getString(if (ok) R.string.deleted else R.string.delete_failed)
                    )
                }
            )
        }
    }

    // 应用内提示条：位于底部控制区上方
    toastMessage?.let { message ->
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xCC333333))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
    }

    if (showValidityDialog) {
        ValidityDialog(
            currentDays = validityDays,
            onSelect = { days ->
                validityDays = days
                ShimmerPrefs.setValidityDays(context, days)
                showValidityDialog = false
            },
            onDismiss = { showValidityDialog = false }
        )
    }

    if (showSettings) {
        SettingsPage(
            validityDays = validityDays,
            accessibilityEnabled = screenshotMode == ScreenshotMode.ACCESSIBILITY,
            onValidityClick = { showValidityDialog = true },
            onAccessibilityToggle = { enable ->
                if (enable) {
                    when {
                        Build.VERSION.SDK_INT < 30 -> showMessage(
                            context.getString(R.string.accessibility_sdk_too_old)
                        )
                        !ScreenshotAccessibilityService.isEnabled(context) -> {
                            showMessage(context.getString(R.string.accessibility_not_enabled))
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        else -> {
                            screenshotMode = ScreenshotMode.ACCESSIBILITY
                            ShimmerPrefs.setScreenshotMode(context, ScreenshotMode.ACCESSIBILITY)
                        }
                    }
                } else {
                    screenshotMode = ScreenshotMode.MEDIA_PROJECTION
                    ShimmerPrefs.setScreenshotMode(context, ScreenshotMode.MEDIA_PROJECTION)
                }
            },
            onClose = { showSettings = false }
        )
    }

    if (showAdjustDialog) {
        AdjustDialog(
            columns = gridColumns,
            showCamera = showCamera,
            showScreenshot = showScreenshot,
            onColumnsSelect = { columns ->
                gridColumns = columns
                ShimmerPrefs.setGridColumns(context, columns)
            },
            onCameraToggle = { checked ->
                showCamera = checked
                ShimmerPrefs.setShowCamera(context, checked)
            },
            onScreenshotToggle = { checked ->
                showScreenshot = checked
                ShimmerPrefs.setShowScreenshot(context, checked)
            },
            onDismiss = { showAdjustDialog = false }
        )
    }
}

/** 拍摄并保存照片到应用专属目录。目录内放置 .nomedia 文件，避免系统相册扫描。 */
private fun capturePhoto(
    context: Context,
    dir: File,
    imageCapture: ImageCapture,
    validityDays: Int,
    mainExecutor: Executor,
    onSaved: () -> Unit,
    onMessage: (String) -> Unit
) {
    if (!dir.exists()) {
        dir.mkdirs()
    }
    runCatching { File(dir, ".nomedia").writeText("") }

    val fileName = "IMG_" +
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) +
        "_${validityDays}d.jpg"
    val file = File(dir, fileName)

    imageCapture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        mainExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved()
                onMessage(context.getString(R.string.photo_saved))
            }

            override fun onError(exception: ImageCaptureException) {
                onMessage(context.getString(R.string.capture_failed, exception.message))
            }
        }
    )
}

/** 读取应用相册目录下的照片，按拍摄时间倒序。 */
private fun loadPhotos(dir: File): List<File> =
    dir.listFiles { file -> file.isFile && file.extension.equals("jpg", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

/** 把拍摄时间格式化为 yyyy-MM-dd HH:mm。 */
private fun formatCaptureTime(captureTime: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(captureTime))

/**
 * 保存到手机相册：
 * Android 10+ 通过 MediaStore 写入系统相册（无需权限）；
 * Android 9 及以下复制到公共 Pictures 目录并触发媒体扫描（需存储权限）。
 */
private fun saveToGallery(context: Context, file: File): Boolean {
    return if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Shimmer"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    } else {
        @Suppress("DEPRECATION")
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Shimmer"
        )
        if (!destDir.exists() && !destDir.mkdirs()) return false
        val dest = File(destDir, file.name)
        return try {
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(dest.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** 相册页：三列网格展示已拍摄照片。 */
@Composable
private fun GalleryPage(
    photos: List<File>,
    columns: Int,
    filteredOut: Boolean,
    onPhotoClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        if (photos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (filteredOut) R.string.gallery_filter_empty
                        else R.string.gallery_empty
                    ),
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = 2.dp,
                    top = 2.dp,
                    end = 2.dp,
                    bottom = 44.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(photos, key = { it.name }) { file ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clickable { onPhotoClick(file) }
                    ) {
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 顶部灰色半透明条：显示剩余保存天数
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Color.Gray.copy(alpha = 0.5f))
                                .padding(vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.remaining_days,
                                    PhotoExpiry.remainingDays(file)
                                ),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全屏查看照片：图片全尺寸展示，左右滑动切换；
 * 白底青字的顶栏（返回箭头 + 拍摄时间）和底栏（保存 / 删除）悬浮在图片上，
 * 点击图片收起或展开两栏。
 */
@Composable
private fun PhotoViewer(
    photos: List<File>,
    initialIndex: Int,
    onClose: () -> Unit,
    onSave: (File) -> Unit,
    onDelete: (File) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    var barsVisible by remember { mutableStateOf(true) }
    val currentFile = photos.getOrNull(pagerState.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 左右滑动切换图片，点击图片切换两栏显隐
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            AsyncImage(
                model = photos[page],
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { barsVisible = !barsVisible }
                    )
            )
        }

        if (barsVisible) {
            // 顶栏：左侧返回箭头，中间当前照片拍摄时间
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.White)
                    .statusBarsPadding()
                    .height(56.dp)
            ) {
                BackArrowButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                )
                Text(
                    text = formatCaptureTime(
                        currentFile?.let { PhotoExpiry.info(it).first } ?: 0L
                    ),
                    color = ShutterColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 底栏：保存到手机相册 / 从应用目录删除
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.White)
                    .navigationBarsPadding()
                    .height(64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ViewerActionButton(
                    text = stringResource(R.string.viewer_save),
                    onClick = { currentFile?.let(onSave) }
                )
                ViewerActionButton(
                    text = stringResource(R.string.viewer_delete),
                    onClick = { currentFile?.let(onDelete) }
                )
            }
        }
    }
}

/** 用 Canvas 绘制的返回箭头（左向圆角折线），不是字符。 */
@Composable
private fun BackArrowButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val strokeWidth = 2.5.dp.toPx()
            val path = Path().apply {
                moveTo(size.width * 0.66f, size.height * 0.16f)
                lineTo(size.width * 0.30f, size.height * 0.50f)
                lineTo(size.width * 0.66f, size.height * 0.84f)
            }
            drawPath(
                path = path,
                color = ShutterColor,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/** 用 Canvas 绘制的关闭按钮（X 形双斜线），不是字符。 */
@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val strokeWidth = 2.5.dp.toPx()
            val path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.22f)
                lineTo(size.width * 0.78f, size.height * 0.78f)
                moveTo(size.width * 0.78f, size.height * 0.22f)
                lineTo(size.width * 0.22f, size.height * 0.78f)
            }
            drawPath(
                path = path,
                color = Color(0xFF666666),
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/** 查看页底栏的胶囊按钮：浅青底 + 青色文字。 */
@Composable
private fun ViewerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ShutterColor.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            color = ShutterColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 居中的保存时长选择弹窗：右上角自绘关闭按钮，当前档高亮（无勾号）。 */
@Composable
private fun ValidityDialog(
    currentDays: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.validity_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF222222),
                        modifier = Modifier.weight(1f)
                    )
                    CloseButton(onClick = onDismiss)
                }
                Spacer(modifier = Modifier.height(16.dp))

                listOf(1, 7, 30).forEach { option ->
                    val selected = option == currentDays
                    val optionShape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(optionShape)
                            .background(
                                if (selected) ShutterColor.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(option) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.validity_days, option),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) ShutterColor else Color(0xFF333333)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/** 设置页：默认保存时长 + 无障碍截图开关（关闭即每次授权模式）。 */
@Composable
private fun SettingsPage(
    validityDays: Int,
    accessibilityEnabled: Boolean,
    onValidityClick: () -> Unit,
    onAccessibilityToggle: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        // 顶栏：返回箭头 + 标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .height(56.dp)
        ) {
            BackArrowButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
            )
            Text(
                text = stringResource(R.string.settings_title),
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 标题栏与内容区的分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFFE0E0E0))
        )

        // 默认保存时长
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .clickable(onClick = onValidityClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_validity_label),
                color = Color.Black,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.validity_days, validityDays),
                color = ShutterColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 无障碍截图开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_accessibility_label),
                    color = Color.Black,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (accessibilityEnabled) {
                            R.string.settings_accessibility_on
                        } else {
                            R.string.settings_accessibility_off
                        }
                    ),
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            }
            Switch(
                checked = accessibilityEnabled,
                onCheckedChange = onAccessibilityToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = ShutterColor,
                    checkedThumbColor = Color.White
                )
            )
        }
    }
}

/** 居中的调整弹窗：每行照片数量 + 显示内容（全部/仅相机/仅截屏）。 */
@Composable
private fun AdjustDialog(
    columns: Int,
    showCamera: Boolean,
    showScreenshot: Boolean,
    onColumnsSelect: (Int) -> Unit,
    onCameraToggle: (Boolean) -> Unit,
    onScreenshotToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.adjust_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF222222),
                        modifier = Modifier.weight(1f)
                    )
                    CloseButton(onClick = onDismiss)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 每行照片数量：标签在左，步进器在右
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.adjust_columns_label),
                        fontSize = 16.sp,
                        color = Color(0xFF333333),
                        modifier = Modifier.weight(1f)
                    )
                    StepperPill(columns = columns, onColumnsSelect = onColumnsSelect)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 显示内容：标签在左，选择胶囊在右
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.adjust_filter_label),
                        fontSize = 16.sp,
                        color = Color(0xFF333333),
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterPill(
                            text = stringResource(R.string.adjust_filter_camera),
                            selected = showCamera,
                            onClick = { onCameraToggle(!showCamera) }
                        )
                        FilterPill(
                            text = stringResource(R.string.adjust_filter_screenshot),
                            selected = showScreenshot,
                            onClick = { onScreenshotToggle(!showScreenshot) }
                        )
                    }
                }
            }
        }
    }
}

/** 每行照片数量的“- 数字 +”胶囊步进器，范围 2-6。 */
@Composable
private fun StepperPill(
    columns: Int,
    onColumnsSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(ShutterColor.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton(enabled = columns > 2, isMinus = true) {
            onColumnsSelect(columns - 1)
        }
        Text(
            text = columns.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = ShutterColor,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        StepButton(enabled = columns < 6, isMinus = false) {
            onColumnsSelect(columns + 1)
        }
    }
}

/** 步进器里的减号 / 加号按钮（Canvas 绘制，禁用时置灰）。 */
@Composable
private fun StepButton(
    enabled: Boolean,
    isMinus: Boolean,
    onClick: () -> Unit
) {
    val color = if (enabled) ShutterColor else Color(0xFFBBBBBB)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val strokeWidth = 3.dp.toPx()
            val path = Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.5f)
                lineTo(size.width * 0.85f, size.height * 0.5f)
                if (!isMinus) {
                    moveTo(size.width * 0.5f, size.height * 0.15f)
                    lineTo(size.width * 0.5f, size.height * 0.85f)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

/** 显示内容的选择胶囊：选中高亮样式与拍摄页按钮一致。 */
@Composable
private fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) ShutterColor.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = if (selected) ShutterColor else Color(0xFF666666),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PermissionHint(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.permission_needed),
            color = Color.Black,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.permission_retry))
        }
    }
}

/** 底部快门按钮：拍摄页为青色外圈 + 实心圆；相册页为青色全实心圆 + 白色十字加号。 */
@Composable
private fun ShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPlus: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        label = "shutterScale"
    )

    Box(
        modifier = modifier
            .size(84.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showPlus) {
            // 相册页：青色全实心圆 + 白色十字加号
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .background(color = ShutterColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(40.dp)) {
                    val strokeWidth = 4.dp.toPx()
                    val path = Path().apply {
                        moveTo(size.width * 0.5f, size.height * 0.15f)
                        lineTo(size.width * 0.5f, size.height * 0.85f)
                        moveTo(size.width * 0.15f, size.height * 0.5f)
                        lineTo(size.width * 0.85f, size.height * 0.5f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
        } else {
            // 拍摄页：外圈 + 实心圆
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .border(width = 4.dp, color = ShutterColor, shape = CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(color = ShutterColor, shape = CircleShape)
            )
        }
    }
}

private val ShutterColor = Color(0xFF00BCD4)
