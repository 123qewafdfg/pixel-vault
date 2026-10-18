package com.trustmark.share

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class FullVideoCompressor(context: Context) : VideoCompressor {
    private val appContext = context.applicationContext
    @Volatile private var activeTransformer: Transformer? = null

    override fun analyze(input: LocalMedia): VideoAnalysis {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.file.absolutePath)
            val videoTracks = mutableListOf<MediaFormat>()
            val audioTracks = mutableListOf<MediaFormat>()
            var unsupportedTracks = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                when (format.getString(MediaFormat.KEY_MIME).orEmpty().substringBefore('/')) {
                    "video" -> videoTracks += format
                    "audio" -> audioTracks += format
                    else -> unsupportedTracks++
                }
            }
            val video = videoTracks.singleOrNull()
                ?: return unsupported("需要且只能包含一条视频轨", videoTracks.size, audioTracks.size)
            val mime = video.getString(MediaFormat.KEY_MIME).orEmpty()
            val rawWidth = video.int(MediaFormat.KEY_WIDTH)
            val rawHeight = video.int(MediaFormat.KEY_HEIGHT)
            val rotation = video.int(MediaFormat.KEY_ROTATION).let { if (it == Int.MIN_VALUE) 0 else it }
            val width = if (rotation % 180 == 0) rawWidth else rawHeight
            val height = if (rotation % 180 == 0) rawHeight else rawWidth
            val frameRate = video.number(MediaFormat.KEY_FRAME_RATE).toFloat().coerceAtLeast(0f)
            val bitrate = video.int(MediaFormat.KEY_BIT_RATE).coerceAtLeast(0)
            val durationMs = video.long(MediaFormat.KEY_DURATION).coerceAtLeast(0L) / 1000
            val profile = video.int(MediaFormat.KEY_PROFILE)
            val colorTransfer = video.int(MediaFormat.KEY_COLOR_TRANSFER)
            val colorStandard = video.int(MediaFormat.KEY_COLOR_STANDARD)
            val hdr = colorTransfer in setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG) ||
                colorStandard == MediaFormat.COLOR_STANDARD_BT2020
            val tenBit = profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
            val target = targetSize(width, height)
            val encoder = hardwareEncoder(target.first, target.second)
            val audioDescription = audioTracks.singleOrNull()?.let { audio ->
                val audioMime = audio.getString(MediaFormat.KEY_MIME).orEmpty()
                val channels = audio.int(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(0)
                "$audioMime · ${channels}声道"
            } ?: "无音轨"
            val reason = when {
                mime == MimeTypes.VIDEO_H265 -> "视频已经是 H.265，继续使用原视频"
                mime == MimeTypes.VIDEO_AV1 -> "首版不压缩 AV1 视频"
                mime != MimeTypes.VIDEO_H264 -> "首版只处理 H.264/AVC 视频"
                videoTracks.size != 1 -> "首版只处理单视频轨"
                audioTracks.size > 1 -> "检测到多音轨，继续使用原视频"
                unsupportedTracks > 0 -> "检测到字幕或其他轨道，继续使用原视频"
                hdr || tenBit -> "检测到 HDR、BT.2020 或 10-bit，继续使用原视频"
                width <= 0 || height <= 0 || durationMs <= 0 -> "无法可靠读取视频尺寸或时长"
                encoder == null -> "设备没有满足目标规格的硬件 H.265 VBR 编码器"
                else -> ""
            }
            VideoAnalysis(reason.isEmpty(), reason, mime, width, height, frameRate, bitrate, durationMs,
                audioDescription, audioTracks.size, encoder?.name, target.first, target.second)
        } catch (failure: Throwable) {
            unsupported("视频无法安全分析：${failure.message ?: "格式异常"}", 0, 0)
        } finally { extractor.release() }
    }

    override suspend fun compress(input: LocalMedia, output: File, analysis: VideoAnalysis, onProgress: (Int) -> Unit): VideoCompressionResult {
        if (!analysis.supported) return VideoCompressionResult(false, null, analysis.reason)
        output.parentFile?.mkdirs(); output.delete()
        val encoder = hardwareEncoder(analysis.targetWidth, analysis.targetHeight)
            ?: return VideoCompressionResult(false, null, "硬件 H.265 编码器已不可用")
        return try {
            val export = withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<ExportResult> { continuation ->
                    val settings = VideoEncoderSettings.Builder()
                        .setBitrate(3_000_000)
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .build()
                    val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                        .setVideoEncoderSelector(EncoderSelector { mime ->
                            if (mime == MimeTypes.VIDEO_H265) ImmutableList.of(encoder) else EncoderSelector.DEFAULT.selectEncoderInfos(mime)
                        })
                        .setRequestedVideoEncoderSettings(settings)
                        .setEnableFallback(false)
                        .setEnableFormatFallback(false)
                        .build()
                    val effects = if (analysis.targetWidth != analysis.width || analysis.targetHeight != analysis.height) {
                        Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
                            analysis.targetWidth, analysis.targetHeight, Presentation.LAYOUT_SCALE_TO_FIT)))
                    } else Effects.EMPTY
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input.file))).setEffects(effects).build()
                    lateinit var transformer: Transformer
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            activeTransformer = null
                            if (continuation.isActive) continuation.resume(exportResult)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            activeTransformer = null
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }
                    transformer = Transformer.Builder(appContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H265)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setPortraitEncodingEnabled(true)
                        .setEncoderFactory(encoderFactory)
                        .addListener(listener)
                        .build()
                    activeTransformer = transformer
                    val progressJob = launch {
                        val holder = ProgressHolder()
                        while (isActive && continuation.isActive) {
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                            delay(350)
                        }
                    }
                    continuation.invokeOnCancellation { progressJob.cancel(); transformer.cancel(); output.delete() }
                    transformer.start(edited, output.absolutePath)
                }
            }
            withContext(Dispatchers.IO) { validateOutput(input, output, analysis, export) }
        } catch (failure: Throwable) {
            output.delete()
            VideoCompressionResult(false, null, "压缩失败，继续使用原视频：${failure.message ?: "编码器错误"}")
        }
    }

    override fun cancel() { activeTransformer?.cancel(); activeTransformer = null }

    private fun validateOutput(input: LocalMedia, output: File, source: VideoAnalysis, export: ExportResult): VideoCompressionResult {
        if (!output.isFile || output.length() <= 0) return VideoCompressionResult(false, null, "压缩输出为空")
        val checked = analyze(LocalMedia(output, output.name, "video/mp4"))
        val durationTolerance = maxOf(750L, source.durationMs / 100)
        val outputLength = output.length()
        val valid = checked.videoMime == MimeTypes.VIDEO_H265 &&
            abs(checked.durationMs - source.durationMs) <= durationTolerance &&
            checked.width == source.targetWidth && checked.height == source.targetHeight &&
            (source.frameRate <= 0f || checked.frameRate <= 0f || abs(checked.frameRate - source.frameRate) <= 1f) &&
            checked.audioTrackCount == source.audioTrackCount && outputLength * 10 < input.file.length() * 9 &&
            export.videoEncoderName == source.hardwareEncoderName
        if (!valid) {
            output.delete()
            val reason = when {
                outputLength * 10 >= input.file.length() * 9 -> "压缩后未小于原文件的 90%"
                checked.audioTrackCount != source.audioTrackCount -> "压缩结果音轨缺失"
                checked.videoMime != MimeTypes.VIDEO_H265 -> "压缩结果不是 H.265"
                else -> "压缩结果的时长、尺寸、帧率或硬件编码器不符合要求"
            }
            return VideoCompressionResult(false, null, reason)
        }
        val saved = 100 - (output.length() * 100.0 / input.file.length()).roundToInt()
        val media = LocalMedia(output, input.displayName.substringBeforeLast('.', input.displayName) + "-H265.mp4", "video/mp4", input.file.length())
        return VideoCompressionResult(true, media, "H.265 临时副本 · 节省 $saved% · 硬件 ${export.videoEncoderName}")
    }

    private fun hardwareEncoder(width: Int, height: Int): MediaCodecInfo? = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
        info.isEncoder && !software(info) && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, true) } && runCatching {
            val capabilities = info.getCapabilitiesForType(MimeTypes.VIDEO_H265)
            val encoderCapabilities = capabilities.encoderCapabilities ?: return@runCatching false
            val videoCapabilities = capabilities.videoCapabilities ?: return@runCatching false
            encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) &&
                videoCapabilities.isSizeSupported(width, height) && videoCapabilities.bitrateRange.contains(3_000_000)
        }.getOrDefault(false)
    }

    private fun software(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else {
        val name = info.name.lowercase()
        name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains("software") || name.contains("sw.")
    }

    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val maxWidth = if (width >= height) 1920 else 1080
        val maxHeight = if (width >= height) 1080 else 1920
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        fun even(value: Double) = maxOf(2, (value.roundToInt() / 2) * 2)
        return even(width * scale) to even(height * scale)
    }

    private fun unsupported(reason: String, videoTracks: Int, audioTracks: Int) = VideoAnalysis(
        false, reason, "", 0, 0, 0f, 0, 0, if (audioTracks > 0) "$audioTracks 条音轨" else "无音轨",
        audioTracks, null, 0, 0)

    private fun MediaFormat.int(key: String): Int = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(Int.MIN_VALUE) else Int.MIN_VALUE
    private fun MediaFormat.long(key: String): Long = if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L
    private fun MediaFormat.number(key: String): Number = if (containsKey(key)) runCatching { getNumber(key) ?: 0 }.getOrDefault(0) else 0
}
