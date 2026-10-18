package com.trustmark.share

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val LiteGreen = Color(0xFF25A371)

class LiteMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CloudDriveService.install(this)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
        Thread { runCatching { CacheManager.maintain(this) } }.start()
        setContent { LiteApp() }
    }
}

@Composable
private fun LiteApp() {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF67C89E), background = Color(0xFF0E1512), surface = Color(0xFF18211D),
    ) else lightColorScheme(
        primary = LiteGreen, background = Color(0xFFF5F7F4), surface = Color.White,
    )
    var extracting by remember { mutableStateOf(false) }
    var showCloud by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = colors) {
        BackHandler(extracting) { extracting = false }
        if (extracting) LiteExtractPage { extracting = false }
        else LiteHome(onExtract = { extracting = true }, onCloud = { showCloud = true })
        if (showCloud) CloudLoginDialog { showCloud = false }
    }
}

@Composable
private fun LiteHome(onExtract: () -> Unit, onCloud: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.size(210.dp).align(Alignment.TopEnd).offset(x = 95.dp, y = (-65).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("隐像", fontSize = 38.sp, fontWeight = FontWeight.Black)
            Text("轻量提取版", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(46.dp))
            Row(
                Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onExtract).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("提取", color = MaterialTheme.colorScheme.onPrimary, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("图片识别或口令恢复", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f), fontSize = 13.sp)
                }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                    Text("↗", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onCloud, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.Cloud, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("网盘登录", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text("仅保留提取功能 · 不含嵌入与 B站登录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

private data class LiteExtractResult(val label: String, val detail: String, val restored: RestoredFile? = null)

@Composable
private fun LiteExtractPage(back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { TrustMarkPlugin(context, true) }
    var selected by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var results by remember { mutableStateOf<List<LiteExtractResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<RestoredFile?>(null) }
    var preview by remember { mutableStateOf<LocalMedia?>(null) }

    DisposableEffect(Unit) {
        onDispose { Thread { CacheManager.clearTask(context, setOf("imports", "remote", "restored")) }.start() }
    }

    suspend fun restoreCode(code: String, folder: String): RestoredFile {
        val record = withContext(Dispatchers.IO) { NativeWorkflow.getRecord(code) }
        return withContext(Dispatchers.IO) {
            NativeWorkflow.restoreRemote(record, File(context.cacheDir, folder), context) { state ->
                val fraction = if (state.totalBytes > 0) state.downloadedBytes.toFloat() / state.totalBytes else 0f
                scope.launch {
                    progressLabel = "下载分片 ${state.partIndex + 1}/${state.partCount}"
                    progress = (.35f + .6f * ((state.partIndex + fraction) / state.partCount.coerceAtLeast(1))).coerceIn(0f, .96f)
                }
            }
        }
    }

    fun restoreManual() {
        val code = PasscodeCodec.normalize(manualCode)
        if (!PasscodeCodec.isCurrent(code)) { error = "口令码必须是 5 个英文字母"; return }
        scope.launch {
            busy = true; error = ""; progress = .08f; progressLabel = "正在连接服务器"
            runCatching { restoreCode(code, "lite-manual-${System.nanoTime()}") }
                .onSuccess { PasscodeHistory.add(context, PasscodeHistoryKind.EXTRACT, code); results = listOf(LiteExtractResult(code, "恢复成功 · ${it.name}", it)); opened = it; progress = 1f }
                .onFailure { error = it.message?.takeIf(String::isNotBlank) ?: "提取失败" }
            delay(150); busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; error = ""; results = emptyList(); progress = .03f; progressLabel = "正在导入图片"
            val imported = runCatching { withContext(Dispatchers.IO) { uris.map { MediaFiles.import(context, it) } } }
                .getOrElse { error = it.message ?: "导入图片失败"; busy = false; return@launch }
            selected = imported
            val completed = mutableListOf<LiteExtractResult>()
            imported.forEachIndexed { index, image ->
                progressLabel = "正在识别第 ${index + 1}/${imported.size} 张图片"
                progress = .08f + .86f * index / imported.size.coerceAtLeast(1)
                val outcome = runCatching {
                    val packet = withContext(Dispatchers.IO) { TrustMarkCodec.decode(bridge.extract(decodeLiteBitmap(image))).first }
                    val candidates = PasscodeCodec.decodeCandidates(packet)
                    var restored: RestoredFile? = null
                    var matched = ""
                    var reason = "服务器未响应"
                    for (candidate in candidates) {
                        val attempt = runCatching { restoreCode(candidate, "lite-remote-${System.nanoTime()}") }
                        attempt.onSuccess { restored = it; matched = candidate }.onFailure { reason = it.message ?: reason }
                        if (restored != null) break
                    }
                    requireNotNull(restored) { reason }.also { PasscodeHistory.add(context, PasscodeHistoryKind.EXTRACT, matched) }
                }
                completed += outcome.fold(
                    onSuccess = { LiteExtractResult(image.displayName, "恢复成功 · ${it.name}", it) },
                    onFailure = { LiteExtractResult(image.displayName, it.message?.takeIf(String::isNotBlank) ?: "未提取到有效内容") },
                )
                results = completed.toList()
            }
            progress = 1f; progressLabel = "处理完成"; delay(150); busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { Text("← 返回") }
                Text("提取", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(72.dp))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("导入含盲水印的图片", fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text("选择后自动识别口令并恢复隐藏文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { picker.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                    Text(if (selected.isEmpty()) "选择一张或多张图片" else "重新选择图片", fontWeight = FontWeight.Bold)
                }
                selected.forEach { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { preview = item }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(item.file, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp)); Text(item.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (busy) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("$progressLabel ${(progress * 100).roundToInt()}%", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp)).padding(14.dp))
                HorizontalDivider()
                Text("也可以直接输入口令", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it.filter(Char::isLetter).take(5).uppercase() },
                        modifier = Modifier.weight(1f), label = { Text("5位口令码") }, placeholder = { Text("ABCDE") }, singleLine = true, enabled = !busy,
                    )
                    Button(onClick = ::restoreManual, enabled = !busy && PasscodeCodec.isCurrent(manualCode), modifier = Modifier.height(56.dp)) { Text("提取") }
                }
                results.forEach { result ->
                    Card(onClick = { result.restored?.let { opened = it } }, enabled = result.restored != null, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(result.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(result.detail, color = if (result.restored != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
    preview?.let { MediaPreviewDialog(it) { preview = null } }
    opened?.let { LiteRecoveredDialog(it, onPreview = { preview = it }, onDismiss = { opened = null }) }
}

@Composable
private fun LiteRecoveredDialog(restored: RestoredFile, onPreview: (LocalMedia) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember(restored) { LocalMedia(restored.file, restored.name, restored.mime) }
    val items = remember(restored) { restored.children.ifEmpty { listOf(root) } }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(.76f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("提取结果", fontSize = 21.sp, fontWeight = FontWeight.Black); Text("${items.size} 个文件", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onDismiss) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onPreview(item) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(item.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(liteSize(item.file.length()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Icon(Icons.Rounded.Download, contentDescription = null)
                        }
                    }
                }
                Button(onClick = {
                    scope.launch { saving = true; runCatching { withContext(Dispatchers.IO) { items.forEach { MediaFiles.save(context, it.file, it.displayName, it.mime) } } }.onSuccess { saved = true }; saving = false }
                }, enabled = !saving && !saved, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "已保存" else if (saving) "正在保存…" else "全部保存") }
            }
        }
    }
}

private fun decodeLiteBitmap(item: LocalMedia) = item.file.inputStream().use { requireNotNull(BitmapFactory.decodeStream(it)) { "无法解码图片" } }
private fun liteSize(bytes: Long) = when { bytes < 1024 -> "$bytes B"; bytes < 1048576 -> "%.1f KiB".format(bytes / 1024.0); else -> "%.2f MiB".format(bytes / 1048576.0) }
