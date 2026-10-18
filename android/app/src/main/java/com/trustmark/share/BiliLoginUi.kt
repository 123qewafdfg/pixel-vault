package com.trustmark.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BiliLoginDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session = remember { BiliSession.get(context) }
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val featurePrefs = remember { context.getSharedPreferences("bili_features", Context.MODE_PRIVATE) }
    var longLinkEnabled by remember { mutableStateOf(featurePrefs.getBoolean("long_link_enabled", false)) }
    var showExplanation by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<BiliAccount?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf("正在检查登录状态") }
    var saving by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    fun save() {
        val bitmap = image ?: return
        scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) {
                    val file = File.createTempFile("bili-qr-", ".png", context.cacheDir)
                    try {
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        MediaFiles.save(context, file, "B站登录二维码.png", "image/png")
                    } finally { file.delete() }
                }
                message = "二维码已保存，请使用 B站客户端扫一扫，选择相册中的二维码"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { message = "二维码保存失败，请检查相册权限" }
            finally { saving = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) save() else message = "保存二维码需要相册写入权限"
    }
    LaunchedEffect(refresh) {
        image = null; working = true; account = null
        try {
            if (session.verify()) {
                account = session.account(); message = "B站登录成功"; return@LaunchedEffect
            }
            message = "正在获取二维码"
            val qr = session.generate()
            image = withContext(Dispatchers.Default) {
                val matrix = MultiFormatWriter().encode(qr.url, BarcodeFormat.QR_CODE, 512, 512)
                Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                    val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                    setPixels(pixels, 0, 512, 0, 0, 512, 512)
                }
            }
            message = "请用 B站客户端扫码；也可以保存二维码后从相册扫描"
            working = false
            while (true) {
                delay(2_000)
                when (session.poll(qr.key)) {
                    BiliPoll.WAITING -> Unit
                    BiliPoll.SCANNED -> message = "已扫码，请在 B站客户端确认登录"
                    BiliPoll.CONFIRMED -> { account = session.account(); message = "B站登录成功"; image = null; break }
                    BiliPoll.EXPIRED -> { message = "二维码已过期，请刷新"; image = null; break }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            image = null
            message = if (state == BiliLoginState.UNVERIFIED) "暂时无法验证登录，请检查网络后重试" else "B站连接或登录校验失败，请刷新重试"
        } finally { working = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("B站登录") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message)
            account?.let { Text("账号等级 LV${it.level} · 今日剩余 ${BiliQuota.remaining(context, it)}/${BiliQuota.limit(it.level)} 次", style = MaterialTheme.typography.bodySmall) }
            image?.let { Image(it.asImageBitmap(), "B站登录二维码", Modifier.fillMaxWidth().aspectRatio(1f)) }
            if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (image != null) OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
            }, enabled = !saving) { Text(if (saving) "正在保存" else "保存二维码") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("确保直链长期可用", style = MaterialTheme.typography.titleSmall)
                    Text("开启后创建私密专栏；关闭仍可临时上传", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showExplanation = true }) { Text("？") }
                Switch(longLinkEnabled, { enabled ->
                    longLinkEnabled = enabled
                    featurePrefs.edit().putBoolean("long_link_enabled", enabled).apply()
                }, enabled = state == BiliLoginState.VERIFIED && (account?.level ?: 0) in 1..6)
            }
            Text("登录凭据仅加密保存在本机，不发送到本软件后端。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { refresh++ }, enabled = !working) { Text("刷新 / 核验") }
            if (state != BiliLoginState.LOGGED_OUT) TextButton(onClick = { scope.launch {
                try { session.logout(); longLinkEnabled = false; account = null; refresh++; message = "已退出本地登录" }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = "退出失败，请重试" }
            } }, enabled = !working) { Text("退出登录") }
        },
    )
    if (showExplanation) AlertDialog(
        onDismissRequest = { showExplanation = false },
        title = { Text("长期直链功能说明") },
        text = { Text("关闭时仍可把承载图片临时上传到 B站，但直链可能短期失效。开启后还会创建仅自己可见的专栏稿件；稿件仅自己可见，但持有图片直链的人仍可能访问图片，B站也不承诺永久保存。LV1–3 账号每天最多完成 1 次，LV4–6 每天最多 5 次，这是专栏平台限制。上传、投稿和可选校验都需要时间；使用代理或 VPN 可能导致结果不明。大文件耗时很长，建议改用网盘。如果你不清楚这些影响，请不要开启。") },
        confirmButton = { TextButton(onClick = { showExplanation = false }) { Text("我知道了") } },
    )
}
