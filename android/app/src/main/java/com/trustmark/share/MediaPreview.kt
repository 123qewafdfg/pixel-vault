package com.trustmark.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import kotlinx.coroutines.launch
import java.nio.charset.Charset

private const val MAX_TEXT_PREVIEW_BYTES = 2 * 1024 * 1024

@Composable
fun MediaPreviewDialog(item: LocalMedia, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val imageLoader = rememberPreviewImageLoader()
    var openError by remember(item.file) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.86f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.displayName, Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                when {
                    item.isImage -> ZoomImage(item, imageLoader, Modifier.weight(1f))
                    item.isVideo || item.isAudio -> MediaPlayer(item, Modifier.weight(1f))
                    item.isText -> TextPreview(item, Modifier.weight(1f))
                    else -> Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("此格式不支持软件内预览", style = MaterialTheme.typography.titleMedium)
                        Text(item.mime, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, item.mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "用其他应用打开"))
                            }.onFailure { openError = "手机中没有支持此格式的应用" }
                        }) { Text("使用其他应用打开") }
                        openError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageGalleryDialog(items: List<LocalMedia>, initialIndex: Int, onDismiss: () -> Unit) {
    val images = remember(items) { items.filter { it.isImage } }
    if (images.isEmpty()) return
    val start = initialIndex.coerceIn(0, images.lastIndex)
    val pager = rememberPagerState(initialPage = start, pageCount = { images.size })
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(images[pager.currentPage].displayName, Modifier.weight(1f), maxLines = 1)
                    Text("${pager.currentPage + 1} / ${images.size}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    ZoomImage(images[page], rememberPreviewImageLoader(), Modifier.fillMaxSize())
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("上一张") }
                    Button(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        enabled = pager.currentPage < images.lastIndex,
                        modifier = Modifier.weight(1f),
                    ) { Text("下一张") }
                }
            }
        }
    }
}

@Composable private fun ZoomImage(item: LocalMedia, imageLoader: ImageLoader, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }; var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 8f); offset += pan }
    Box(modifier.fillMaxWidth().background(Color.Black).transformable(state), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = item.file,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable private fun MediaPlayer(item: LocalMedia, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file))); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, modifier.background(Color.Black))
}

@Composable private fun TextPreview(item: LocalMedia, modifier: Modifier) {
    val result = remember(item.file) { runCatching { readTextPreview(item) } }
    val text = result.getOrElse { it.message ?: "无法读取文本" }
    Text(text, modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), color = if (result.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
}

private fun readTextPreview(item: LocalMedia): String {
    require(item.file.length() <= MAX_TEXT_PREVIEW_BYTES) { "文本超过 2 MB，请保存后使用其他应用打开" }
    val bytes = item.file.readBytes()
    val utf8 = bytes.toString(Charsets.UTF_8)
    return if ('\uFFFD' in utf8) bytes.toString(Charset.forName("GB18030")) else utf8
}

@Composable private fun rememberPreviewImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        ImageLoader.Builder(context).components {
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
}
