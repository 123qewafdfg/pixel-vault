package com.trustmark.share

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLDecoder
import java.util.Base64

class BiliApiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun cookies()=BiliCookies().apply{replace(listOf("SESSDATA","bili_jct").map{Cookie.Builder().name(it).value("test-only").domain("bilibili.com").path("/").build()})}
    @Test fun cookieScopeAndExpirationAreRespected() {
        val jar=cookies()
        assertEquals(2,jar.loadForRequest("https://api.bilibili.com/".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        jar.saveFromResponse("https://api.bilibili.com/".toHttpUrl(),listOf(Cookie.Builder().name("SESSDATA").value("expired").domain("bilibili.com").expiresAt(1).build()))
        assertNull(jar.credential("SESSDATA"))
    }
    @Test fun qrStatesAndAuthenticationErrors() = runBlocking {
        MockWebServer().use{server->
            server.start();val url=server.url("/").toString();val api=BiliApi(cookies(),BiliEndpoints(url,url,url,url))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"url":"https://passport.bilibili.com/qr","qrcode_key":"test-key"}}"""))
            assertEquals("test-key",api.generate().key)
            for((code,state) in listOf(86101 to BiliPoll.WAITING,86090 to BiliPoll.SCANNED,86038 to BiliPoll.EXPIRED,0 to BiliPoll.CONFIRMED)) {
                server.enqueue(MockResponse().setBody("""{"code":0,"data":{"code":$code}}"""));assertEquals(state,api.poll("test-key"))
            }
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"isLogin":true,"mid":123,"level_info":{"current_level":4}}}"""));assertEquals(4,api.account()!!.level)
            server.enqueue(MockResponse().setBody("""{"code":-101,"data":{}}"""))
            try{api.verify();fail()}catch(_:BiliAuthenticationRequired){}
        }
    }
    @Test fun missingCredentialsDoNotCallUpstream() = runBlocking {
        val api=BiliApi()
        assertFalse(api.verify())
        try{api.upload(temp.newFile());fail()}catch(_:BiliAuthenticationRequired){}
    }
    @Test fun rawPngAndAppendedDataReachUploadUnchanged() = runBlocking {
        MockWebServer().use{server->
            server.start();val url=server.url("/").toString();val api=BiliApi(cookies(),BiliEndpoints(url,url,url,url))
            val bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6foAAAAASUVORK5CYII=")+ByteArray(8192){it.toByte()}
            val file=temp.newFile().apply{writeBytes(bytes)}
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"image_url":"http://i0.hdslb.com/test.png","image_width":1,"image_height":1,"img_size":9}}"""))
            assertEquals("https://i0.hdslb.com/test.png",api.upload(file).url)
            val request=server.takeRequest()
            assertEquals("test-only",request.requestUrl!!.queryParameter("csrf"))
            assertTrue(request.body.size>bytes.size)
        }
    }
    @Test fun streamingBodyReportsRealProgress() {
        val bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6foAAAAASUVORK5CYII=")+ByteArray(4099){(it*37).toByte()}
        val file=temp.newFile().apply{writeBytes(bytes)}
        var sent=0L;var total=0L
        val body=ProgressRequestBody(file.asRequestBody("image/png".toMediaType())){done,size->sent=done;total=size}
        val actual=Buffer().also{body.writeTo(it)}.readByteArray()
        assertArrayEquals(bytes,actual);assertEquals(bytes.size.toLong(),sent);assertEquals(sent,total)
    }
    @Test fun privateArticlePayloadIsForcedAndReturnsId() = runBlocking {
        MockWebServer().use{server->server.start();val url=server.url("/").toString();val api=BiliApi(cookies(),BiliEndpoints(url,url,url,url,url))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"isLogin":true,"mid":123,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/bfs/wbi/fedcba9876543210fedcba9876543210.png"}}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"dyn_id_str":"12345"}}"""))
            assertEquals("12345",api.publishPrivate("AbCdE",listOf(BiliUploadedImage("https://i0.hdslb.com/test.png",1,1,9.0))))
            server.takeRequest()
            val request=server.takeRequest()
            assertEquals("test-only",request.requestUrl!!.queryParameter("csrf"))
            assertEquals("main_web",request.requestUrl!!.queryParameter("gaia_source"))
            assertTrue(request.requestUrl!!.queryParameter("w_opus_req.upload_id")!!.matches(Regex("123_\\d+_\\d+")))
            assertTrue(request.requestUrl!!.queryParameter("w_rid")!!.matches(Regex("[0-9a-f]{32}")))
            assertTrue(request.requestUrl!!.queryParameter("wts")!!.matches(Regex("\\d+")))
            val payload=org.json.JSONObject(request.body.readUtf8())
            val opusReq=payload.getJSONObject("opus_req")
            assertEquals(1,opusReq.getJSONObject("option").getInt("private_pub"))
            assertEquals(2,opusReq.getJSONObject("option").getInt("aigc"))
            assertEquals(12,opusReq.getInt("scene"))
            assertEquals("create.creative.h5",opusReq.getJSONObject("meta").getJSONObject("app_meta").getString("from"))
            assertEquals("eva3-4.0.0",opusReq.getJSONObject("opus").getJSONObject("pub_info").getString("editor_version"))
            assertEquals(15,opusReq.getJSONObject("opus").getJSONObject("article").getInt("category_id"))
            assertEquals(0,opusReq.getJSONObject("opus").getJSONObject("content").getJSONArray("paragraphs").getJSONObject(0).getJSONObject("pic").getInt("style"))
            assertEquals(opusReq.getString("upload_id"),request.requestUrl!!.queryParameter("w_opus_req.upload_id"))
        }
    }
    @Test fun unsafeImageDomainsAreRejected() {
        for(url in listOf("https://biliimg.com.attacker.example/x","https://user:pass@i0.hdslb.com/x","https://example.com/x"))
            assertThrows(IllegalArgumentException::class.java){BiliApi.imageUrl(url)}
    }
    @Test fun cancelClosesTheRequestWithoutRetrying() = runBlocking {
        MockWebServer().use{server->
            server.start();val url=server.url("/").toString();val api=BiliApi(cookies(),BiliEndpoints(url,url,url,url))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}""").setBodyDelay(3,java.util.concurrent.TimeUnit.SECONDS))
            val task=launch { api.generate() }
            assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)})
            kotlinx.coroutines.withTimeout(2_000){task.cancel();task.join()}
            assertTrue(task.isCancelled);assertEquals(1,server.requestCount)
        }
    }
}
