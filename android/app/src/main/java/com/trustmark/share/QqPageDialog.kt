package com.trustmark.share

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.net.URI

@Composable
fun QqPageDialog(url: String, onImages: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("正在打开 QQ 官方帖子。若页面要求验证，请自行完成后点击提取原图。") }
    var closed by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
    }
    val latestImages by rememberUpdatedState(onImages)
    fun readState() {
        if (closed || reading) return
        val page = runCatching { URI(webView.url.orEmpty()) }.getOrNull()
        if (page?.scheme != "https" || page.host != "pd.qq.com") { status="请返回 QQ 频道公开帖子页面";return }
        reading = true
        webView.evaluateJavascript("JSON.stringify(window.__NUXT__ || null)") { raw ->
            if (!closed) scope.launch {
                val images = withContext(Dispatchers.IO) {
                    runCatching {
                        require(raw.length <= 4*1024*1024)
                        val json = JSONTokener(raw).nextValue() as? String ?: "null"
                        QqChannelClient.parseState(json)
                    }.getOrDefault(emptyList())
                }
                reading=false
                if (images.isNotEmpty()) { closed=true; latestImages(images) }
                else status="原图尚未就绪。请完成页面验证后重试；已删除、非公开或无图片的帖子无法提取。"
            }
        }
    }
    DisposableEffect(webView) {
        webView.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:android.webkit.WebResourceRequest):Boolean {
                val target=request.url
                return target.scheme!="https" || target.host!="pd.qq.com"
            }
            override fun onPageFinished(view:WebView,url:String){if(!closed)readState()}
        }
        webView.loadUrl(url)
        onDispose { closed=true; webView.stopLoading(); webView.destroy() }
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton(onDismiss){Text("取消")}
                    Button(onClick=::readState,enabled=!reading){Text(if(reading)"读取中" else "提取原图")}
                }
                Text(status,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
                AndroidView(factory={webView},modifier=Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
