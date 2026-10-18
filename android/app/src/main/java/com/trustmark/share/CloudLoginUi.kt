package com.trustmark.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.ui.login.*
import com.yunx.app.ui.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private inline fun <reified T : ViewModel> accountModel(key: String, crossinline create: () -> T): T =
    viewModel(key = "cloud-$key", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
    })

@Composable
fun CloudLoginDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val drive = remember { CloudDriveService.get(context) }
    var selected by remember { mutableStateOf<SharePlatform?>(null) }
    var xunleiWeb by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Map<SharePlatform, Boolean>>(emptyMap()) }
    var revision by remember { mutableIntStateOf(0) }
    val platforms = remember { SharePlatform.entries.filter { it != SharePlatform.GITHUB } }
    LaunchedEffect(revision) { status = withContext(Dispatchers.IO) { platforms.associateWith { drive.loggedIn(it) } } }
    fun back() { selected = null; xunleiWeb = false; revision++ }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            BackHandler { if (selected == null) onDismiss() else back() }
            when (selected) {
                null -> Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("网盘登录", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onDismiss) { Text("关闭") }
                    }
                    Text("仅使用对应网盘分享时需要登录；已有登录状态会保留。", style = MaterialTheme.typography.bodyMedium)
                    platforms.forEach { platform ->
                        OutlinedButton(onClick = { selected = platform }, modifier = Modifier.fillMaxWidth()) {
                            Text(CloudDriveService.label(platform), Modifier.weight(1f))
                            Text(if (status[platform] == true) "已登录 · 管理" else "登录")
                        }
                    }
                    Text("网盘由你自行上传。账号凭据仅加密保存在本设备。", style = MaterialTheme.typography.bodySmall)
                    Text("网盘适配来自 YunX（GNU AGPL-3.0），对应源码随本版本提供。", style = MaterialTheme.typography.bodySmall)
                }
                SharePlatform.QUARK -> QuarkLoginScreen(accountModel("quark") { QuarkAccountViewModel(drive.quark) }, ::back, ::back)
                SharePlatform.UC -> UCLoginScreen(accountModel("uc") { UCAccountViewModel(drive.uc) }, ::back, ::back)
                SharePlatform.XUNLEI -> {
                    val model = accountModel("xunlei") { XunleiAccountViewModel(drive.xunlei) }
                    if (xunleiWeb) XunleiWebLoginScreen(model, { xunleiWeb = false }, ::back)
                    else XunleiLoginScreen(model, ::back, ::back, onWebLogin = { xunleiWeb = true })
                }
                SharePlatform.BAIDU -> BaiduLoginScreen(accountModel("baidu") { BaiduAccountViewModel(drive.baidu) }, ::back, ::back)
                SharePlatform.C139 -> C139LoginScreen(accountModel("c139") { C139AccountViewModel(drive.c139) }, ::back, ::back)
                SharePlatform.PAN123 -> Pan123LoginScreen(accountModel("123") { Pan123AccountViewModel(drive.pan123) }, ::back, ::back)
                SharePlatform.PAN115 -> Pan115LoginScreen(accountModel("115") { Pan115AccountViewModel(drive.pan115) }, ::back, ::back)
                SharePlatform.GUANGYA -> GuangYaLoginScreen(accountModel("guangya") { GuangYaAccountViewModel(drive.guangya) }, ::back, ::back)
                SharePlatform.ILANZOU -> ILanzouLoginScreen(accountModel("ilanzou") { ILanzouAccountViewModel(drive.ilanzou) }, ::back, ::back)
                SharePlatform.LANZOU -> LanzouLoginScreen(accountModel("lanzou") { LanzouAccountViewModel(drive.lanzou) }, ::back, ::back)
                SharePlatform.GITHUB -> Unit
            }
        }
    }
}
