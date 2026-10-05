package com.example.stocksgnal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class NamuCredentials(val appKey: String, val appSecret: String)
data class NamuAccessToken(val value: String, val expiresAt: Long)
data class NamuConnectionResult(val success: Boolean, val message: String)

/** AppKey와 AppSecret은 Android Keystore 키로 암호화해 이 기기에만 저장한다. */
object NamuSecretStore {
    private const val PREFS = "namu_secure_credentials"
    private const val KEY_ALIAS = "stocksgnal_namu_aes_key"
    private const val APP_KEY = "app_key"
    private const val APP_SECRET = "app_secret"
    private const val ACCESS_TOKEN = "access_token"
    private const val EXPIRES_AT = "expires_at"
    private const val MASSIVE_KEY = "massive_key"
    private const val FMP_KEY = "fmp_key"
    private const val MANUAL_BUY = "manual_buy_receipt"
    private const val MANUAL_BUY_HISTORY = "manual_buy_history"

    fun saveManualBuy(context: Context, value: String) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MANUAL_BUY, encrypt(value)).commit()) { "주문 상태를 저장하지 못했습니다" }
    }

    fun loadManualBuy(context: Context): String? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MANUAL_BUY, null) ?: return null
        return decrypt(saved) ?: error("저장된 주문 상태를 읽지 못했습니다. 나무 앱에서 주문을 확인하세요.")
    }

    fun clearRejectedManualBuy(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MANUAL_BUY).commit())
    }

    fun loadManualBuyHistory(context: Context): String? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MANUAL_BUY_HISTORY, null) ?: return null
        return decrypt(saved) ?: error("이전 주문 내역을 읽지 못했습니다.")
    }

    /** 내역 저장과 현재 주문 해제를 같은 commit으로 처리한다. 실패하면 현재 잠금을 유지한다. */
    @Synchronized
    fun archiveManualBuy(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentCipher = prefs.getString(MANUAL_BUY, null) ?: error("현재 주문이 없습니다.")
        val current = decrypt(currentCipher) ?: error("현재 주문을 읽지 못했습니다.")
        val historyCipher = prefs.getString(MANUAL_BUY_HISTORY, null)
        val history = JSONArray(loadManualBuyHistory(context) ?: "[]")
        val kept = JSONArray()
        // 현재 건을 포함해 최근 100건 보관. 평문은 파일에 기록하지 않는다.
        for (i in (history.length() - 99).coerceAtLeast(0) until history.length()) kept.put(history.getJSONObject(i))
        kept.put(JSONObject(current))
        if (!prefs.edit().putString(MANUAL_BUY_HISTORY, encrypt(kept.toString())).remove(MANUAL_BUY).commit()) {
            val restore = prefs.edit().putString(MANUAL_BUY, currentCipher)
            if (historyCipher == null) restore.remove(MANUAL_BUY_HISTORY) else restore.putString(MANUAL_BUY_HISTORY, historyCipher)
            restore.commit()
            error("주문 내역 저장 실패 · 기존 주문 잠금을 유지합니다.")
        }
    }

    fun save(context: Context, credentials: NamuCredentials) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(APP_KEY, encrypt(credentials.appKey))
            .putString(APP_SECRET, encrypt(credentials.appSecret))
            .apply()
    }

    fun load(context: Context): NamuCredentials? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val appKey = prefs.getString(APP_KEY, null)?.let(::decrypt) ?: return null
        val appSecret = prefs.getString(APP_SECRET, null)?.let(::decrypt) ?: return null
        return NamuCredentials(appKey, appSecret)
    }

    fun saveAccessToken(context: Context, token: String, expiresAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACCESS_TOKEN, encrypt(token))
            .putLong(EXPIRES_AT, expiresAt)
            .apply()
    }

    fun loadAccessToken(context: Context): NamuAccessToken? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(ACCESS_TOKEN, null)?.let(::decrypt) ?: return null
        return NamuAccessToken(token, prefs.getLong(EXPIRES_AT, 0L))
    }

    /** Massive(배당 일정) API 키 — 나무 키와 같은 방식으로 암호화 저장 */
    fun saveMassiveKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MASSIVE_KEY, encrypt(key)).apply()
    }

    fun loadMassiveKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MASSIVE_KEY, null)?.let(::decrypt)?.takeIf { it.isNotBlank() }

    fun clearMassiveKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MASSIVE_KEY).apply()
    }

    fun saveFmpKey(context: Context, key: String) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(FMP_KEY, encrypt(key.trim())).commit()) { "FMP 키 저장 실패" }
    }

    fun loadFmpKey(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(FMP_KEY, null)?.let(::decrypt)?.takeIf { it.isNotBlank() }

    fun clearFmpKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(FMP_KEY).apply()
    }

    fun clearAccessToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(ACCESS_TOKEN)
            .remove(EXPIRES_AT)
            .apply()
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(value: String): String? = runCatching {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            javax.crypto.spec.GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()
}

object NamuAuthApi {
    const val BASE_URL = "https://api.nhplug.com:8443"
    private const val TOKEN_URL = "$BASE_URL/oauth2/token"
    private const val ACCOUNT_URL = "$BASE_URL/n2/acctinfo"

    fun verifyAccountConnection(context: Context): NamuConnectionResult {
        val token = validToken(context) ?: issueToken(context).getOrElse { return NamuConnectionResult(false, it.message ?: "토큰 발급에 실패했습니다.") }
        val first = requestAccounts(token)
        if (first.code != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return first.toConnectionResult()
        }

        clearToken(context)
        val renewedToken = issueToken(context).getOrElse {
            return NamuConnectionResult(false, it.message ?: "토큰 재발급에 실패했습니다.")
        }
        return requestAccounts(renewedToken).toConnectionResult()
    }

    /** 시세 조회 등에서 쓰는 접근토큰. 유효한 저장 토큰이 있으면 재사용하고, 없을 때만 새로 발급한다(재발급마다 보안 알림이 옴). */
    fun accessToken(context: Context): Result<String> = runCatching {
        validToken(context) ?: issueToken(context).getOrThrow()
    }

    fun discardAccessToken(context: Context) = clearToken(context)

    private fun validToken(context: Context): String? {
        val stored = NamuSecretStore.loadAccessToken(context) ?: return null
        return stored.value.takeIf { stored.expiresAt > System.currentTimeMillis() + 60_000L }
    }

    private fun issueToken(context: Context): Result<String> = runCatching {
        val credentials = NamuSecretStore.load(context) ?: error("설정에서 나무증권 AppKey와 AppSecret을 저장해 주세요.")
        val body = listOf(
            "appkey" to credentials.appKey,
            "appsecretkey" to credentials.appSecret,
            "grant_type" to "client_credentials",
            "scope" to "oob"
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) error("토큰 발급 실패: HTTP ${connection.responseCode}")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: error("토큰 발급 응답에 접근토큰이 없습니다.")
            val expiresIn = json.optLong("expires_in", 86_400L)
            NamuSecretStore.saveAccessToken(context, token, System.currentTimeMillis() + expiresIn * 1_000L)
            token
        } finally {
            connection.disconnect()
        }
    }

    private data class AccountAttempt(val code: Int, val message: String)

    private fun requestAccounts(token: String): AccountAttempt {
        val connection = (URL(ACCOUNT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json;charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            connection.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            AccountAttempt(code, response)
        } catch (e: Exception) {
            AccountAttempt(-1, e.message ?: "네트워크 연결에 실패했습니다.")
        } finally {
            connection.disconnect()
        }
    }

    private fun AccountAttempt.toConnectionResult(): NamuConnectionResult {
        if (code !in 200..299) return NamuConnectionResult(false, "나무증권 연동 실패: HTTP $code")
        val json = runCatching { JSONObject(message) }.getOrNull()
        return if (json?.optString("rsp_cd") == "00000") NamuConnectionResult(true, "나무증권 연동 완료")
        else NamuConnectionResult(false, json?.optString("rsp_msg").takeUnless { it.isNullOrBlank() } ?: "나무증권 연동에 실패했습니다.")
    }

    private fun clearToken(context: Context) {
        NamuSecretStore.clearAccessToken(context)
    }
}
