package com.trustmark.share

import android.content.Context
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.repository.*
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

data class CloudLocator(val platform: String, val shareUrl: String, val fileId: String, val password: String? = null) {
    fun json(contentSha256: String) = JSONObject().put("platform", platform).put("shareUrl", shareUrl).put("fileId", fileId)
        .put("contentSha256", contentSha256)
        .apply { password?.let { put("password", it) } }
    companion object {
        fun fromJson(value: JSONObject) = CloudLocator(value.getString("platform"), value.getString("shareUrl"),
            value.getString("fileId"), value.optString("password").takeIf { it.isNotBlank() })
    }
}

class CloudLoginRequired(val platform: SharePlatform, expired: Boolean = false) : IllegalStateException(
    (if(expired) "${CloudDriveService.label(platform)}登录已失效。" else "") + "请在主界面登录${CloudDriveService.label(platform)}后重新登记或提取")
class SharePasswordRequired(val shareUrl: String) : IllegalStateException("此分享需要提取码，请填写后重新登记")
data class RemoteCandidate(val url: String, val locator: CloudLocator? = null,
    val headers: Map<String, String> = emptyMap(), val size: Long = -1L, val cleanup: suspend () -> Unit = {})

class CloudDriveService private constructor(context: Context) {
    private val db = AppDatabase.get(context.applicationContext)
    private val quarkApi = QuarkApi()
    private val ucApi = UCApi()
    private val xunleiApi = XunleiApi()
    val quark = QuarkAccountRepository(db.quarkAccountDao(), quarkApi)
    val uc = UCAccountRepository(db.ucAccountDao(), ucApi)
    val xunlei = XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
    val baidu = BaiduAccountRepository(db.baiduAccountDao(), BaiduApi())
    val c139 = C139AccountRepository(db.c139AccountDao())
    val pan123 = Pan123AccountRepository(db.pan123AccountDao(), Pan123Api())
    val pan115 = Pan115AccountRepository(db.pan115AccountDao(), Pan115Api())
    val guangya = GuangYaAccountRepository(db.guangyaAccountDao(), GuangYaApi())
    val ilanzou = ILanzouAccountRepository(db.ilanzouAccountDao(), ILanzouApi())
    val lanzou = LanzouAccountRepository(db.lanzouAccountDao(), LanzouApi())
    private val repos: Map<SharePlatform, ShareResolveRepository> = mapOf(
        SharePlatform.QUARK to QuarkResolveRepository(quarkApi),
        SharePlatform.UC to UCResolveRepository(ucApi),
        SharePlatform.XUNLEI to XunleiResolveRepository(xunleiApi,
            { xunlei.getAccount()?.accessToken }, { xunlei.getAccount()?.deviceId }, { xunlei.getAccount()?.captchaToken },
            { xunlei.getAccount()?.let { account -> xunleiApi.refreshToken(account.refreshToken, account.deviceId, account.authType)?.also {
                xunlei.updateTokens(it.first, it.second)
            } } }),
        SharePlatform.BAIDU to BaiduResolveRepository(BaiduApi()),
        SharePlatform.C139 to C139ResolveRepository(C139Api()),
        SharePlatform.PAN123 to Pan123ResolveRepository(Pan123Api()) { pan123.getAccount()?.accessToken },
        SharePlatform.PAN115 to Pan115ResolveRepository(Pan115Api()),
        SharePlatform.GUANGYA to GuangYaResolveRepository(GuangYaApi()) { guangya.ensureAccessToken() },
        SharePlatform.ILANZOU to ILanzouResolveRepository(ILanzouApi(), ilanzou),
        SharePlatform.LANZOU to LanzouResolveRepository(LanzouApi()),
    )

    suspend fun credential(platform: SharePlatform): String? = when (platform) {
        SharePlatform.QUARK -> quark.getFreshCookie()
        SharePlatform.UC -> uc.getFreshCookie()
        SharePlatform.XUNLEI -> xunlei.getAccount()?.accessToken
        SharePlatform.BAIDU -> baidu.getAccount()?.cookie
        SharePlatform.C139 -> c139.getAccount()?.cookie
        SharePlatform.PAN123 -> pan123.getAccount()?.accessToken
        SharePlatform.PAN115 -> pan115.getAccount()?.cookie
        SharePlatform.GUANGYA -> guangya.ensureAccessToken()
        SharePlatform.ILANZOU -> ilanzou.getAccount()?.appToken
        SharePlatform.LANZOU -> lanzou.getAccount()?.cookie
        SharePlatform.GITHUB -> null
    }

    suspend fun loggedIn(platform: SharePlatform): Boolean = when (platform) {
        SharePlatform.QUARK -> quark.getAccount() != null
        SharePlatform.UC -> uc.getAccount() != null
        SharePlatform.XUNLEI -> xunlei.getAccount() != null
        SharePlatform.BAIDU -> baidu.getAccount() != null
        SharePlatform.C139 -> c139.getAccount() != null
        SharePlatform.PAN123 -> pan123.getAccount() != null
        SharePlatform.PAN115 -> pan115.getAccount() != null
        SharePlatform.GUANGYA -> guangya.getAccount() != null
        SharePlatform.ILANZOU -> ilanzou.getAccount() != null
        SharePlatform.LANZOU -> lanzou.getAccount() != null
        SharePlatform.GITHUB -> false
    }

    private fun authenticationExpired(failure: Throwable): Boolean = generateSequence(failure){it.cause}.take(8).any {
        (it is QuarkApiException&&it.code==31001) ||
            Regex("(?i)(?:登录|登陆)(?:状态)?(?:已)?(?:失效|过期|无效)|未登录|身份验证失败|access[_ ]?token[ _-]*(?:expired|invalid)|unauthenticated").containsMatchIn(it.message.orEmpty())
    }

    private suspend fun fail(platform: SharePlatform, failure: Throwable): Nothing {
        if(authenticationExpired(failure)){
            // Clear only this platform's encrypted account row, not the shared WebView cookie jar.
            when(platform){
                SharePlatform.QUARK -> db.rawQuarkAccountDao().clear()
                SharePlatform.UC -> db.rawUcAccountDao().clear()
                SharePlatform.XUNLEI -> db.rawXunleiAccountDao().clear()
                SharePlatform.BAIDU -> db.rawBaiduAccountDao().clear()
                SharePlatform.C139 -> db.rawC139AccountDao().clear()
                SharePlatform.PAN123 -> db.rawPan123AccountDao().clear()
                SharePlatform.PAN115 -> db.rawPan115AccountDao().clear()
                SharePlatform.GUANGYA -> db.rawGuangYaAccountDao().clear()
                SharePlatform.ILANZOU -> db.rawILanzouAccountDao().clear()
                SharePlatform.LANZOU -> db.rawLanzouAccountDao().clear()
                SharePlatform.GITHUB -> Unit
            }
            throw CloudLoginRequired(platform,true)
        }
        throw failure
    }

    data class ShareListing(val platform: SharePlatform, val raw: String, val password: String?, val repo: ShareResolveRepository,
        val session: ShareSession, val credential: String, val files: List<ShareFile>)

    suspend fun list(raw: String, password: String? = null): ShareListing {
        val parsed = requireNotNull(ShareLinkParser.parse(raw)) { "无法识别网盘分享链接" }
        val shareUrl = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value
            ?.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'') ?: error("分享链接必须使用 HTTPS")
        val uri=java.net.URI(shareUrl)
        require(uri.userInfo==null&&uri.port in listOf(-1,443)&&trustedHost(parsed.platform,uri.host.orEmpty())){"分享地址与网盘平台不一致"}
        val repo = requireNotNull(repos[parsed.platform]) { "GitHub 文件请填写公开下载地址" }
        val credential = credential(parsed.platform)?.takeIf { it.isNotBlank() } ?: throw CloudLoginRequired(parsed.platform)
        val pass = password?.takeIf { it.isNotBlank() } ?: parsed.pwd
        val session = repo.createSession(raw, pass, credential).getOrElse { failure ->
            if(failure is kotlinx.coroutines.CancellationException)throw failure
            if(authenticationExpired(failure))fail(parsed.platform,failure)
            val message = failure.message.orEmpty()
            if (pass.isNullOrBlank() && listOf("提取码", "访问码", "密码", "passcode", "password").any { message.contains(it, true) })
                throw SharePasswordRequired(raw)
            fail(parsed.platform,IllegalStateException("${label(parsed.platform)}分享解析失败：${message.ifBlank { "请检查分享是否有效，稍后重试" }}", failure))
        }
        val files = mutableListOf<ShareFile>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(rootId(parsed.platform) to 0)
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (directory, depth) = pending.removeFirst()
            if (!seen.add(directory)) continue
            require(seen.size <= 500 && depth <= 20) { "分享目录过多或层级过深，请使用较小的分享文件夹" }
            repo.listFiles(session, directory, credential).getOrElse { fail(parsed.platform,it) }.forEach { file ->
                if (file.isdir) pending.add(file.fid to depth + 1) else files.add(file)
            }
            require(files.size <= 5000) { "分享文件过多，请缩小分享范围" }
        }
        return ShareListing(parsed.platform, shareUrl, pass, repo, session, credential, files.distinctBy { it.fid })
    }

    suspend fun candidate(listing: ShareListing, file: ShareFile): RemoteCandidate {
        val noSave = listing.repo.getShareDownloadLinkWithoutSave(listing.session, file, listing.credential)
        val link = if (noSave.isFailure && listing.platform == SharePlatform.QUARK)
            listing.repo.getShareDownloadLink(listing.session, file, listing.credential).getOrElse { fail(listing.platform,it) }
        else noSave.getOrElse { fail(listing.platform,it) }
        require(!link.isHls) { "必须下载原始图片，不能使用转码流" }
        val headers = when (listing.platform) {
            SharePlatform.QUARK -> mapOf("Cookie" to (quark.getFreshCookie() ?: listing.credential), "User-Agent" to QuarkConstants.USER_AGENT, "Referer" to "https://pan.quark.cn/")
            SharePlatform.UC -> mapOf("Cookie" to (uc.getFreshCookie() ?: listing.credential), "User-Agent" to UCConstants.USER_AGENT, "Referer" to UCConstants.DOWNLOAD_REFERER, "Origin" to "https://drive.uc.cn")
            SharePlatform.BAIDU -> mapOf("Cookie" to listing.credential, "User-Agent" to BaiduConstants.UA_NETDISK)
            SharePlatform.XUNLEI -> mapOf("User-Agent" to XunleiConstants.APP_UA)
            SharePlatform.C139 -> mapOf("User-Agent" to C139Constants.PC_UA)
            SharePlatform.PAN123 -> mapOf("User-Agent" to Pan123Constants.WEB_UA, "Referer" to Pan123Constants.DOWNLOAD_REFERER)
            SharePlatform.PAN115 -> mapOf("Cookie" to Pan115Constants.mergeCookies(listing.credential, link.guestCookie), "User-Agent" to Pan115Constants.CLIENT_UA, "Referer" to Pan115Constants.DOWNLOAD_REFERER)
            SharePlatform.GUANGYA -> mapOf("User-Agent" to GuangYaConstants.WEB_UA, "Referer" to GuangYaConstants.DOWNLOAD_REFERER)
            SharePlatform.ILANZOU -> mapOf("User-Agent" to ILanzouConstants.WEB_UA, "Referer" to ILanzouConstants.DOWNLOAD_REFERER, "Origin" to ILanzouConstants.WEB_SITE)
            SharePlatform.LANZOU -> mapOf("User-Agent" to LanzouConstants.WEB_UA, "Referer" to java.net.URI(link.downloadUrl).let { "${it.scheme}://${it.host}/" }, "Cookie" to LanzouConstants.DOWN_IP_COOKIE)
            SharePlatform.GITHUB -> emptyMap()
        }
        return RemoteCandidate(link.downloadUrl, CloudLocator(listing.platform.name, listing.raw, file.fid, listing.password), headers, file.fsize) {
            link.cleanupDirFid?.let { listing.repo.cleanupTempDir(it, listing.credential) }
        }
    }

    suspend fun resolve(locator: CloudLocator): RemoteCandidate {
        val listing = list(locator.shareUrl, locator.password)
        require(listing.platform.name == locator.platform) { "网盘平台与记录不一致" }
        val file = listing.files.firstOrNull { it.fid == locator.fileId } ?: error("分享中的承载图片已删除或移动")
        return candidate(listing, file)
    }

    companion object {
        @Volatile private var instance: CloudDriveService? = null
        fun get(context: Context): CloudDriveService = instance ?: synchronized(this) {
            instance ?: CloudDriveService(context.applicationContext).also { instance = it }
        }
        fun install(context: Context) {
            XunleiDeviceFingerprint.init(context.applicationContext)
            Pan123DeviceId.install(context.applicationContext)
            com.yunx.app.data.security.CredentialStore.installRecovery(context.applicationContext)
        }
        fun platform(raw: String) = ShareLinkParser.parse(raw)?.platform?.takeIf { it != SharePlatform.GITHUB }
        internal fun trustedHost(platform:SharePlatform,rawHost:String):Boolean {
            val host=rawHost.lowercase()
            val allowed=when(platform){
                SharePlatform.QUARK -> listOf("pan.quark.cn")
                SharePlatform.UC -> listOf("drive.uc.cn")
                SharePlatform.XUNLEI -> listOf("pan.xunlei.com")
                SharePlatform.BAIDU -> listOf("pan.baidu.com")
                SharePlatform.C139 -> listOf("yun.139.com")
                SharePlatform.PAN123 -> listOf("123pan.com","123pan.cn","123684.com","123865.com","123912.com","123592.com")
                SharePlatform.PAN115 -> listOf("115.com","115cdn.com","115rc.com")
                SharePlatform.GUANGYA -> listOf("guangyapan.com")
                SharePlatform.ILANZOU -> listOf("ilanzou.com","ilanzou.net","ilanzou.org","ilanzou.cn")
                SharePlatform.LANZOU -> return Regex("^(?:[a-z0-9-]+\\.)*(?:lanzou[a-z0-9-]*|lan[zs]o[ux])\\.(?:com|net|org|cn)$").matches(host)
                SharePlatform.GITHUB -> listOf("github.com","raw.githubusercontent.com")
            }
            return allowed.any{host==it||host.endsWith(".$it")}
        }
        fun label(platform: SharePlatform) = when (platform) {
            SharePlatform.QUARK -> "夸克网盘"; SharePlatform.UC -> "UC网盘"; SharePlatform.XUNLEI -> "迅雷网盘"
            SharePlatform.BAIDU -> "百度网盘"; SharePlatform.C139 -> "139网盘"; SharePlatform.PAN123 -> "123云盘"
            SharePlatform.PAN115 -> "115网盘"; SharePlatform.GUANGYA -> "光鸭云盘"; SharePlatform.ILANZOU -> "蓝奏优享"
            SharePlatform.LANZOU -> "蓝奏云"; SharePlatform.GITHUB -> "GitHub"
        }
        private fun rootId(platform: SharePlatform) = when (platform) {
            SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
            SharePlatform.BAIDU, SharePlatform.GUANGYA, SharePlatform.ILANZOU, SharePlatform.LANZOU -> ""
            SharePlatform.PAN115 -> Pan115Constants.ROOT_CID
            else -> "0"
        }
    }
}
