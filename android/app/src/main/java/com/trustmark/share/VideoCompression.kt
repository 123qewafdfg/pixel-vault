package com.trustmark.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class VideoAnalysis(
    val supported: Boolean,
    val reason: String,
    val videoMime: String,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val videoBitrate: Int,
    val durationMs: Long,
    val audioDescription: String,
    val audioTrackCount: Int,
    val hardwareEncoderName: String?,
    val targetWidth: Int,
    val targetHeight: Int,
)

enum class VideoCompressionStage { ANALYZING, QUEUED, COMPRESSING, ADOPTED, ORIGINAL, FAILED, CANCELLED }

data class VideoCompressionUpdate(
    val taskId: String,
    val input: LocalMedia,
    val analysis: VideoAnalysis? = null,
    val stage: VideoCompressionStage,
    val progress: Int = 0,
    val output: LocalMedia? = null,
    val message: String = "",
)

data class VideoCompressionResult(val adopted: Boolean, val output: LocalMedia?, val reason: String)

interface VideoCompressor {
    fun analyze(input: LocalMedia): VideoAnalysis
    suspend fun compress(input: LocalMedia, output: File, analysis: VideoAnalysis, onProgress: (Int) -> Unit): VideoCompressionResult
    fun cancel()
}

private class UnsupportedVideoCompressor : VideoCompressor {
    override fun analyze(input: LocalMedia) = VideoAnalysis(false, "当前版本不包含视频压缩模块", "", 0, 0, 0f, 0, 0, "", 0, null, 0, 0)
    override suspend fun compress(input: LocalMedia, output: File, analysis: VideoAnalysis, onProgress: (Int) -> Unit) = VideoCompressionResult(false, null, analysis.reason)
    override fun cancel() = Unit
}

object VideoCompressionProvider {
    fun create(context: Context): VideoCompressor = runCatching {
        val type = Class.forName("com.trustmark.share.FullVideoCompressor")
        type.getConstructor(Context::class.java).newInstance(context.applicationContext) as VideoCompressor
    }.getOrElse { UnsupportedVideoCompressor() }
}

object VideoCompressionBus {
    private val mutable = MutableStateFlow<Map<String, VideoCompressionUpdate>>(emptyMap())
    private val dismissedInputPaths = mutableSetOf<String>()
    val updates: StateFlow<Map<String, VideoCompressionUpdate>> = mutable.asStateFlow()
    @Synchronized fun put(update: VideoCompressionUpdate) {
        if (update.input.file.absolutePath !in dismissedInputPaths) mutable.value = mutable.value + (update.taskId to update)
    }
    @Synchronized fun allow(inputPaths: Collection<String>) { dismissedInputPaths.removeAll(inputPaths.toSet()) }
    @Synchronized fun dismiss(inputPath: String) {
        dismissedInputPaths += inputPath
        mutable.value = mutable.value.filterValues { it.input.file.absolutePath != inputPath }
    }
    @Synchronized fun clear() {
        dismissedInputPaths += mutable.value.values.map { it.input.file.absolutePath }
        mutable.value = emptyMap()
    }
}

class VideoCompressionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var queueJob: Job? = null
    private var compressor: VideoCompressor? = null
    private var currentOutput: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            VideoCompressionBus.updates.value.values.filter {
                it.stage == VideoCompressionStage.ANALYZING || it.stage == VideoCompressionStage.QUEUED || it.stage == VideoCompressionStage.COMPRESSING
            }.forEach { VideoCompressionBus.put(it.copy(stage = VideoCompressionStage.CANCELLED, message = "已取消，继续使用原视频")) }
            compressor?.cancel(); queueJob?.cancel(); currentOutput?.delete(); stopSelf()
            return START_NOT_STICKY
        }
        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val names = intent?.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val mimes = intent?.getStringArrayListExtra(EXTRA_MIMES).orEmpty()
        if (paths.isEmpty() || paths.size != names.size || paths.size != mimes.size) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification("正在准备视频压缩", 0))
        if (queueJob?.isActive == true) return START_NOT_STICKY
        queueJob = scope.launch {
            val engine = VideoCompressionProvider.create(this@VideoCompressionService).also { compressor = it }
            try {
                paths.indices.forEach { index ->
                    val input = LocalMedia(File(paths[index]), names[index], mimes[index])
                    val taskId = UUID.randomUUID().toString()
                    VideoCompressionBus.put(VideoCompressionUpdate(taskId, input, stage = VideoCompressionStage.ANALYZING, message = "正在分析"))
                    val analysis = engine.analyze(input)
                    if (!analysis.supported) {
                        VideoCompressionBus.put(VideoCompressionUpdate(taskId, input, analysis, VideoCompressionStage.ORIGINAL, message = analysis.reason))
                        return@forEach
                    }
                    val outputDir = File(cacheDir, "video-hevc").apply { mkdirs() }
                    val output = File(outputDir, "hevc-${System.nanoTime()}.mp4").also { currentOutput = it }
                    VideoCompressionBus.put(VideoCompressionUpdate(taskId, input, analysis, VideoCompressionStage.COMPRESSING, message = "硬件 H.265 压缩中"))
                    updateNotification("正在压缩 ${input.displayName}", 0)
                    val result = engine.compress(input, output, analysis) { value ->
                        VideoCompressionBus.put(VideoCompressionUpdate(taskId, input, analysis, VideoCompressionStage.COMPRESSING, value, message = "硬件 H.265 压缩中"))
                        updateNotification("正在压缩 ${input.displayName}", value)
                    }
                    if (result.adopted && result.output != null) {
                        VideoCompressionBus.put(VideoCompressionUpdate(taskId, input, analysis, VideoCompressionStage.ADOPTED, 100, result.output, result.reason))
                    } else {
                        output.delete()
                        VideoCompressionBus.put(VideoCompressionUpdate(taskId, input, analysis, VideoCompressionStage.ORIGINAL, 100, message = result.reason))
                    }
                    currentOutput = null
                }
            } catch (_: CancellationException) {
                currentOutput?.delete()
            } catch (failure: Throwable) {
                currentOutput?.delete()
                val active = VideoCompressionBus.updates.value.values.lastOrNull { it.stage == VideoCompressionStage.COMPRESSING || it.stage == VideoCompressionStage.ANALYZING }
                if (active != null) VideoCompressionBus.put(active.copy(stage = VideoCompressionStage.FAILED, message = failure.message ?: "视频压缩失败"))
            } finally {
                compressor = null; currentOutput = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(text: String, progress: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text, progress))
    }

    private fun notification(text: String, progress: Int): android.app.Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "视频压缩", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 1, Intent(this, VideoCompressionService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("隐像 · 视频压缩")
            .setContentText(text).setOnlyAlertOnce(true).setOngoing(true).setProgress(100, progress.coerceIn(0, 100), false)
            .addAction(0, "取消", cancel).build()
    }

    override fun onDestroy() { compressor?.cancel(); queueJob?.cancel(); currentOutput?.delete(); scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL_ID = "video-compression"
        private const val NOTIFICATION_ID = 3402
        private const val ACTION_CANCEL = "com.trustmark.share.CANCEL_VIDEO_COMPRESSION"
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_MIMES = "mimes"

        fun enqueue(context: Context, items: List<LocalMedia>) {
            val videos = items.filter { it.isVideo }
            if (videos.isEmpty()) return
            VideoCompressionBus.allow(videos.map { it.file.absolutePath })
            val intent = Intent(context, VideoCompressionService::class.java)
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(videos.map { it.file.absolutePath }))
                .putStringArrayListExtra(EXTRA_NAMES, ArrayList(videos.map { it.displayName }))
                .putStringArrayListExtra(EXTRA_MIMES, ArrayList(videos.map { it.mime }))
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, VideoCompressionService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
