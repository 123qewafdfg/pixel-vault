package com.trustmark.share

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class BiliLoginState { LOGGED_OUT, VERIFIED, UNVERIFIED }

/** Stored in noBackupFilesDir: encrypted cookies must not migrate without their Keystore key. */
private class BiliCredentialStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "bili-session.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("yinxiang-bilibili", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("yinxiang-bilibili", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<Cookie> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        require(bytes.size > 28) { "B站本地凭据损坏，请重新登录" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12))) }
        val array = JSONArray(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8))
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            Cookie.Builder().name(value.getString("name")).value(value.getString("value"))
                .apply { if (value.getBoolean("hostOnly")) hostOnlyDomain(value.getString("domain")) else domain(value.getString("domain")) }
                .path(value.getString("path")).expiresAt(value.getLong("expiresAt"))
                .apply { if (value.getBoolean("secure")) secure(); if (value.getBoolean("httpOnly")) httpOnly() }.build()
        }.filter { it.expiresAt > System.currentTimeMillis() }
    }
    fun save(cookies: List<Cookie>) {
        val array = JSONArray().apply { cookies.forEach { cookie -> put(JSONObject().apply {
            put("name",cookie.name); put("value",cookie.value); put("domain",cookie.domain); put("path",cookie.path)
            put("expiresAt",cookie.expiresAt); put("secure",cookie.secure); put("httpOnly",cookie.httpOnly); put("hostOnly",cookie.hostOnly)
        }) } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val temporary = File(file.parentFile, "bili-session.tmp")
        try {
            temporary.outputStream().use { it.write(cipher.iv); it.write(cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))) }
            android.system.Os.rename(temporary.absolutePath, file.absolutePath)
        } finally { temporary.delete() }
    }
    fun clear() { if (file.exists()) check(file.delete()) { "无法清除本地 B站凭据" } }
}

class BiliSession private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = BiliCredentialStore(context)
    private var api = BiliApi()
    private var candidate: BiliApi? = null
    private var qr: BiliQr? = null
    private var deadline = 0L
    private val mutex = Mutex()
    private var loaded = false
    private val mutableState = MutableStateFlow(BiliLoginState.LOGGED_OUT)
    val state = mutableState.asStateFlow()

    suspend fun verify(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { verifyLocked() } }
    suspend fun account(): BiliAccount = withContext(Dispatchers.IO) { mutex.withLock {
        if (!loaded) verifyLocked()
        requireNotNull(api.account()) { "请先登录 B站" }
    } }
    private suspend fun verifyLocked(): Boolean {
        if (!loaded) {
            loaded = true
            try { api.cookies.replace(store.load()) } catch (_: Exception) {
                mutableState.value = BiliLoginState.LOGGED_OUT
                error("B站本地凭据无法读取，请重新扫码登录")
            }
        }
        return try {
            val valid = api.verify()
            mutableState.value = if (valid) BiliLoginState.VERIFIED else BiliLoginState.LOGGED_OUT
            if (valid) store.save(api.cookies.snapshot())
            valid
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: BiliAuthenticationRequired) { mutableState.value = BiliLoginState.LOGGED_OUT; false }
        catch (failure: Exception) {
            if (api.cookies.snapshot().isNotEmpty()) mutableState.value = BiliLoginState.UNVERIFIED
            throw failure
        }
    }
    suspend fun generate(): BiliQr = withContext(Dispatchers.IO) { mutex.withLock {
        val next = BiliApi()
        val fresh = next.generate()
        candidate = next; qr = fresh; deadline = android.os.SystemClock.elapsedRealtime() + 180_000
        fresh
    } }
    suspend fun poll(key: String): BiliPoll = withContext(Dispatchers.IO) { mutex.withLock {
        require(qr?.key == key) { "二维码已刷新，请使用新二维码" }
        if (android.os.SystemClock.elapsedRealtime() >= deadline) return@withLock BiliPoll.EXPIRED
        val next = requireNotNull(candidate)
        val result = next.poll(key)
        if (result == BiliPoll.CONFIRMED) {
            require(next.verify()) { "扫码已确认，但登录凭据校验失败，请重新扫码" }
            store.save(next.cookies.snapshot()); api = next; loaded = true
            mutableState.value = BiliLoginState.VERIFIED; candidate = null; qr = null
        }
        result
    } }
    suspend fun upload(file: File, progress: (Long, Long) -> Unit = { _, _ -> }): BiliUploadedImage = withContext(Dispatchers.IO) { mutex.withLock {
        check(mutableState.value == BiliLoginState.VERIFIED) { "请先登录 B站" }
        try { api.upload(file, progress) } catch (failure: BiliAuthenticationRequired) {
            mutableState.value = BiliLoginState.LOGGED_OUT; throw failure
        }
    } }
    suspend fun publishPrivate(title: String, images: List<BiliUploadedImage>): String = withContext(Dispatchers.IO) { mutex.withLock {
        check(mutableState.value == BiliLoginState.VERIFIED) { "请先登录 B站" }
        api.publishPrivate(title, images)
    } }
    suspend fun logout() = withContext(Dispatchers.IO) { mutex.withLock {
        store.clear(); api = BiliApi(); candidate = null; qr = null; loaded = true
        appContext.getSharedPreferences("bili_features", Context.MODE_PRIVATE).edit().remove("long_link_enabled").apply()
        mutableState.value = BiliLoginState.LOGGED_OUT
    } }
    companion object {
        @Volatile private var instance: BiliSession? = null
        fun get(context: Context): BiliSession = instance ?: synchronized(this) {
            instance ?: BiliSession(context.applicationContext).also { instance = it }
        }
    }
}
