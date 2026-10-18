package com.trustmark.share

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private val Blue = Color(0xFF3867EF)
private val Green = Color(0xFF25A371)
private val Ink = Color(0xFF16211D)
private val BluePage = Color(0xFFEDF2FF)
private val GreenPage = Color(0xFFE9F6EF)
private val DarkPage = Color(0xFF0E1512)
private const val LEGAL_VERSION = 7
private const val OFFICIAL_SITE_URL = "https://github.com/123qewafdfg/pixel-vault/releases/latest"
private val LocalThemeProgress=staticCompositionLocalOf{0f}

@Composable private fun adaptiveAccent(color:Color)=lerp(color,Color.White,LocalThemeProgress.current*.18f)

private fun blendSchemes(light:ColorScheme,dark:ColorScheme,t:Float)=light.copy(
    primary=lerp(light.primary,dark.primary,t),onPrimary=lerp(light.onPrimary,dark.onPrimary,t),
    primaryContainer=lerp(light.primaryContainer,dark.primaryContainer,t),onPrimaryContainer=lerp(light.onPrimaryContainer,dark.onPrimaryContainer,t),
    inversePrimary=lerp(light.inversePrimary,dark.inversePrimary,t),secondary=lerp(light.secondary,dark.secondary,t),onSecondary=lerp(light.onSecondary,dark.onSecondary,t),
    secondaryContainer=lerp(light.secondaryContainer,dark.secondaryContainer,t),onSecondaryContainer=lerp(light.onSecondaryContainer,dark.onSecondaryContainer,t),
    tertiary=lerp(light.tertiary,dark.tertiary,t),onTertiary=lerp(light.onTertiary,dark.onTertiary,t),tertiaryContainer=lerp(light.tertiaryContainer,dark.tertiaryContainer,t),onTertiaryContainer=lerp(light.onTertiaryContainer,dark.onTertiaryContainer,t),
    background=lerp(light.background,dark.background,t),onBackground=lerp(light.onBackground,dark.onBackground,t),surface=lerp(light.surface,dark.surface,t),onSurface=lerp(light.onSurface,dark.onSurface,t),
    surfaceVariant=lerp(light.surfaceVariant,dark.surfaceVariant,t),onSurfaceVariant=lerp(light.onSurfaceVariant,dark.onSurfaceVariant,t),surfaceTint=lerp(light.surfaceTint,dark.surfaceTint,t),
    inverseSurface=lerp(light.inverseSurface,dark.inverseSurface,t),inverseOnSurface=lerp(light.inverseOnSurface,dark.inverseOnSurface,t),
    error=lerp(light.error,dark.error,t),onError=lerp(light.onError,dark.onError,t),errorContainer=lerp(light.errorContainer,dark.errorContainer,t),onErrorContainer=lerp(light.onErrorContainer,dark.onErrorContainer,t),
    outline=lerp(light.outline,dark.outline,t),outlineVariant=lerp(light.outlineVariant,dark.outlineVariant,t),scrim=lerp(light.scrim,dark.scrim,t),
    surfaceBright=lerp(light.surfaceBright,dark.surfaceBright,t),surfaceDim=lerp(light.surfaceDim,dark.surfaceDim,t),surfaceContainer=lerp(light.surfaceContainer,dark.surfaceContainer,t),
    surfaceContainerHigh=lerp(light.surfaceContainerHigh,dark.surfaceContainerHigh,t),surfaceContainerHighest=lerp(light.surfaceContainerHighest,dark.surfaceContainerHighest,t),
    surfaceContainerLow=lerp(light.surfaceContainerLow,dark.surfaceContainerLow,t),surfaceContainerLowest=lerp(light.surfaceContainerLowest,dark.surfaceContainerLowest,t),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CloudDriveService.install(this)
        val startsDark = getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            .getBoolean("dark", false)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.setBackgroundColor(
            if (startsDark) android.graphics.Color.rgb(14, 21, 18)
            else android.graphics.Color.rgb(245, 247, 244),
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
        Thread { runCatching { CacheManager.maintain(this) } }.start()
        setContent { PixelVault() }
    }
}

private enum class Screen { HOME, EMBED, EXTRACT }

@Composable
private fun PixelVault() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    val legalPrefs = remember { context.getSharedPreferences("legal", android.content.Context.MODE_PRIVATE) }
    var legalAccepted by remember { mutableStateOf(legalPrefs.getInt("accepted_version", 0) >= LEGAL_VERSION) }
    var showLegal by remember { mutableStateOf(!legalAccepted) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    val animationScope = rememberCoroutineScope()
    var themeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val initialDark = remember { dark }
    val themeProgress = remember { Animatable(if(initialDark) 1f else 0f) }
    val latestDark by rememberUpdatedState(dark)
    var reordering by remember { mutableStateOf(false) }
    fun setDark(target: Boolean) {
        themeJob?.cancel()
        dark = target
        prefs.edit().putBoolean("dark", target).apply()
        themeJob = animationScope.launch { themeProgress.animateTo(if(target) 1f else 0f, tween(420)) }
    }
    val amount = themeProgress.value
    val pageAccent = if(screen==Screen.EXTRACT) Green else Blue
    val lightColors=lightColorScheme(primary=pageAccent,onPrimary=Color.White,background=Color(0xFFF5F7F4),onBackground=Ink,surface=Color.White,onSurface=Ink,surfaceVariant=Color(0xFFEDF1EE),onSurfaceVariant=Color(0xFF65716A),outline=Color(0xFFD7DDD8))
    val darkColors=darkColorScheme(primary=lerp(pageAccent,Color.White,.20f),onPrimary=Color(0xFF10204E),background=DarkPage,onBackground=Color(0xFFF2F7F3),surface=Color(0xFF18211D),onSurface=Color(0xFFF2F7F3),surfaceVariant=Color(0xFF25312B),onSurfaceVariant=Color(0xFFB8C5BD),outline=Color(0xFF48564E))
    val colors=blendSchemes(lightColors,darkColors,amount)
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalThemeProgress provides amount, LocalReorderInProgress provides { active ->
            reordering = active
            if(active) themeJob?.cancel()
        }){Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val origin = themeProgress.value
                val target = if(latestDark) 0f else 1f
                var committed = false
                themeJob?.cancel()
                themeJob = animationScope.launch {
                    delay(1000)
                    if (reordering) return@launch
                    committed = true
                    dark = target == 1f
                    prefs.edit().putBoolean("dark", dark).apply()
                    themeProgress.animateTo(target,tween(420))
                }
                var moved = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if(change != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    val pressed = event.changes.any { it.pressed }
                } while(pressed && !moved)
                if(!committed) {
                    themeJob?.cancel()
                    themeJob = animationScope.launch { themeProgress.animateTo(origin, tween(220)) }
                }
            }
        }) {
            when (screen) {
                Screen.HOME -> HomePage(open = { screen = it }, openLegal = { showLegal = true }, dark = dark, onDark = ::setDark, featuresEnabled = legalAccepted && !showLegal)
                Screen.EMBED -> if (BuildConfig.EXTRACT_ONLY) ExtractPage(dark) { screen = Screen.HOME } else EmbedPage(dark) { screen = Screen.HOME }
                Screen.EXTRACT -> ExtractPage(dark) { screen = Screen.HOME }
            }
        }}
        if (showLegal) LegalDialog(
            required = !legalAccepted,
            onAgree = {
                legalPrefs.edit().putInt("accepted_version", LEGAL_VERSION).apply()
                legalAccepted = true
                showLegal = false
            },
            onDismiss = { showLegal = false },
            onReject = { (context as? Activity)?.finishAffinity() },
        )

    }
}

@Composable
private fun HomePage(open: (Screen) -> Unit, openLegal: () -> Unit, dark: Boolean, onDark: (Boolean) -> Unit, featuresEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updatePrefs = remember { context.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var checking by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }
    var updateLabel by remember { mutableStateOf("") }
    var updateError by remember { mutableStateOf("") }
    var preparedUpdate by remember { mutableStateOf<File?>(null) }
    var skipThisVersion by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var clearingCache by remember { mutableStateOf(false) }
    var showCloudLogin by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBiliLogin by remember { mutableStateOf(false) }
    val biliSession = remember { if (BuildConfig.EXTRACT_ONLY) null else BiliSession.get(context) }
    val biliState = if (biliSession == null) BiliLoginState.LOGGED_OUT else biliSession.state.collectAsState().value
    val featurePrefs = remember { context.getSharedPreferences("feature_notices",android.content.Context.MODE_PRIVATE) }
    var showBiliNotice by remember { mutableStateOf(!BuildConfig.EXTRACT_ONLY && !featurePrefs.getBoolean("bili_upload_v1",false)) }
    LaunchedEffect(featuresEnabled) {
        if(!featuresEnabled || BuildConfig.EXTRACT_ONLY) return@LaunchedEffect
        try { requireNotNull(biliSession).verify() }
        catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(_:Exception){ /* State remains unverified; the login dialog explains network errors. */ }
    }
    fun check(manual: Boolean) {
        if (checking) return
        scope.launch {
            checking = true
            runCatching { withContext(Dispatchers.IO) { NativeWorkflow.checkUpdate() } }
                .onSuccess { info ->
                    val ignored = updatePrefs.getInt("ignored_version", -1)
                    if (info.versionCode > BuildConfig.VERSION_CODE && (manual || ignored != info.versionCode)) {
                        skipThisVersion = false
                        updateError = ""
                        preparedUpdate = null
                        update = info
                    } else if (manual) {
                        android.widget.Toast.makeText(context, "当前已是最新版", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure {
                    if (manual) android.widget.Toast.makeText(context, "暂时无法检查更新", android.widget.Toast.LENGTH_SHORT).show()
                }
            checking = false
        }
    }
    fun dismissUpdate(info: AppUpdate) {
        if (skipThisVersion) updatePrefs.edit().putInt("ignored_version", info.versionCode).apply()
        update = null
    }
    fun beginUpdate(info: AppUpdate, preferPatch: Boolean) {
        if (updating) return
        scope.launch {
            updating = true; updateError = ""; updateProgress = 0f
            runCatching {
                val ready = preparedUpdate ?: withContext(Dispatchers.IO) {
                    DeltaUpdater.prepare(context, info, preferPatch) { state ->
                        scope.launch { updateLabel = state.label; updateProgress = state.fraction }
                    }
                }.also { preparedUpdate = it }
                DeltaUpdater.install(context, ready)
            }.onFailure { error -> updateError = error.message ?: "更新失败，请稍后重试" }
            updating = false
        }
    }
    LaunchedEffect(Unit) { check(false) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { CacheManager.size(context) } }
    val homeBlue = adaptiveAccent(Blue)
    val homeGreen = adaptiveAccent(Green)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.size(220.dp).align(Alignment.TopEnd).offset(x=100.dp,y=(-70).dp).clip(CircleShape).background(homeBlue.copy(alpha=.08f)))
        Box(Modifier.size(190.dp).align(Alignment.BottomStart).offset(x=(-110).dp,y=100.dp).clip(CircleShape).background(homeGreen.copy(alpha=.07f)))
        if (BuildConfig.EXTRACT_ONLY) {
            Box(
                Modifier.fillMaxSize().widthIn(max=560.dp).align(Alignment.TopCenter)
                    .statusBarsPadding().navigationBarsPadding().padding(horizontal=22.dp),
            ) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth().padding(bottom=54.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                ) {
                    Text(
                        "一张图下，皆是秘密",
                        modifier=Modifier.fillMaxWidth(),
                        textAlign=TextAlign.Center,
                        fontSize=27.sp,
                        lineHeight=34.sp,
                        fontWeight=FontWeight.Bold,
                        letterSpacing=(-.5).sp,
                        color=MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(34.dp))
                    HomeAction("提取","↗",homeGreen) { open(Screen.EXTRACT) }
                    Spacer(Modifier.height(12.dp))
                    TraceCloudAction { showCloudLogin=true }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.72f))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical=8.dp),
                        horizontalArrangement=Arrangement.spacedBy(2.dp),
                        verticalAlignment=Alignment.CenterVertically,
                    ) {
                        TraceBottomAction(if(checking)"检查中…" else "检测更新",Modifier.weight(1f),enabled=!checking){check(true)}
                        TraceBottomAction("协议与隐私",Modifier.weight(1f),onClick=openLegal)
                        TraceBottomAction(if(clearingCache)"清理中…" else "清理缓存",Modifier.weight(1f),enabled=!clearingCache){
                            scope.launch {
                                clearingCache=true
                                val result=withContext(Dispatchers.IO){CacheManager.clear(context)}
                                cacheBytes=result.after
                                clearingCache=false
                                android.widget.Toast.makeText(context,"已释放 ${humanSize(result.released)}",android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    Text(
                        "缓存 ${humanSize(cacheBytes)}",
                        fontSize=10.sp,
                        color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.72f),
                        modifier=Modifier.padding(bottom=10.dp),
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().widthIn(max=560.dp).align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal=22.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(88.dp))
                Text("一张图下，皆是秘密",modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,fontSize=27.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.5).sp,color=MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(12.dp))
                HomeAction("嵌入","＋",homeBlue){open(Screen.EMBED)}
                HomeAction("提取","↗",homeGreen){open(Screen.EXTRACT)}
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    HomeUtilityButton(Icons.Rounded.Cloud,"网盘登录",Modifier.weight(1f)){showCloudLogin=true}
                    HomeUtilityButton(Icons.Rounded.HelpOutline,"教程",Modifier.weight(1f)){showTutorial=true}
                    HomeUtilityButton(Icons.Rounded.History,"历史",Modifier.weight(1f)){showHistory=true}
                }
                BiliAccountButton(biliState=biliState,onClick={showBiliLogin=true})
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Text("项目与支持",fontSize=12.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp,start=2.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    HomeLinkButton(if(checking)"检查中…" else "检测更新",null,Modifier.weight(1f),enabled=!checking){check(true)}
                    HomeLinkButton("TrustMark",com.trustmark.share.R.drawable.ic_github,Modifier.weight(1f)){context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/adobe/trustmark")))}
                    HomeLinkButton("YunX",com.trustmark.share.R.drawable.ic_github,Modifier.weight(1f)){context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/CYQawa/YunX")))}
                }
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
               TextButton(onClick = openLegal) { Text("用户协议与隐私政策", fontSize = 12.sp) }
               TextButton(onClick = { scope.launch { clearingCache = true; val result = withContext(Dispatchers.IO) { CacheManager.clear(context) }; cacheBytes = result.after; clearingCache = false; android.widget.Toast.makeText(context, "已释放 ${humanSize(result.released)}", android.widget.Toast.LENGTH_SHORT).show() } }, enabled = !clearingCache) {
                   Text(if (clearingCache) "正在清理…" else "清理缓存 · ${humanSize(cacheBytes)}", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        }
        Surface(
            onClick={onDark(!dark)},
            modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top=12.dp,end=18.dp),
            shape=CircleShape,
            color=MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation=2.dp,
        ) {
            Box(Modifier.size(44.dp),contentAlignment=Alignment.Center){
                Icon(if(dark)Icons.Rounded.LightMode else Icons.Rounded.DarkMode,if(dark)"切换日间模式" else "切换夜间模式",Modifier.size(21.dp))
            }
        }
    }
    if (showCloudLogin) CloudLoginDialog { showCloudLogin = false }
    if (showTutorial) TutorialDialog { showTutorial = false }
    if (showHistory) PasscodeHistoryDialog { showHistory = false }
    if (!BuildConfig.EXTRACT_ONLY && showBiliLogin) BiliLoginDialog { showBiliLogin = false }
    if (!BuildConfig.EXTRACT_ONLY && showBiliNotice && featuresEnabled) AlertDialog(
        onDismissRequest={featurePrefs.edit().putBoolean("bili_upload_v1",true).apply();showBiliNotice=false},
        title={Text("新增 B站自动上传")},
        text={Text("登录 B站后即可临时上传加密承载图；开启“确保直链长期可用”后，还会创建仅自己可见的专栏稿件。B站隐藏内容上限为 100 MiB；可选匿名下载校验。")},
        confirmButton={TextButton({featurePrefs.edit().putBoolean("bili_upload_v1",true).apply();showBiliNotice=false;showBiliLogin=true}){Text("去登录 B站")}},
        dismissButton={TextButton({featurePrefs.edit().putBoolean("bili_upload_v1",true).apply();showBiliNotice=false}){Text("知道了")}},
    )
    update?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!info.force) dismissUpdate(info) },
            title = { Text("发现新版本 ${info.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(info.notes.ifBlank { "发现可用更新。" })
                    info.patch?.let { patch ->
                        Text("差分更新 ${humanSize(patch.size)}，无需下载完整安装包。", color = adaptiveAccent(Green), fontWeight = FontWeight.Bold)
                    }
                    if (updating) {
                        SmoothLinearProgress(updateProgress, Modifier.fillMaxWidth())
                        Text("$updateLabel ${(updateProgress * 100).roundToInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (updateError.isNotBlank()) Text(updateError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (!info.force) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(skipThisVersion, { skipThisVersion = it })
                        Text("不再提示这个版本")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !updating,
                    onClick = {
                        val patch = info.patch
                        if (patch != null) beginUpdate(info, true)
                        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL)))
                    },
                ) {
                    Text(if (preparedUpdate != null) "安装更新" else if (info.patch != null) "下载并安装" else "前往官网下载")
                }
            },
            dismissButton = {
                Row {
                    if (info.patch != null) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL))) }, enabled = !updating) { Text("完整更新") }
                    if (!info.force) TextButton(onClick = { dismissUpdate(info) }, enabled = !updating) { Text("稍后再说") }
                }
            },
        )
    }
}

@Composable
private fun TutorialDialog(onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用教程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page == 0) Button({ page = 0 }, Modifier.weight(1f)) { Text("图片图床") }
                    else OutlinedButton({ page = 0 }, Modifier.weight(1f)) { Text("图片图床") }
                    if (page == 1) Button({ page = 1 }, Modifier.weight(1f)) { Text("网盘") }
                    else OutlinedButton({ page = 1 }, Modifier.weight(1f)) { Text("网盘") }
                }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (page == 0) {
                        LegalSection("1. 生成承载图片", "选择要隐藏的内容和承载图片，完成加密生成后，把生成的每张图片保存并上传到不会压缩原图的图片服务。")
                        LegalSection("2. 填写图片链接", "进入“填写公开图片直链”。链接可以任意排序，应用会下载原图并按 SHA-256 内容哈希自动匹配；重复链接和重复图片会自动识别。")
                        LegalSection("3. 使用 QQ 频道", "点击标题中的蓝色“直链”，粘贴 QQ 频道公开帖子链接并点“确认”。应用获取帖子原图、自动填写、校验并登记成功后直接进入下一步。若 QQ 要求人机验证，会打开官方页面让你完成验证。")
                        LegalSection("4. 完成水印", "登记成功后选择最终图片，点击“确定并嵌入”。请保留原始承载图片；图床压缩、转码或删除原图会导致内容无法恢复。")
                    } else {
                        LegalSection("1. 登录对应网盘", "在主页右下角进入“网盘登录”。只有使用某个网盘的分享链接时才需要登录该网盘；已有且仍有效的登录状态会继续使用。")
                        LegalSection("2. 自行上传并分享", "应用不会代替你上传。先把所有生成的承载图片上传到网盘，再创建分享链接。可以分享文件夹，应用会递归扫描并忽略无关文件。")
                        LegalSection("3. 登记分享", "把分享链接填到链接页后点“验证并登记”。应用根据内容哈希匹配所有图片，不要求顺序一致；链接包含提取码时会自动识别，需要时再提示填写。")
                        LegalSection("4. 提取内容", "提取者必须登录分享链接所属的网盘。应用使用服务器保存的平台、分享链接和文件 ID 重新取得下载地址，再校验 SHA-256；登录失效时才会要求重新登录。")
                    }
                }
            }
        },
        confirmButton = { Button(onDismiss) { Text("知道了") } },
    )
}

private fun copyPasscode(context: android.content.Context, code: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("口令码", code))
    android.widget.Toast.makeText(context, "口令码已复制", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun PasscodeCard(code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("口令码", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
                Text(code, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            IconButton(onClick = { copyPasscode(context, code) }) { Icon(Icons.Rounded.ContentCopy, "复制口令码") }
        }
    }
}

@Composable
private fun PasscodeHistoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(PasscodeHistoryKind.UPLOAD) }
    var revision by remember { mutableIntStateOf(0) }
    val items = remember(kind, revision) { PasscodeHistory.list(context, kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("口令码历史") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == PasscodeHistoryKind.UPLOAD) Button({ kind = PasscodeHistoryKind.UPLOAD }, Modifier.weight(1f)) { Text("上传") }
                    else OutlinedButton({ kind = PasscodeHistoryKind.UPLOAD }, Modifier.weight(1f)) { Text("上传") }
                    if (kind == PasscodeHistoryKind.EXTRACT) Button({ kind = PasscodeHistoryKind.EXTRACT }, Modifier.weight(1f)) { Text("提取") }
                    else OutlinedButton({ kind = PasscodeHistoryKind.EXTRACT }, Modifier.weight(1f)) { Text("提取") }
                }
                if (items.isEmpty()) Text("暂无记录", Modifier.fillMaxWidth().padding(vertical = 28.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEach { code ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(code, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                            IconButton({ copyPasscode(context, code) }) { Icon(Icons.Rounded.ContentCopy, "复制 $code") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { PasscodeHistory.clear(context); revision++ }, enabled = items.isNotEmpty()) { Text("清空全部") } },
    )
}

@Composable
private fun LegalDialog(required: Boolean, onAgree: () -> Unit, onDismiss: () -> Unit, onReject: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (!required) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !required,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("用户协议与隐私政策", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Black)
                    if (!required) IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭") }
                }
                Text("版本 7 · 生效日期：2026年10月18日", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (BuildConfig.EXTRACT_ONLY) {
                        LegalSection("一、使用前须知", "隐像·溯源仅用于识别盲水印口令、下载对应承载图片并恢复隐藏内容。使用前请完整阅读本协议；点击“同意并继续”表示你已理解并同意全部内容。")
                        LegalSection("二、合法使用", "你必须遵守所在地法律法规，并确认自己有权访问和恢复相关内容。禁止利用本软件实施违法犯罪、侵犯隐私或知识产权、未经授权访问、批量滥用接口及其他损害他人或公共利益的行为。")
                        LegalSection("三、数据处理", "所选图片在设备本地完成盲水印识别。应用会用识别出的口令码查询恢复所需的加密元数据和图片位置，再下载、校验、解密并恢复文件。恢复结果仅在你主动保存时写入公共存储。")
                        LegalSection("四、网络与第三方服务", "提取时可能匿名访问普通图床或 B站承载图片；这不需要 B站登录，也不会上传或投稿。使用网盘资源时，需要登录对应网盘；凭据经 Android Keystore 加密后仅保存在本设备，不发送到本软件后端。第三方服务可能按其政策处理 IP 地址、请求时间、User-Agent、登录状态和访问内容。")
                        LegalSection("五、第三方账户与风控", "登录或访问 B站、网盘、QQ频道等第三方服务时，你同时受相应平台协议和规则约束。登录、下载、校验或频繁请求可能触发验证码、限流、登录限制、冻结或封禁；这些措施由第三方决定，本软件无法预防、绕过、解除或代为申诉，也不保证账户持续可用。因你的操作、内容或违反第三方规则造成的账户处罚及损失由你依法承担，但法律规定不得排除的责任除外。触发风控后请停止重复请求，改用对应平台的官方客户端或网站完成验证、检查账户状态并按官方渠道申诉。")
                        LegalSection("六、完整性与可用性", "应用会限制远程下载大小、重定向次数和地址类型，并校验下载分片的 SHA-256 及恢复文件完整性。但第三方链接可能失效、限速或改变，原图被压缩、转码、裁剪或删除后也可能无法恢复。请自行保留重要文件备份。")
                        LegalSection("七、本地缓存与权限", "导入、下载、解压、预览和算法运行会产生临时文件或缓存，应用会按既定策略清理，也可在主页手动清理。联网功能需要网络权限；Android 9 及以下保存文件时可能请求旧版存储写入权限。")
                        LegalSection("八、开源软件声明", "网盘登录与解析适配来自 YunX，采用 GNU AGPL-3.0。盲水印包含 Adobe TrustMark 的代码和模型，推理运行时包含 Microsoft ONNX Runtime，均采用 MIT License。相关项目的商标和著作权归各自权利人所有。")
                        LegalSection("MIT License 全文", MIT_LICENSE)
                        LegalSection("九、协议变更", "功能、数据处理方式或第三方组件发生实质变化时，协议版本将更新，并可能再次要求你确认。")
                    } else {
                    LegalSection("一、使用前须知", "本软件用于图片隐写、盲水印及相关技术的研究与分享。使用前请完整阅读本协议。点击“同意并继续”表示你已理解并同意全部内容；不同意时请停止使用。")
                    LegalSection("二、合法使用与禁止行为", "你必须遵守所在地法律法规，并对输入、处理、发布和下载的内容及其后果负责。禁止利用本软件实施或协助违法犯罪、诈骗、骚扰、传播违法或有害内容、侵犯著作权或其他知识产权、侵犯隐私或个人信息权益、未经授权访问或绕过安全措施、攻击网络服务、批量滥用接口，以及其他损害他人或公共利益的行为。")
                    LegalSection("三、功能与责任", "本软件不能保证隐藏内容在所有平台的压缩、转码、裁剪或二次处理后仍可恢复，也不能保证第三方链接永久有效。自动分配载体图仅按设定容量规划，实际可用性仍取决于文件、图片和第三方服务。请自行确认你有权处理相关文件，并妥善保留原文件和必要备份。软件按现状提供，仅作技术分享；在法律允许范围内，不对数据丢失、内容损坏、链接失效、服务中断或不当使用造成的损失作保证。")
                    LegalSection("四、本地压缩与数据处理", "图片、视频及普通文件本体在设备本地处理，不上传到本软件自己的服务器。启用智能压缩时，所选静态图片会按你设置的质量尝试转换为 WebP，动画图片、转换失败或结果更大时保留原文件。启用视频压缩时，仅对符合条件的视频尝试使用设备硬件 H.265 编码器生成临时副本；超过横屏 1920×1080 或竖屏 1080×1920 时会等比例缩小，不裁剪、不拉伸且不主动改变帧率。仅当输出格式、时长、尺寸、帧率和音轨检查通过且体积小于原文件的 90% 时采用压缩结果，否则继续使用原视频。文件型流程会向服务器保存恢复所需的文件密钥、加密元数据、图片 SHA-256 哈希、分片和协议元数据。普通图床保存 HTTPS 下载链接；网盘保存平台、分享链接、网盘文件 ID、必要提取码及对应图片哈希。文本内容不超过 1000 字节时，可按你的选择加密后保存到服务器。新记录使用 5 位英文字母口令码，自创建起有效 30 天，过期后无法提取并由定时任务清理；旧版 8 位记录继续按原规则保留。持有有效口令码的人可尝试提取对应内容，请仅分享给可信对象。")
                    LegalSection("五、网络与第三方服务", "点击登记、检查更新或执行提取时，应用访问相应网络服务。主页还会核验已保存的 B站登录；打开 B站登录弹窗会申请二维码并轮询扫码状态。QQ频道功能只访问你填写的公开帖子并读取其中的原图地址；如官方页面要求验证，需要由你完成。远程下载会限制文件大小、重定向次数和地址类型，以降低异常地址和超大响应风险。图床、网盘、QQ频道及其他第三方会按其政策处理 IP 地址、请求时间、User-Agent、登录状态和分享内容，本软件无法控制其保存期限与可用性。请勿提交你无权公开或交由第三方处理的内容。")
                    LegalSection("六、第三方账户与风控", "登录或使用 B站、网盘、QQ频道等第三方服务时，你同时受相应平台协议、社区规范和风控规则约束。登录、上传、投稿、下载、校验或频繁请求可能触发验证码、限流、登录限制、内容处置、账户冻结或封禁；这些措施由第三方决定，本软件无法预防、绕过、解除或代为申诉，也不保证账户持续可用。因你的操作、内容或违反第三方规则造成的账户处罚及损失由你依法承担，但法律规定不得排除的责任除外。触发风控后请立即停止重复请求，改用对应平台的官方客户端或网站完成验证、检查账户状态，并按官方渠道申诉或处理；必要时退出登录、撤销会话或修改密码。")
                    LegalSection("七、B站自动上传", "开启“确保直链长期可用”后，应用将隐藏内容在本机打包、加密，将密文追加到承载图片，上传至 B站并创建仅自己可见的专栏稿件。稿件仅自己可见，但持有图片直链的人仍可能访问，且无法保证永久有效。开启完整校验时，投稿后匿名下载并比对完整大小及 SHA-256，通过后才登记口令；关闭完整校验时仍执行基础安全与内容检查，但不能保证远程内容与本地文件完全一致。B站登录 Cookie 使用 Android Keystore 加密，仅保存在本机。取消处理不能撤回已经上传的图片或已提交稿件。")
                    LegalSection("八、本地文件、缓存与权限", "本软件仅在你主动选择时读取文件，并将你明确保存的结果写入设备存储或相册。上传和提取成功的口令码会分别保存在应用私有历史记录中，最多各 100 条，可在主页查看、复制或全部清空；该历史不上传服务器。B站及网盘登录凭据通过 Android Keystore 加密后仅保存在本设备，不发送到本软件后端。预览、图片与视频压缩、下载、解压和算法运行会产生临时文件或缓存，应用会按既定策略清理，也可在主页手动清理。联网功能需要网络权限；视频压缩会使用前台服务并显示进度通知；Android 9 及以下保存文件时可能请求旧版存储写入权限。")
                    LegalSection("九、账户、查询与删除", "本软件不注册自己的用户账户。只有使用 B站自动上传或特定网盘链接时才要求登录对应第三方账户；登录失效或无法取得内容时会要求重新登录。承载图片由你自行上传或按你的选择自动上传，服务器不保存第三方账号凭据或短期下载签名。本软件当前没有服务器记录查询、修改或删除入口，请在发送前确认内容无误并保留原文件。")
                    LegalSection("十、开源软件声明", "网盘登录与解析适配来自 heDuke 维护的 YunX 项目（https://github.com/CYQawa/YunX），采用 GNU AGPL-3.0，版权与许可证保留在源码中，对应完整源码随版本提供。盲水印包含 Adobe TrustMark 的代码和模型，推理运行时包含 Microsoft ONNX Runtime，均采用 MIT License。相关项目的商标和著作权归各自权利人所有。")
                    LegalSection("MIT License 全文", MIT_LICENSE)
                    LegalSection("十一、协议变更", "功能、数据处理方式或第三方组件发生实质变化时，协议版本将更新，并可能再次要求你确认。继续使用前请阅读更新内容。")
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { checked = !checked }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it })
                    Text("我已阅读并同意用户协议与隐私政策", fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (required) OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("不同意并退出") }
                    else OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("返回") }
                    Button(onClick = onAgree, enabled = checked, modifier = Modifier.weight(1.35f)) { Text("同意并继续") }
                }
            }
        }
    }
}

@Composable
private fun LegalSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

private val MIT_LICENSE = """
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
""".trimIndent()

@Composable private fun HomeAction(label:String,symbol:String,color:Color,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(24.dp)).background(color).clickable(onClick=onClick).padding(horizontal=26.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),color=Color.White,fontSize=25.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=1.sp);Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha=.17f)),contentAlignment=Alignment.Center){Text(symbol,color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)}}
}

@Composable private fun TraceCloudAction(onClick:()->Unit){
    OutlinedButton(
        onClick=onClick,
        modifier=Modifier.fillMaxWidth(.68f).widthIn(max=300.dp).height(48.dp),
        shape=RoundedCornerShape(16.dp),
        contentPadding=PaddingValues(horizontal=16.dp),
    ){
        Icon(Icons.Rounded.Cloud,contentDescription=null,modifier=Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text("网盘登录",fontSize=13.sp,fontWeight=FontWeight.Bold)
    }
}

@Composable private fun TraceBottomAction(label:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit){
    TextButton(onClick=onClick,modifier=modifier.height(44.dp),enabled=enabled,contentPadding=PaddingValues(horizontal=2.dp)){
        Text(label,fontSize=11.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable private fun BiliAccountButton(biliState:BiliLoginState,onClick:()->Unit){
    val status = when(biliState){
        BiliLoginState.VERIFIED -> "已登录"
        BiliLoginState.UNVERIFIED -> "暂时无法验证"
        else -> "登录"
    }
    val statusColor = when(biliState){
        BiliLoginState.VERIFIED -> adaptiveAccent(Green)
        BiliLoginState.UNVERIFIED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val shape = RoundedCornerShape(17.dp)
    Row(
        Modifier.fillMaxWidth().height(56.dp).border(1.dp,MaterialTheme.colorScheme.outline,shape)
            .clip(shape).clickable(onClick=onClick).padding(horizontal=14.dp),
        verticalAlignment=Alignment.CenterVertically,
    ){
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFFB7299)),contentAlignment=Alignment.Center){
            Text("哔",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(10.dp))
        Text("B站账号",modifier=Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface)
        Text(status,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=statusColor)
        Spacer(Modifier.width(7.dp))
        Text("›",fontSize=22.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun HomeUtilityButton(icon:ImageVector,label:String,modifier:Modifier=Modifier,onClick:()->Unit){
    OutlinedButton(onClick=onClick,modifier=modifier.height(52.dp),shape=RoundedCornerShape(17.dp),contentPadding=PaddingValues(horizontal=8.dp)){
        Icon(icon,contentDescription=null,modifier=Modifier.size(19.dp));Spacer(Modifier.width(6.dp));Text(label,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1)
    }
}

@Composable private fun HomeLinkButton(label:String,iconRes:Int?,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit){
    TextButton(onClick=onClick,modifier=modifier.height(44.dp),enabled=enabled,contentPadding=PaddingValues(horizontal=5.dp)){
        if(iconRes!=null){Icon(painterResource(iconRes),contentDescription=null,modifier=Modifier.size(17.dp));Spacer(Modifier.width(5.dp))}
        Text(label,fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EmbedPage(dark: Boolean, back: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val bridge = remember { TrustMarkPlugin(context) }
    val loader = remember { ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory());if(Build.VERSION.SDK_INT>=28)add(AnimatedImageDecoder.Factory())else add(GifDecoder.Factory()) }.build() }
    DisposableEffect(Unit){onDispose{Thread{
        val videoActive=VideoCompressionBus.updates.value.values.any{it.stage==VideoCompressionStage.ANALYZING||it.stage==VideoCompressionStage.QUEUED||it.stage==VideoCompressionStage.COMPRESSING}
        if(!videoActive)CacheManager.clearTask(context,setOf("imports","payload-bundles","payload-opt","carrier-opt","carriers","text","trustmark-result","video-hevc"))
    }.start()}}
    val biliSession = remember { BiliSession.get(context) }
    val biliState by biliSession.state.collectAsState()
    var biliFlow by remember { mutableStateOf(false) }
    var biliRegistrationUncertain by remember { mutableStateOf(false) }
    var biliJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showBiliSuggestion by remember { mutableStateOf(false) }
    var showLargeCarrierWarning by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }; var payloads by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }; var covers by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    val videoUpdates by VideoCompressionBus.updates.collectAsState()
    var appliedVideoTasks by remember { mutableStateOf<Set<String>>(emptySet()) }
    val videoActive=videoUpdates.values.any{it.stage==VideoCompressionStage.ANALYZING||it.stage==VideoCompressionStage.QUEUED||it.stage==VideoCompressionStage.COMPRESSING}
    val activeVideoPaths=videoUpdates.values.filter{it.stage==VideoCompressionStage.ANALYZING||it.stage==VideoCompressionStage.QUEUED||it.stage==VideoCompressionStage.COMPRESSING}.map{it.input.file.absolutePath}.toSet()
    val countedPayloads=payloads.filterNot{it.file.absolutePath in activeVideoPaths}
    var generated by remember { mutableStateOf<GeneratedResource?>(null) }; var urls by remember { mutableStateOf<List<String>>(emptyList()) }; var resourceId by remember { mutableStateOf("") }
    var finalCover by remember { mutableStateOf<LocalMedia?>(null) }; var result by remember { mutableStateOf<LocalMedia?>(null) }; var preview by remember { mutableStateOf<LocalMedia?>(null) }
    var busy by remember { mutableStateOf("") }; var progress by remember { mutableFloatStateOf(0f) }; var error by remember { mutableStateOf("") }; var notice by remember { mutableStateOf("") }
    var percentOnlyProgress by remember { mutableStateOf(false) }
    var payloadExpanded by remember { mutableStateOf(false) }; var coversExpanded by remember { mutableStateOf(false) }; var showImportMenu by remember { mutableStateOf(false) }
    var carriersDownloaded by remember { mutableStateOf(false) }; var validationSuccess by remember { mutableStateOf(false) }
    var matchedRemotes by remember { mutableStateOf<Map<Int,VerifiedRemote>>(emptyMap()) }
    var showQqDialog by remember { mutableStateOf(false) }
    var qqLink by remember { mutableStateOf("") }
    var qqBrowserLink by remember { mutableStateOf<String?>(null) }
    var pendingPassword by remember { mutableStateOf<String?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var sharePasswords by remember { mutableStateOf<Map<String,String>>(emptyMap()) }

    var inlineTextFlow by remember { mutableStateOf(false) }
    var carrierSelectionMode by remember { mutableStateOf(CarrierSelectionMode.DEFAULT) }
    val settings = remember { context.getSharedPreferences("embed_settings", android.content.Context.MODE_PRIVATE) }
    var smartCompress by remember { mutableStateOf(settings.getBoolean("smart_compress", true)) }
    var compressVideo by remember { mutableStateOf(settings.getBoolean("compress_video", false)) }
    var webpQuality by remember { mutableIntStateOf(settings.getInt("webp_quality", 75).coerceIn(0,100)) }
    var autoAllocate by remember { mutableStateOf(settings.getBoolean("auto_allocate_carriers", false)) }
    var targetAppendHalfSteps by remember { mutableIntStateOf(settings.getInt("target_append_half_steps", 7).coerceIn(2,8)) }
    var verifyRemote by remember { mutableStateOf(settings.getBoolean("verify_remote", settings.getBoolean("verify_bili", true))) }
    val biliFeatures = remember { context.getSharedPreferences("bili_features", android.content.Context.MODE_PRIVATE) }
    val longLinkEnabled = biliFeatures.getBoolean("long_link_enabled", false)
    var showSettings by remember { mutableStateOf(false) }
    var showProxyWarning by remember { mutableStateOf(false) }
    var firstCover by remember { mutableStateOf(DefaultImageStore.first(context)) }
    var secondCover by remember { mutableStateOf(DefaultImageStore.second(context)) }
    var replaceFirstDefault by remember { mutableStateOf(false) }
    var replaceSecondDefault by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }; var textInput by remember { mutableStateOf("") }
    val textFocusRequester=remember{FocusRequester()};val keyboard=LocalSoftwareKeyboardController.current
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(showTextInput){if(showTextInput){delay(120);textFocusRequester.requestFocus();keyboard?.show()}}
    LaunchedEffect(videoUpdates){
        videoUpdates.values.forEach{update->
            val inputPath=update.input.file.absolutePath
            if(update.stage==VideoCompressionStage.ADOPTED&&update.output!=null&&update.taskId !in appliedVideoTasks){
                val output=update.output
                val index=payloads.indexOfFirst{it.file.absolutePath==inputPath}
                payloads=if(index>=0)payloads.toMutableList().apply{set(index,output)} else if(payloads.none{it.file.absolutePath==output.file.absolutePath})payloads+output else payloads
                appliedVideoTasks=appliedVideoTasks+update.taskId
                generated=null;urls=emptyList();carriersDownloaded=false;validationSuccess=false;resourceId="";result=null;inlineTextFlow=false;biliFlow=false
            }else if(update.stage!=VideoCompressionStage.ADOPTED&&payloads.none{it.file.absolutePath==inputPath}){
                payloads=payloads+update.input
            }
        }
    }
    fun importPayloadUris(uris:List<android.net.Uri>){if(uris.isEmpty())return;scope.launch{
        busy="正在处理图片";progress=.03f;percentOnlyProgress=true;error="";notice=""
        try {
            val limiter=Semaphore(minOf(4,Runtime.getRuntime().availableProcessors().coerceAtLeast(2)))
            val importedCount=java.util.concurrent.atomic.AtomicInteger(0)
            val processedCount=java.util.concurrent.atomic.AtomicInteger(0)
            val importedFiles = java.util.Collections.synchronizedList(mutableListOf<File>())
            val outcomes=try { kotlinx.coroutines.coroutineScope { uris.map{uri->async(Dispatchers.IO){limiter.withPermit{
                val imported=MediaFiles.import(context,uri)
                importedFiles.add(imported.file)
                val importedDone=importedCount.incrementAndGet()
                withContext(Dispatchers.Main){progress=maxOf(progress,.03f+.27f*importedDone/uris.size)}
                if(!smartCompress||!imported.isImage) {
                    val done=processedCount.incrementAndGet()
                    withContext(Dispatchers.Main){progress=maxOf(progress,.3f+.65f*done/uris.size)}
                    imported to null
                } else {
                    runCatching{DefaultImageStore.optimizePayloadImage(imported,File(context.cacheDir,"webp-cache"),webpQuality)}
                        .fold({it.copy(sourceSize=imported.sourceSize) to null},{imported to imported.displayName})
                        .also{withContext(Dispatchers.Main){val done=processedCount.incrementAndGet();progress=maxOf(progress,.3f+.65f*done/uris.size)}}
                }
            }}}.awaitAll() } } catch(failure:Throwable){importedFiles.forEach{it.delete()};throw failure}
            val items=outcomes.map{it.first}
            val skipped=outcomes.mapNotNull{it.second}
            progress=.98f
            payloads=payloads+items;generated=null;urls=emptyList();carriersDownloaded=false
            validationSuccess=false;resourceId="";result=null;inlineTextFlow=false;biliFlow=false
            if(compressVideo&&items.any{it.isVideo}&&Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            if(compressVideo)VideoCompressionService.enqueue(context,items.filter{it.isVideo})
            if(skipped.isNotEmpty())notice="部分图片无法转换为 WebP，已自动保留原文件。"
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.message?:"导入失败"}
        finally{busy="";progress=0f;percentOnlyProgress=false}
    }}
    val pickImages=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->importPayloadUris(uris)}
    val pickVideos=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->importPayloadUris(uris)}
    val pickFiles=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->importPayloadUris(uris)}
    val pickCovers=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->if(uris.isNotEmpty())scope.launch{busy="正在导入承载图片";progress=.05f;error="";runCatching{uris.mapIndexed{index,uri->val imported=withContext(Dispatchers.IO){MediaFiles.import(context,uri)};progress=.1f+.85f*((index+1f)/uris.size);imported}}.onSuccess{covers=it;carrierSelectionMode=CarrierSelectionMode.MANUAL;generated=null;matchedRemotes=emptyMap();urls=emptyList();validationSuccess=false;resourceId="";carriersDownloaded=false;progress=1f}.onFailure{error=it.message?:"承载图导入失败"};delay(150);busy="";progress=0f}}
    val pickFirst = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { runCatching {
        DefaultImageStore.replace(context,"first",uri)
    }.onSuccess { firstCover=it;carrierSelectionMode=CarrierSelectionMode.DEFAULT;covers=emptyList();generated=null;matchedRemotes=emptyMap();urls=emptyList();validationSuccess=false;resourceId="";carriersDownloaded=false }.onFailure { error=it.message?:"图片导入失败" };replaceFirstDefault=false } }
    val pickSecond = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { runCatching {
        val imported=MediaFiles.import(context,uri)
        val report=DefaultImageStore.textureReport(imported)
        require(report.accepted){"图片纹理过少或清晰度不足，可能导致水印无法提取，请选择细节更丰富的图片"}
        if(replaceSecondDefault) DefaultImageStore.replace(context,"second",uri) else imported
    }.onSuccess { secondCover=it;finalCover=it;result=null }.onFailure { error=it.message?:"图片检测失败" };replaceSecondDefault=false } }
    fun openPicker(launcher: androidx.activity.result.ActivityResultLauncher<String>, mimeType:String, stage:String){

        try{launcher.launch(mimeType);}catch(failure:RuntimeException){error=when(failure){is android.content.ActivityNotFoundException->"系统文件选择器不可用，请启用系统“文件”应用后重试";is SecurityException->"系统拒绝打开文件选择器，请检查系统文件应用权限";else->"无法打开文件选择器，请稍后重试"}}
    }
    fun task(label: String, block: suspend () -> Unit) { scope.launch {
        busy=label;progress=.05f;error=""
        try { block();progress=1f }
        catch(required:SharePasswordRequired){pendingPassword=required.shareUrl;passwordInput=""}
        catch(required:QqBrowserRequired){qqBrowserLink=required.shareUrl;showQqDialog=false}
        catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.message?:"发生未知错误"}
        finally{busy="";progress=0f}
    } }
    fun uploadBili(ignoreProxy:Boolean=false) {
        if(busy.isNotEmpty() || biliRegistrationUncertain) return
        if(!ignoreProxy&&BiliNetwork.proxyOrVpnActive(context)){showProxyWarning=true;return}
        if(!BiliPayload.shouldSuggest(biliState==BiliLoginState.VERIFIED,payloads,biliRegistrationUncertain)) {
            error="请先登录 B站，并选择不超过 100 MiB 的隐藏文件（不含载体图）";return
        }
        if(biliFlow && resourceId.isNotBlank()) { step=3; return }
        biliJob=scope.launch {
            busy="正在准备 B站自动上传";error="";progress=.01f
            try {
                val id=BiliWorkflow.run(context,payloads,verifyRemote,longLinkEnabled) { label,fraction ->
                    withContext(Dispatchers.Main) { busy=label;progress=fraction }
                }
                resourceId=id;biliFlow=true;inlineTextFlow=false;validationSuccess=true;step=3;progress=1f
            } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(_:BiliSubmissionUncertain){biliRegistrationUncertain=true;error="B站私密稿件提交结果未确认，请先到创作中心核实，切勿重复投稿。"}
            catch(_:BiliRegistrationUncertain){biliRegistrationUncertain=true;error="口令登记结果未确认，请勿重复提交。已上传图片可能仍保留。"}
            catch(failure:Exception){error=failure.message?:"B站上传失败"}
            finally{busy="";progress=0f;biliJob=null}
        }
    }
    fun continueManually() {
        showBiliSuggestion=false
        inlineTextFlow=false;biliFlow=false;resourceId="";step=1
    }
    fun nextEmbedStep() {
        if(step==0 && BiliPayload.shouldSuggest(biliState==BiliLoginState.VERIFIED,payloads,biliRegistrationUncertain))
            showBiliSuggestion=true
        else if(step==0) continueManually()
        else step++
    }
    fun leaveBiliOrBack() {
        if(biliJob==null) back()
        else if(progress<.9f){biliJob?.cancel();notice="已取消处理，已上传的图片可能仍保留。"}
        else error="正在登记口令，请等待结果，避免重复提交。"
    }
    BackHandler(enabled=biliJob!=null){leaveBiliOrBack()}
    suspend fun registerQqImages(images:List<String>){
        val g=requireNotNull(generated)
        val checked=withContext(Dispatchers.IO){RemoteRegistration.match(context,g.carriers,images,matchedRemotes,validationLevel=if(verifyRemote)RemoteValidationLevel.FULL else RemoteValidationLevel.BASIC)}
        matchedRemotes=checked.matches
        urls=g.carriers.indices.map{checked.matches[it]?.let{it.cloud?.shareUrl?:it.url}.orEmpty()}
        notice=checked.message(g.carriers.size)+(if(!verifyRemote)"\n未进行完整 SHA-256 校验。" else "");validationSuccess=false
        if(checked.matches.size==g.carriers.size&&checked.passwordRequest==null&&checked.loginRequired==null){
            busy="正在安全登记";progress=.7f
            resourceId=withContext(Dispatchers.IO){NativeWorkflow.createRecord(g,g.carriers.indices.map{checked.matches.getValue(it)})}
            validationSuccess=true;showQqDialog=false;step=3
        }
    }
    fun invalidateGenerated() { matchedRemotes=emptyMap();generated=null;urls=emptyList();carriersDownloaded=false;validationSuccess=false;resourceId="";result=null;inlineTextFlow=false;biliFlow=false }
    fun reset() { VideoCompressionService.cancel(context);VideoCompressionBus.clear();appliedVideoTasks=emptySet();matchedRemotes=emptyMap();sharePasswords=emptyMap();step=0;payloads=emptyList();covers=emptyList();carrierSelectionMode=CarrierSelectionMode.DEFAULT;generated=null;urls=emptyList();resourceId="";finalCover=null;result=null;error="";notice="";payloadExpanded=false;coversExpanded=false;carriersDownloaded=false;validationSuccess=false;inlineTextFlow=false;biliFlow=false }
    fun removePayload(item:LocalMedia,update:VideoCompressionUpdate?){
        val active=update?.stage==VideoCompressionStage.ANALYZING||update?.stage==VideoCompressionStage.QUEUED||update?.stage==VideoCompressionStage.COMPRESSING
        update?.let{VideoCompressionBus.dismiss(it.input.file.absolutePath)}
        if(active)VideoCompressionService.cancel(context)
        payloads=payloads.filterNot{it.file.absolutePath==item.file.absolutePath};invalidateGenerated()
        scope.launch(Dispatchers.IO){if(active)delay(350);listOfNotNull(item.file,update?.input?.file,update?.output?.file).distinctBy{it.absolutePath}.forEach{it.delete()}}
    }
    fun currentAllocation()=CarrierAllocation(if(autoAllocate&&carrierSelectionMode==CarrierSelectionMode.DEFAULT)CarrierAllocationMode.AUTO else CarrierAllocationMode.STANDARD,targetAppendHalfSteps*500_000L)
    fun generateCarriers(){task("正在打包隐藏内容"){
        progress=.12f
        val s=withContext(Dispatchers.IO){PayloadBundle.pack(payloads,File(context.cacheDir,"payload-bundles"))}
        busy="正在准备承载图片";progress=.35f
        val selected=if(carrierSelectionMode==CarrierSelectionMode.DEFAULT)listOf(withContext(Dispatchers.IO){DefaultImageStore.prepareFirstCarrier(firstCover,File(context.cacheDir,"carrier-opt"))})else covers
        busy="正在生成加密图片";progress=.55f
        generated=withContext(Dispatchers.IO){NativeWorkflow.generate(s,selected,File(context.cacheDir,"carriers"),allocation=currentAllocation())};urls=generated!!.carriers.map{""};carriersDownloaded=false
        progress=.95f
    }}
    val titles = listOf("文件","承载图片","链接","水印图片","完成")
    FlowPage(blue = true, title = "嵌入", back = ::leaveBiliOrBack, header = { StepBar(titles, step) }, footer = {
        if(step==4) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Button(::reset,Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=adaptiveAccent(Green))){Text("再来一次")}
            OutlinedButton(back,Modifier.weight(1f)){Text("返回主界面")}
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { if(step>0) step=if(step==3&&(inlineTextFlow||biliFlow))0 else step-1 }, enabled = step>0 && busy.isEmpty()) { Text("← 上一步") }
            if(step<2) Button(onClick={nextEmbedStep()},enabled=busy.isEmpty()&&!videoActive&&((step==0&&payloads.isNotEmpty())||(step==1&&generated!=null&&carriersDownloaded))){Text("下一步 →")}
        }
    }) {
        when(step) {
            0 -> {
                Text("选择隐藏文件",fontSize=29.sp,fontWeight=FontWeight.Black,letterSpacing=(-.7).sp,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp))
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Spacer(Modifier.weight(1f))
                        if(payloads.isNotEmpty())Column(horizontalAlignment=Alignment.End){Text("共 ${payloads.size} 项",fontSize=12.sp);Text("隐藏 ${humanSize(countedPayloads.sumOf{it.file.length()})}",fontSize=12.sp)}
                        IconButton({showSettings=true}){Icon(Icons.Rounded.Settings,contentDescription="隐藏内容设置")}
                    }
                    val shown=if(payloadExpanded)payloads else payloads.take(3)
                    shown.forEach{item->key(item.file.absolutePath){
                        val videoUpdate=videoUpdates.values.lastOrNull{it.input.file.absolutePath==item.file.absolutePath||it.output?.file?.absolutePath==item.file.absolutePath}
                        val analysis=videoUpdate?.analysis
                        val videoDetail=videoUpdate?.let{update->buildString{
                            if(analysis!=null&&analysis.width>0)append("${analysis.width}×${analysis.height} · ${analysis.frameRate.roundToInt()} fps · ${analysis.videoBitrate/1000} kbps · ${analysis.audioDescription}\n")
                            append(update.message);if(update.stage==VideoCompressionStage.COMPRESSING)append(" ${update.progress}%")
                        }}
                        val itemVideoActive=videoUpdate?.stage==VideoCompressionStage.ANALYZING||videoUpdate?.stage==VideoCompressionStage.QUEUED||videoUpdate?.stage==VideoCompressionStage.COMPRESSING
                        ReorderableMediaRow(item.file.absolutePath,busy.isEmpty(),{direction->
                            val from=payloads.indexOf(item);val to=from+direction
                            if(from>=0&&to in payloads.indices){payloads=payloads.toMutableList().apply{add(to,removeAt(from))};invalidateGenerated()}
                        }){LegacyPayloadRow(item,payloads.indexOf(item)+1,loader,{preview=item},{
                            if(busy.isEmpty())removePayload(item,videoUpdate)
                        },detail=videoDetail,hideSize=itemVideoActive,progress=videoUpdate?.takeIf{it.stage==VideoCompressionStage.COMPRESSING}?.progress?.div(100f),progressIndeterminate=videoUpdate?.stage==VideoCompressionStage.ANALYZING||videoUpdate?.stage==VideoCompressionStage.QUEUED)}
                    }}
                    if(payloads.size>1)Text("长按 0.2 秒，上下拖动调整位置",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(payloads.size>3)TextButton({payloadExpanded=!payloadExpanded},Modifier.fillMaxWidth()){Text(if(payloadExpanded)"收起" else "展开全部 ${payloads.size} 项（已折叠 ${payloads.size-3} 项）")}
                    Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button({showImportMenu=true},Modifier.weight(1f),enabled=busy.isEmpty()&&!videoActive){Text("导入")}
                        OutlinedButton({VideoCompressionService.cancel(context);VideoCompressionBus.clear();appliedVideoTasks=emptySet();payloads=emptyList();payloadExpanded=false;invalidateGenerated()},Modifier.weight(1f),enabled=payloads.isNotEmpty()&&busy.isEmpty()){Text("清空")}
                    }
                    if(videoActive)OutlinedButton({VideoCompressionService.cancel(context)},Modifier.fillMaxWidth()){Text("取消视频压缩")}
                    val inlineText=payloads.singleOrNull()?.takeIf{it.isText&&it.file.length() in 1..1000}
                    if(biliState==BiliLoginState.VERIFIED && payloads.isNotEmpty()) {
                        val hiddenBytes=BiliPayload.size(countedPayloads)
                        val estimatedBytes=(hiddenBytes+if(countedPayloads.size>1)countedPayloads.size*512L+1024 else 0L)
                        val estimate=if(estimatedBytes in 1..BiliPartition.MAX_PAYLOAD)BiliPartition.plan(estimatedBytes,1024).size else null
                        Text("B站隐藏内容 ${humanSize(hiddenBytes)} / 100 MiB（不含载体图）",fontSize=12.sp)
                        Text("预计 ${estimate?.toString()?:"待打包确认"} 张载体，打包后确认分片数量。",fontSize=12.sp)
                        Text(if(longLinkEnabled)"加密后上传并创建仅自己可见的专栏；${if(verifyRemote)"匿名完整校验通过后" else "完成基础安全校验后"}进入盲水印图片选择。" else "加密后临时上传到 B站；${if(verifyRemote)"匿名完整校验通过后" else "完成基础安全校验后"}进入盲水印图片选择。直链可能短期失效。",fontSize=12.sp)
                        Button(onClick={uploadBili()},enabled=busy.isEmpty()&&!videoActive&&BiliPayload.shouldSuggest(biliState==BiliLoginState.VERIFIED,payloads,biliRegistrationUncertain),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=adaptiveAccent(Green))){Text(if(biliFlow&&resourceId.isNotBlank())"继续选择盲水印图" else "将内容发放到 B站",fontWeight=FontWeight.Bold)}
                        if(biliJob!=null)OutlinedButton(onClick={biliJob?.cancel();notice="已取消本次处理，已上传至 B站的图片可能仍保留。"},enabled=progress<.6f,modifier=Modifier.fillMaxWidth()){Text(if(progress>=.6f)"正在投稿或登记，请等待" else "取消处理")}
                        if(hiddenBytes>BiliPartition.MAX_PAYLOAD)Text("隐藏文件超过 100 MiB，请减少文件；载体图大小不计入。",color=MaterialTheme.colorScheme.error)
                    } else if(inlineText!=null)Button(onClick={task("正在加密文字"){progress=.25f;resourceId=withContext(Dispatchers.IO){NativeWorkflow.createInlineText(inlineText)};inlineTextFlow=true;progress=.9f;step=3}},enabled=busy.isEmpty(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=adaptiveAccent(Green))){Text("将内容发放到数据库",fontWeight=FontWeight.Bold)}

                }}
        if(biliState!=BiliLoginState.VERIFIED&&countedPayloads.sumOf{it.file.length()}>10L*1024*1024) InfoBox("隐藏内容超过 10 MB，可在第 2 步选择多张承载图。")
            }
            1 -> {
                Text("选择承载图片",fontSize=29.sp,fontWeight=FontWeight.Black,letterSpacing=(-.7).sp,modifier=Modifier.fillMaxWidth())
                if(carrierSelectionMode==CarrierSelectionMode.DEFAULT) { RoundedImagePreview(firstCover,loader,{preview=firstCover}){replaceFirstDefault=true;openPicker(pickFirst,"image/*","更换默认承载图")};Text("当前使用：默认载体图",fontSize=12.sp,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold) }
                else Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Spacer(Modifier.weight(1f));Text("共 ${covers.size} 张",fontSize=12.sp)}
                    val shown=if(coversExpanded)covers else covers.take(3)
                    shown.forEach{item->key(item.file.absolutePath){
                        ReorderableMediaRow(item.file.absolutePath,busy.isEmpty(),{direction->
                            val from=covers.indexOf(item);val to=from+direction
                            if(from>=0&&to in covers.indices){covers=covers.toMutableList().apply{add(to,removeAt(from))};invalidateGenerated()}
                        }){LegacyPayloadRow(item,covers.indexOf(item)+1,loader,onClick={preview=item},onRemove={covers=covers.filterNot{it.file==item.file};invalidateGenerated()})}
                    }}
                    if(covers.size>1)Text("长按 0.2 秒，上下拖动调整图片位置",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(covers.size>3)TextButton({coversExpanded=!coversExpanded},Modifier.fillMaxWidth()){Text(if(coversExpanded)"收起" else "展开全部 ${covers.size} 张（已折叠 ${covers.size-3} 张）")}
                }}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    OutlinedButton({replaceFirstDefault=true;openPicker(pickFirst,"image/*","更换默认承载图")},Modifier.weight(1f),contentPadding=PaddingValues(horizontal=5.dp)){Text("更换图")}
                    Button({openPicker(pickCovers,"image/*","选择多张承载图")},Modifier.weight(1.25f),contentPadding=PaddingValues(horizontal=5.dp)){Text("选择多张图")}
                    OutlinedButton({carrierSelectionMode=CarrierSelectionMode.DEFAULT;covers=emptyList();coversExpanded=false;invalidateGenerated()},Modifier.weight(1f),enabled=carrierSelectionMode!=CarrierSelectionMode.DEFAULT,contentPadding=PaddingValues(horizontal=5.dp)){Text("使用默认")}
                }
                val estimateCovers=if(carrierSelectionMode==CarrierSelectionMode.DEFAULT)listOf(firstCover) else covers
                val estimatedSecretBytes=payloads.sumOf{it.file.length()}+payloads.size*256L+1024L
                val allocation=currentAllocation()
                val estimatedSizes=NativeWorkflow.estimateCarrierSizes(estimatedSecretBytes,estimateCovers,allocation)
                if(estimatedSizes.isNotEmpty()){
                    Text("预计载体数量：${estimatedSizes.size} 张",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                    Text("每张目标尾追加容量：${if(allocation.mode==CarrierAllocationMode.AUTO)"%.1f MB".format(allocation.targetAppendBytes/1_000_000.0) else "20 MiB 普通分片"}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                    Text("预计生成文件总大小：${humanSize(estimatedSizes.sum())} · 预计缓存：${humanSize(estimatedSizes.sum()+estimatedSecretBytes)}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                    if(estimatedSizes.any{it>5L*1024*1024})Text("预计有图片超过 5 MB，建议选择更多承载图片。",fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                }
                if(generated==null) Primary("加密并生成",busy,estimatedSizes.isNotEmpty()){if(estimatedSizes.size>25)showLargeCarrierWarning=true else generateCarriers()} else Button(onClick={task("正在保存图片"){val g=requireNotNull(generated);g.carriers.forEachIndexed{index,carrier->withContext(Dispatchers.IO){MediaFiles.save(context,carrier.file,carrier.file.name,MediaFiles.mimeOf(carrier.file.name))};progress=.1f+.85f*((index+1f)/g.carriers.size)};carriersDownloaded=true}},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=adaptiveAccent(Green))){Text(if(carriersDownloaded)"已下载" else "下载图片",fontWeight=FontWeight.Bold)}
            }
            2 -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Text("填写公开图片",fontSize=29.sp,fontWeight=FontWeight.Black)
                    Text("直链",fontSize=29.sp,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary,
                        modifier=Modifier.clickable(enabled=busy.isEmpty()){showQqDialog=true})
                }
                Text("可填写图片直链或网盘分享，无需按图片顺序排列。点击登记后自动匹配。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                urls.forEachIndexed { i,value ->
                    OutlinedTextField(value,{v->urls=urls.mapIndexed{at,old->if(at==i)v else old};matchedRemotes=emptyMap();validationSuccess=false},
                        Modifier.fillMaxWidth().padding(vertical=5.dp),label={Text("链接 ${i+1} · 直链或网盘分享")},singleLine=true,enabled=busy.isEmpty())
                }
                if(RemoteRegistration.duplicates(urls)) Text("存在重复链接，登记时会自动去重。",color=MaterialTheme.colorScheme.error)
                TextButton(onClick={urls=urls+""},enabled=busy.isEmpty()){Text("添加链接")}
                Primary("验证并登记",busy,urls.any{it.isNotBlank()}){task("正在解析并校验图片"){
                    val g=requireNotNull(generated)
                    val checked=withContext(Dispatchers.IO){RemoteRegistration.match(context,g.carriers,urls,matchedRemotes,sharePasswords,if(verifyRemote)RemoteValidationLevel.FULL else RemoteValidationLevel.BASIC){state->
                        val fraction=if(state.totalBytes>0)(state.downloadedBytes.toFloat()/state.totalBytes).coerceIn(0f,1f) else 0f
                        scope.launch{busy="正在校验第 ${state.partIndex+1}/${state.partCount} 张 · ${humanSize(state.downloadedBytes)}";progress=.05f+.55f*((state.partIndex+fraction)/state.partCount.coerceAtLeast(1))}
                    }}
                    matchedRemotes=checked.matches;notice=checked.message(g.carriers.size)+(if(!verifyRemote)"\n未进行完整 SHA-256 校验。" else "")
                    checked.passwordRequest?.let{pendingPassword=it;passwordInput=""}
                    checked.loginRequired?.let{error=it.message.orEmpty()}
                    if(checked.matches.size==g.carriers.size&&checked.passwordRequest==null&&checked.loginRequired==null){
                        busy="正在安全登记";progress=.7f
                        resourceId=withContext(Dispatchers.IO){NativeWorkflow.createRecord(g,g.carriers.indices.map{checked.matches.getValue(it)})}
                        validationSuccess=true;step=3
                    }
                }}
                if(validationSuccess)SuccessBox(if(verifyRemote)"完整校验成功" else "基础安全与内容检查通过；未进行完整 SHA-256 校验")
            }
            3 -> { Text("确认盲水印图片",fontSize=29.sp,fontWeight=FontWeight.Black,letterSpacing=(-.7).sp,modifier=Modifier.fillMaxWidth()); RoundedImagePreview(finalCover?:secondCover,loader,{preview=finalCover?:secondCover}){replaceSecondDefault=true;openPicker(pickSecond,"image/*","长按更换盲水印图")};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({replaceSecondDefault=false;openPicker(pickSecond,"image/*","更换盲水印图")},Modifier.weight(1f)){Text("更换图")};OutlinedButton({finalCover=null;result=null},Modifier.weight(1f),enabled=finalCover!=null){Text("使用默认")}}; Primary("确定并嵌入",busy,true){task("正在检查图片质量"){val src=finalCover?:secondCover;progress=.12f;val report=withContext(Dispatchers.IO){DefaultImageStore.textureReport(src)};require(report.accepted){"图片纹理过少或清晰度不足，请更换图片"};busy="正在嵌入隐藏信息";progress=.32f;val out=withContext(Dispatchers.IO){val bmp=decodeBitmap(src);val id=PasscodeCodec.encodeWatermark(resourceId);val marked=bridge.embed(bmp,TrustMarkCodec.encode(id));File(context.cacheDir,"trustmark-result-${System.nanoTime()}.png").also{f->FileOutputStream(f).use{marked.compress(Bitmap.CompressFormat.PNG,100,it)}}};progress=.85f;busy="正在保存到相册";val media=LocalMedia(out,out.name,"image/png");withContext(Dispatchers.IO){MediaFiles.save(context,out,out.name,"image/png")};PasscodeHistory.add(context,PasscodeHistoryKind.UPLOAD,resourceId);progress=.96f;result=media;step=4}} }
            4 -> { Heading("处理完成","最终图片已存进相册，可以直接分享。"); Text("✓",color=adaptiveAccent(Green),fontSize=50.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth());SuccessBox("已存进相册");PasscodeCard(resourceId);result?.let { MediaRow(it,loader,{preview=it}) } }
        }
        if(busy.isNotEmpty()){if(progress<0)LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(top=16.dp))else SmoothLinearProgress(progress,Modifier.fillMaxWidth().padding(top=16.dp));Text(if(progress<0)busy else if(percentOnlyProgress)"${(progress*100).roundToInt()}%" else "$busy ${(progress*100).roundToInt()}%",color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(8.dp))}
        if(notice.isNotEmpty()) InfoBox(notice)
        if(error.isNotEmpty()) ErrorBox(error)
    }
    preview?.let { MediaPreviewDialog(it){preview=null} }
    if(showQqDialog) AlertDialog(onDismissRequest={if(busy.isEmpty())showQqDialog=false},title={Text("QQ 频道原图")},text={Column{
        Text("粘贴 QQ 频道公开帖子链接。确认后会获取原图、自动填写并校验，成功后直接进入下一步。")
        OutlinedTextField(qqLink,{qqLink=it},label={Text("QQ 频道帖子链接")},enabled=busy.isEmpty())
    }},confirmButton={Button(enabled=busy.isEmpty()&&qqLink.isNotBlank(),onClick={task("正在解析 QQ 频道原图"){
        val images=QqChannelClient.originalImages(qqLink)
        registerQqImages(images)
    }}){Text("确认")}},dismissButton={TextButton(onClick={showQqDialog=false},enabled=busy.isEmpty()){Text("取消")}})
    qqBrowserLink?.let{link->QqPageDialog(link,onImages={images->qqBrowserLink=null;task("正在校验 QQ 原图"){
        registerQqImages(images)
    }},onDismiss={qqBrowserLink=null})}
    pendingPassword?.let{share->AlertDialog(onDismissRequest={pendingPassword=null},title={Text("填写提取码")},text={OutlinedTextField(passwordInput,{passwordInput=it},label={Text("分享提取码")})},
        confirmButton={Button(onClick={sharePasswords=sharePasswords+(share to passwordInput.trim());pendingPassword=null;notice="提取码已保存，请点击登记继续。"},enabled=passwordInput.isNotBlank()){Text("确定")}},
        dismissButton={TextButton(onClick={pendingPassword=null}){Text("取消")}})}
    if(showBiliSuggestion) AlertDialog(
        onDismissRequest={continueManually()},
        title={Text("可上传到 B站")},
        text={Text("确定${if(longLinkEnabled)"使用 B站私密专栏长期保存" else "临时上传到 B站"}承载图吗？\n\n隐藏文件 ${humanSize(BiliPayload.size(payloads))}，不含载体图。${if(longLinkEnabled)"上传和投稿" else "上传"}需要较长时间；${if(verifyRemote)"完成后会匿名下载并完整校验。" else "仍会执行基础安全与内容检查，但不比对完整 SHA-256。"}")},
        confirmButton={TextButton(onClick={showBiliSuggestion=false;uploadBili()}){Text("确定上传")}},
        dismissButton={TextButton(onClick={continueManually()}){Text("继续原流程")}},
    )
    if(showSettings) AlertDialog(onDismissRequest={showSettings=false},containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,titleContentColor=MaterialTheme.colorScheme.onSurface,textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,title={Text("隐藏内容设置")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("智能压缩",fontWeight=FontWeight.Bold);Text("仅隐藏图片：失败、变大或动画图片会保留原图",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(smartCompress,{smartCompress=it;settings.edit().putBoolean("smart_compress",it).apply()})};Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("是否压缩视频",fontWeight=FontWeight.Bold);Text("导入后自动等比例压缩；不裁剪、不拉伸，不主动修改帧率",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(compressVideo,{compressVideo=it;settings.edit().putBoolean("compress_video",it).apply()})};Column{Text("WebP 质量：$webpQuality",fontWeight=FontWeight.Bold);Slider(webpQuality.toFloat(),{webpQuality=it.roundToInt().coerceIn(0,100)},onValueChangeFinished={settings.edit().putInt("webp_quality",webpQuality).apply()},valueRange=0f..100f,steps=99,enabled=smartCompress)};Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("自动分配载体图",fontWeight=FontWeight.Bold);Text("仅普通流程；重复当前默认载体图",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(autoAllocate,{autoAllocate=it;settings.edit().putBoolean("auto_allocate_carriers",it).apply();invalidateGenerated()})};Column{Text("单张尾追加容量：${"%.1f".format(targetAppendHalfSteps*.5)} MB",fontWeight=FontWeight.Bold);Slider(targetAppendHalfSteps.toFloat(),{targetAppendHalfSteps=it.roundToInt().coerceIn(2,8)},onValueChangeFinished={settings.edit().putInt("target_append_half_steps",targetAppendHalfSteps).apply();invalidateGenerated()},valueRange=2f..8f,steps=5,enabled=autoAllocate)};Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("是否完整校验",fontWeight=FontWeight.Bold);Text("适用于 B站、图片直链、图床和网盘；关闭后仍执行基础安全与内容检查",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(verifyRemote,{verifyRemote=it;settings.edit().putBoolean("verify_remote",it).putBoolean("verify_bili",it).apply();matchedRemotes=emptyMap();validationSuccess=false})}}},confirmButton={Button({showSettings=false}){Text("完成")}})
    if(showLargeCarrierWarning) AlertDialog(onDismissRequest={showLargeCarrierWarning=false},title={Text("预计载体较多")},text={Text("预计生成超过 25 张载体图。数量较多会增加缓存、上传和链接填写时间，但不会修改你的容量设置。")},confirmButton={Button({showLargeCarrierWarning=false;generateCarriers()}){Text("继续生成")}},dismissButton={TextButton({showLargeCarrierWarning=false}){Text("返回调整")}})
    if(showProxyWarning) AlertDialog(onDismissRequest={showProxyWarning=false},title={Text("检测到代理或 VPN")},text={Text("代理或 VPN 可能导致长时间上传、投稿或校验结果不明。建议关闭后再继续。你仍然可以自行承担风险继续。")},confirmButton={TextButton({showProxyWarning=false;uploadBili(true)}){Text("仍然继续")}},dismissButton={TextButton({showProxyWarning=false}){Text("取消")}})
    if(showImportMenu) AlertDialog(onDismissRequest={showImportMenu=false},containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,titleContentColor=MaterialTheme.colorScheme.onSurface,textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,title={Text("选择导入类型")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TypeButton(Icons.Rounded.Image,"从相册选图片",Modifier.weight(1f)){showImportMenu=false;openPicker(pickImages,"image/*","导入图片")};TypeButton(Icons.Rounded.VideoLibrary,"从相册选视频",Modifier.weight(1f)){showImportMenu=false;openPicker(pickVideos,"video/*","导入视频")}};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TypeButton(Icons.Rounded.InsertDriveFile,"任意文件",Modifier.weight(1f)){showImportMenu=false;openPicker(pickFiles,"*/*","导入任意文件")};TypeButton(Icons.Rounded.Edit,"手打一段文字",Modifier.weight(1f)){showImportMenu=false;showTextInput=true}}}},confirmButton={TextButton({showImportMenu=false}){Text("取消")}})
    if(showTextInput) AlertDialog(onDismissRequest={showTextInput=false},containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,titleContentColor=MaterialTheme.colorScheme.onSurface,textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,title={Text("添加文字")},text={OutlinedTextField(textInput,{textInput=it},modifier=Modifier.focusRequester(textFocusRequester),minLines=5,label={Text("要隐藏的文字")})},confirmButton={Button({if(textInput.isNotBlank()){val f=File(context.cacheDir,"text-${System.nanoTime()}.txt");f.writeText(textInput,Charsets.UTF_8);payloads=payloads+LocalMedia(f,"文字-${payloads.size+1}.txt","text/plain");invalidateGenerated();textInput=""};showTextInput=false}){Text("添加")}},dismissButton={TextButton({showTextInput=false}){Text("取消")}})
}

@Composable
private fun ExtractPage(dark: Boolean, back: () -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val bridge=remember{TrustMarkPlugin(context,BuildConfig.EXTRACT_ONLY)};val loader=remember{ImageLoader.Builder(context).components{add(VideoFrameDecoder.Factory());if(Build.VERSION.SDK_INT>=28)add(AnimatedImageDecoder.Factory())else add(GifDecoder.Factory())}.build()}
    DisposableEffect(Unit){onDispose{Thread{CacheManager.clearTask(context,setOf("imports","remote","restored"))}.start()}}
    var selected by remember{mutableStateOf<List<LocalMedia>>(emptyList())};var reports by remember{mutableStateOf<List<ExtractReport>>(emptyList())};var busy by remember{mutableStateOf(false)};var progress by remember{mutableFloatStateOf(0f)};var progressLabel by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};var preview by remember{mutableStateOf<LocalMedia?>(null)};var gallery by remember{mutableStateOf<Pair<List<LocalMedia>,Int>?>(null)};var showOverview by remember{mutableStateOf(false)};var openedResult by remember{mutableStateOf<ExtractReport?>(null)};var selectedExpanded by remember{mutableStateOf(false)}
    var manualCode by remember{mutableStateOf("")};var manualRestored by remember{mutableStateOf<RestoredFile?>(null)}
    fun reset(){selected=emptyList();reports=emptyList();error="";showOverview=false;openedResult=null;manualRestored=null;preview=null;gallery=null;selectedExpanded=false}
    fun restoreManual(){
        val code=PasscodeCodec.normalize(manualCode)
        if(!PasscodeCodec.isCurrent(code)){error="口令码必须是 5 个英文字母";return}
        scope.launch{
            busy=true;progress=.05f;progressLabel="正在连接服务器";error="";manualRestored=null
            runCatching{
                val record=withContext(Dispatchers.IO){NativeWorkflow.getRecord(code)}
                progress=.3f;progressLabel="正在下载并恢复文件"
                val restored=withContext(Dispatchers.IO){NativeWorkflow.restoreRemote(record,File(context.cacheDir,"manual-${System.nanoTime()}"),context){state->
                    scope.launch{progress=.3f+.65f*state.fraction;progressLabel="正在并发下载 · 已完成 ${state.completedParts}/${state.partCount}"}
                }}
                code to restored
            }.onSuccess{(matched,restored)->PasscodeHistory.add(context,PasscodeHistoryKind.EXTRACT,matched);manualRestored=restored;progress=1f;progressLabel="处理完成"}
                .onFailure{error=it.message?.takeIf(String::isNotBlank)?:"提取失败"}
            delay(160);busy=false
        }
    }
    val scan=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->

        if(uris.isNotEmpty())scope.launch{
            busy=true;progress=.03f;progressLabel="正在导入图片";error="";showOverview=false;openedResult=null
            val imported=runCatching{withContext(Dispatchers.IO){uris.map{MediaFiles.import(context,it)}}}.getOrElse{error=it.message?:"导入图片失败";busy=false;return@launch}
            progress=.1f
            selected=imported;selectedExpanded=false
            reports=imported.map{ExtractReport(it,"正在提取水印并下载…")}
            imported.forEachIndexed{index,image->
                val base=.1f+.9f*(index.toFloat()/imported.size);val span=.9f/imported.size
                progressLabel="正在提取第 ${index+1} 张图片";progress=base+.08f*span
                val decoded=runCatching{withContext(Dispatchers.IO){TrustMarkCodec.decode(bridge.extract(decodeBitmap(image))).first}}
                if(decoded.isFailure){reports=reports.toMutableList().also{it[index]=ExtractReport(image,"未提取到水印",false)};progress=base+span;return@forEachIndexed}
                val candidates=PasscodeCodec.decodeCandidates(decoded.getOrThrow())
                progressLabel="正在连接服务器";progress=base+.45f*span
                var record:ResourceRecord?=null;var matchedCode="";var queryReason="服务器未响应"
                for(candidate in candidates){
                    val attempt=runCatching{withContext(Dispatchers.IO){NativeWorkflow.getRecord(candidate)}}
                    attempt.onSuccess{record=it;matchedCode=it.id}.onFailure{queryReason=it.message?.takeIf(String::isNotBlank)?:queryReason}
                    if(record!=null)break
                }
                if(record==null){reports=reports.toMutableList().also{it[index]=ExtractReport(image,"服务器查询失败：$queryReason",false)};progress=base+span;return@forEachIndexed}
                progressLabel="正在下载并恢复文件";progress=base+.68f*span
                val restoredResult=runCatching{withContext(Dispatchers.IO){NativeWorkflow.restoreRemote(requireNotNull(record),File(context.cacheDir,"remote-${System.nanoTime()}"),context){state->
                    val sizeText=if(state.totalBytes>0)"${humanSize(state.downloadedBytes)}/${humanSize(state.totalBytes)}" else humanSize(state.downloadedBytes)
                    val percentText=" · ${(state.fraction*100).roundToInt()}%"
                    val speedText=if(state.bytesPerSecond>0)" · ${humanSize(state.bytesPerSecond)}/s" else ""
                    scope.launch{progressLabel="并发下载 ${state.completedParts}/${state.partCount} · $sizeText$percentText$speedText";progress=base+span*(.5f+.45f*state.fraction)}
                }}}
                reports=reports.toMutableList().also{list->list[index]=restoredResult.fold(
                    onSuccess={PasscodeHistory.add(context,PasscodeHistoryKind.EXTRACT,matchedCode);ExtractReport(image,"下载并恢复成功 · ${it.name} · ${humanSize(it.file.length())}",true,it)},
                    onFailure={error->ExtractReport(image,error.message?.takeIf{it.isNotBlank()}?:"下载或恢复失败",false)}
                )}
                progress=base+span
            }
            progress=1f;progressLabel="处理完成";delay(180);busy=false;showOverview=true
        }
    }
    fun openScan(){try{scan.launch("image/*");}catch(failure:RuntimeException){error=when(failure){is android.content.ActivityNotFoundException->"系统文件选择器不可用，请启用系统“文件”应用后重试";is SecurityException->"系统拒绝打开文件选择器，请检查系统文件应用权限";else->"无法打开文件选择器，请稍后重试"}}}
    FlowPage(false,"提取",back) {
        Heading("导入含盲水印的图片","导入后自动检测提取")
        if(selected.isEmpty())DropArea("＋","选择一张或多张图片",""){openScan()}
        else {
            val shown=if(selectedExpanded)selected else selected.take(3)
            shown.forEach{item->MediaRow(item,loader,{preview=item})}
            if(selected.size>3)TextButton({selectedExpanded=!selectedExpanded},Modifier.fillMaxWidth()){Text(if(selectedExpanded)"收起" else "展开全部 ${selected.size} 张（已折叠 ${selected.size-3} 张）")}
            if(!busy){OutlinedButton({openScan()},Modifier.fillMaxWidth()){Text("重新选择图片")};if(reports.isNotEmpty())Button({showOverview=true},Modifier.fillMaxWidth()){Text("查看提取概览")}}
        }
        if(busy){SmoothLinearProgress(progress,Modifier.fillMaxWidth().padding(top=8.dp));Text("$progressLabel ${(progress*100).roundToInt()}%",color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(8.dp))}
        if(error.isNotEmpty())ErrorBox(error)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
            OutlinedTextField(
                value=manualCode,
                onValueChange={value->manualCode=value.filter{it in 'a'..'z'||it in 'A'..'Z'}.take(5).uppercase()},
                modifier=Modifier.weight(1f),
                label={Text("5位口令码")},
                placeholder={Text("ABCDE")},
                singleLine=true,
                enabled=!busy,
            )
            Button(onClick={restoreManual()},enabled=!busy&&PasscodeCodec.isCurrent(manualCode),modifier=Modifier.height(56.dp)){Text("提取")}
        }
    }
    preview?.let{MediaPreviewDialog(it){preview=null}}
    gallery?.let{(images,index)->ImageGalleryDialog(images,index){gallery=null}}
    if(showOverview)ExtractOverviewDialog(reports,loader,onOpen={openedResult=it},onDismiss={showOverview=false})
    openedResult?.let{report->report.restored?.let{restored->ExtractRecoveredDialog(restored,loader,onPreview={item,images->if(item.isImage)gallery=images to images.indexOf(item).coerceAtLeast(0) else preview=item},onDismiss={openedResult=null})}}
    manualRestored?.let{restored->ExtractRecoveredDialog(restored,loader,onPreview={item,images->if(item.isImage)gallery=images to images.indexOf(item).coerceAtLeast(0) else preview=item},onDismiss={manualRestored=null},closeLabel="关闭")}
}

private data class ExtractReport(val source:LocalMedia,val detail:String,val success:Boolean?=null,val restored:RestoredFile?=null){val finished:Boolean get()=success!=null}

@Composable
private fun ExtractOverviewDialog(reports:List<ExtractReport>,loader:ImageLoader,onOpen:(ExtractReport)->Unit,onDismiss:()->Unit){
    val success=adaptiveAccent(Green)
    Dialog(onDismissRequest=onDismiss){Card(Modifier.fillMaxWidth().fillMaxHeight(.86f),shape=RoundedCornerShape(22.dp)){Column(Modifier.fillMaxSize().padding(16.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("提取概览",fontSize=21.sp,fontWeight=FontWeight.ExtraBold);Text("本次 ${reports.size} 张图片的结果",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onDismiss){Text("关闭")}};HorizontalDivider();Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top=10.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){reports.forEach{report->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)).clickable(enabled=report.restored!=null){onOpen(report)}.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){AsyncImage(model=report.source.file,imageLoader=loader,contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.size(58.dp).clip(RoundedCornerShape(11.dp)));Column(Modifier.weight(1f)){Text(report.source.displayName,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(report.detail,fontSize=12.sp,color=when(report.success){true->success;false->MaterialTheme.colorScheme.error;null->MaterialTheme.colorScheme.onSurfaceVariant},maxLines=2,overflow=TextOverflow.Ellipsis)};if(report.restored!=null)Text("查看 ›",fontSize=12.sp,color=success,fontWeight=FontWeight.Bold)}}}}}}
}

@Composable
private fun ExtractRecoveredDialog(restored:RestoredFile,loader:ImageLoader,onPreview:(LocalMedia,List<LocalMedia>)->Unit,onDismiss:()->Unit,closeLabel:String="返回概览"){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var saving by remember{mutableStateOf(false)};var saved by remember{mutableStateOf<Set<String>>(emptySet())};val media=remember(restored){LocalMedia(restored.file,restored.name,restored.mime)};val items=remember(restored){restored.children.ifEmpty{listOf(media)}}
    val images=remember(items){items.filter{it.isImage}}
    fun save(targets:List<LocalMedia>){scope.launch{saving=true;runCatching{withContext(Dispatchers.IO){targets.forEach{MediaFiles.save(context,it.file,it.displayName,it.mime)}}}.onSuccess{saved=saved+targets.map{it.file.absolutePath}};saving=false}}
    Dialog(onDismissRequest=onDismiss){
        Card(Modifier.fillMaxWidth().fillMaxHeight(if(items.size>1).86f else .55f),shape=RoundedCornerShape(22.dp)){
            Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text("提取结果",fontSize=21.sp,fontWeight=FontWeight.ExtraBold)
                        if(items.size>1)Text("已自动解压 ${items.size} 个文件",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onDismiss){Text(closeLabel)}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
                    items.forEach{item->
                        val isSaved=item.file.absolutePath in saved
                        MediaRow(
                            item=item,
                            loader=loader,
                            onClick={if(item.isImage||item.isVideo||item.isAudio||item.isText)onPreview(item,images)else onPreview(item,emptyList())},
                            onDownload=if(isSaved)null else ({save(listOf(item))}),
                            downloaded=isSaved,
                            downloadEnabled=!saving,
                        )
                    }
                }
                if(items.all{it.file.absolutePath in saved})SuccessBox(if(items.size>1)"全部文件已保存到本机" else "已保存到本机")
                else Button(onClick={save(items.filterNot{it.file.absolutePath in saved})},enabled=!saving,modifier=Modifier.fillMaxWidth()){
                    Text(if(saving)"正在保存…" else if(items.size>1)"全部保存" else "保存文件")
                }
            }
        }
    }
}

@Composable
private fun FlowPage(
    blue: Boolean,
    title: String,
    back: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base=MaterialTheme.colorScheme
    val pageColor=lerp(base.background,base.primary,.10f)
    Box(Modifier.fillMaxSize().background(pageColor).statusBarsPadding().padding(horizontal=10.dp,vertical=8.dp),contentAlignment=Alignment.TopCenter){
            Card(Modifier.fillMaxWidth().widthIn(max=920.dp).fillMaxHeight(),shape=RoundedCornerShape(32.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
                Column(Modifier.fillMaxSize()){
                    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Surface(onClick=back,shape=CircleShape,color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.size(46.dp)){Box(contentAlignment=Alignment.Center){Text("←",fontSize=23.sp,fontWeight=FontWeight.Bold)}}
                        Text(title,Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.ExtraBold)
                        Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                    header?.let{Box(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=2.dp)){it()}}
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
                    footer?.let{Surface(color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)){Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp,vertical=14.dp)){it()}}}
                }
            }
    }
}
@Composable
private fun StepBar(names: List<String>, step: Int) {
    Row(
        Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.65f)).padding(horizontal=8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { i, name ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(if(i==step)MaterialTheme.colorScheme.primary else if(i<step)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .border(1.dp,if(i<=step)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if(i<step)"✓" else "${i+1}",color=if(i==step)MaterialTheme.colorScheme.onPrimary else if(i<step)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp,fontWeight=FontWeight.Bold)
                }
                Text(name, fontSize = 10.sp, color = if (i <= step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    }
}
@Composable private fun Heading(title:String,text:String){Text(title,fontSize=29.sp,fontWeight=FontWeight.Black,letterSpacing=(-.7).sp,modifier=Modifier.fillMaxWidth());Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=20.sp,modifier=Modifier.fillMaxWidth().padding(top=2.dp,bottom=10.dp))}
@Composable private fun SmoothLinearProgress(progress:Float,modifier:Modifier=Modifier){val animated by animateFloatAsState(progress.coerceIn(0f,1f),tween(durationMillis=420,easing=FastOutSlowInEasing),label="smooth-progress");LinearProgressIndicator(progress={animated},modifier=modifier)}
@Composable private fun TypeButton(icon:ImageVector,text:String,modifier:Modifier=Modifier,onClick:()->Unit){OutlinedButton(onClick,modifier.height(88.dp),shape=RoundedCornerShape(20.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){Icon(icon,contentDescription=null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(25.dp));Text(text,fontSize=12.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)}}}
@Composable private fun LegacyPayloadRow(item:LocalMedia,seq:Int,loader:ImageLoader,onClick:()->Unit,onRemove:()->Unit,detail:String?=null,hideSize:Boolean=false,progress:Float?=null,progressIndeterminate:Boolean=false){
    val kind=when{item.isImage->"图片";item.isVideo->"视频";item.isText->"文本";else->"文件"}
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)).clickable(onClick=onClick).padding(horizontal=10.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){
            if(item.isImage||item.isVideo)AsyncImage(model=item.file,imageLoader=loader,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())else Text(if(item.isText)"☰" else "▤",color=MaterialTheme.colorScheme.primary,fontSize=25.sp)
            if(item.isVideo)Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha=.45f)),contentAlignment=Alignment.Center){Text("▶",color=Color.White,fontSize=11.sp)}
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){Text("$kind$seq",fontWeight=FontWeight.SemiBold,fontSize=14.sp);Text(if(hideSize)item.displayName else "${if(item.sourceSize!=item.file.length())"原 ${humanSize(item.sourceSize)} → 采用 ${humanSize(item.file.length())}" else humanSize(item.file.length())} · ${item.displayName}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis);if(!detail.isNullOrBlank())Text(detail,fontSize=11.sp,color=MaterialTheme.colorScheme.primary,maxLines=2,overflow=TextOverflow.Ellipsis);if(progressIndeterminate)LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(top=3.dp))else if(progress!=null)SmoothLinearProgress(progress,Modifier.fillMaxWidth().padding(top=3.dp))}
        IconButton(onRemove){Icon(Icons.Rounded.Close,contentDescription="移除",tint=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun DefaultImageCard(title:String,item:LocalMedia,loader:ImageLoader,onPreview:()->Unit,onPick:()->Unit,onReplace:()->Unit){
    val context=LocalContext.current;val revision=DefaultImageStore.revision(item);val model=remember(revision){ImageRequest.Builder(context).data(item.file).memoryCacheKey(revision).diskCacheKey(revision).build()}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).border(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.65f),RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.35f)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontWeight=FontWeight.ExtraBold);OutlinedButton(onPick){Text("＋")}}
        AsyncImage(model=model,contentDescription=title,imageLoader=loader,modifier=Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surface).combinedClickable(onClick=onPreview,onLongClick=onReplace),contentScale=ContentScale.Fit)
        Text("${item.displayName} · ${humanSize(item.file.length())}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun RoundedImagePreview(item:LocalMedia,loader:ImageLoader,onPreview:()->Unit,onReplace:()->Unit){
    val context=LocalContext.current;val revision=DefaultImageStore.revision(item);val model=remember(revision){ImageRequest.Builder(context).data(item.file).memoryCacheKey(revision).diskCacheKey(revision).build()}
    val ratio=remember(item.file.absolutePath,item.file.lastModified()){
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(item.file.absolutePath,options)
        if(options.outWidth>0&&options.outHeight>0)(options.outWidth.toFloat()/options.outHeight).coerceIn(.5f,2.5f) else 1f
    }
    AsyncImage(model=model,contentDescription=null,imageLoader=loader,modifier=Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(24.dp)).combinedClickable(onClick=onPreview,onLongClick=onReplace),contentScale=ContentScale.Fit)
}
@Composable private fun DropArea(icon:String,title:String,hint:String,onClick:()->Unit){Column(Modifier.fillMaxWidth().heightIn(min=180.dp).clip(RoundedCornerShape(26.dp)).border(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.45f),RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha=.55f)).clickable(onClick=onClick).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Box(Modifier.size(58.dp).clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.primary),contentAlignment=Alignment.Center){Text(icon,color=MaterialTheme.colorScheme.onPrimary,fontSize=27.sp,fontWeight=FontWeight.Bold)};Spacer(Modifier.height(14.dp));Text(title,fontWeight=FontWeight.ExtraBold,fontSize=17.sp);if(hint.isNotBlank())Text(hint,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=6.dp))}}
@Composable private fun Primary(text:String,busy:String,enabled:Boolean,onClick:()->Unit){Button(onClick,enabled=enabled&&busy.isEmpty(),modifier=Modifier.fillMaxWidth().height(54.dp).padding(top=2.dp),shape=RoundedCornerShape(18.dp)){Text(if(busy.isNotEmpty())busy else text,fontWeight=FontWeight.ExtraBold)}}
@Composable private fun ErrorBox(text:String){Text(text,color=MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer,RoundedCornerShape(18.dp)).border(1.dp,MaterialTheme.colorScheme.error.copy(alpha=.5f),RoundedCornerShape(18.dp)).padding(15.dp))}
@Composable private fun InfoBox(text:String){Text(text,color=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(18.dp)).padding(15.dp))}
@Composable private fun SuccessBox(text:String){val success=adaptiveAccent(Green);Text(text,color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.fillMaxWidth().background(lerp(MaterialTheme.colorScheme.surface,success,.14f),RoundedCornerShape(18.dp)).border(1.dp,success.copy(alpha=.5f),RoundedCornerShape(18.dp)).padding(15.dp),fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)}
@Composable private fun ResultRow(title:String,detail:String,onClick:()->Unit){OutlinedButton(onClick,Modifier.fillMaxWidth()){Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(title,maxLines=1,overflow=TextOverflow.Ellipsis);Text(detail,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable private fun MediaRow(item:LocalMedia,loader:ImageLoader,onClick:()->Unit,onDownload:(()->Unit)?=null,downloaded:Boolean=false,downloadEnabled:Boolean=true,onRemove:(()->Unit)?=null){Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp)).border(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.65f),RoundedCornerShape(21.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.32f)).clickable(onClick=onClick).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.size(62.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){if(item.isImage||item.isVideo)AsyncImage(model=item.file,contentDescription=null,imageLoader=loader,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)else Text(if(item.isAudio)"♪" else if(item.isText)"☰" else "▣",color=MaterialTheme.colorScheme.primary,fontSize=28.sp)};Column(Modifier.weight(1f)){Text(item.displayName,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${humanSize(item.file.length())} · ${item.mime}",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp,maxLines=1)};if(downloaded)Icon(Icons.Rounded.Check,contentDescription="已保存",tint=adaptiveAccent(Green))else onDownload?.let{IconButton(it,enabled=downloadEnabled){Icon(Icons.Rounded.Download,contentDescription="下载")}};onRemove?.let{IconButton(it){Icon(Icons.Rounded.Close,contentDescription="移除",tint=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
private fun decodeBitmap(item:LocalMedia):Bitmap=item.file.inputStream().use{requireNotNull(BitmapFactory.decodeStream(it)){"无法解码图片"}}
private fun humanSize(bytes:Long)=when{bytes<1024->"$bytes B";bytes<1048576->"%.1f KiB".format(bytes/1024.0);else->"%.2f MiB".format(bytes/1048576.0)}
