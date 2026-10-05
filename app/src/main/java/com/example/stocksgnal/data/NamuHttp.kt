package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 나무증권(NH PLUG) REST 공통 요청.
 * - 저장된 접근토큰을 재사용하고, 401이면 한 번만 재발급한다(재발급마다 보안 알림이 오므로 최소화).
 * - 초당 5회 한도 → 요청 사이 250ms 이상 간격.
 * - 연속조회: 응답 헤더 cts_flag=Y 이면 cts 값을 다음 요청 헤더에 넣어 이어 받는다.
 */
object NamuHttp {

    /** 토큰·네트워크처럼 다른 요청도 실패할 문제 */
    class Fatal(message: String) : Exception(message)

    data class Response(val json: JSONObject, val cts: String?, val hasMore: Boolean)

    private const val MIN_GAP_MS = 250L
    private var lastCallAt = 0L

    fun post(context: Context, path: String, body: JSONObject, cts: String? = null): Result<Response> {
        if (!isNamuReadOnlyPath(path)) return Result.failure(
            IllegalArgumentException("조회용 연결로 주문 또는 등록 요청을 보낼 수 없습니다.")
        )
        var token = NamuAuthApi.accessToken(context).getOrElse {
            return Result.failure(Fatal(it.message ?: "나무증권 토큰 발급 실패"))
        }
        repeat(3) { attempt ->
            throttle()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(NamuAuthApi.BASE_URL + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("cts_flag", if (cts.isNullOrBlank()) "N" else "Y")
                    if (!cts.isNullOrBlank()) {
                        setRequestProperty("cts", cts)
                        setRequestProperty("cts_flag", "Y")
                    }
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                when {
                    code == 401 && attempt == 0 -> {
                        NamuAuthApi.discardAccessToken(context)
                        token = NamuAuthApi.accessToken(context).getOrElse {
                            return Result.failure(Fatal(it.message ?: "나무증권 토큰 재발급 실패"))
                        }
                    }
                    code == 429 && attempt < 2 -> Thread.sleep(1_200L)
                    code !in 200..299 -> return Result.failure(
                        if (code == 401) Fatal("나무증권 인증 실패") else IllegalStateException("HTTP $code")
                    )
                    else -> {
                        val json = runCatching { JSONObject(text) }.getOrElse {
                            return Result.failure(IllegalStateException("나무증권 응답을 읽을 수 없습니다."))
                        }
                        val nextCts = connection.getHeaderField("cts")?.trim()
                        val flag = connection.getHeaderField("cts_flag")?.trim()?.uppercase()
                        val more = !nextCts.isNullOrBlank() && (flag == "Y" ||
                            (flag.isNullOrBlank() && json.optString("rsp_cd") in setOf("00165", "00218")))
                        return Result.success(Response(json, nextCts, more))
                    }
                }
            } catch (e: Exception) {
                return Result.failure(Fatal("나무증권 연결 실패: ${e.message ?: "네트워크 오류"}"))
            } finally {
                connection?.disconnect()
            }
        }
        return Result.failure(IllegalStateException("나무증권 요청 실패"))
    }

    /** rsp_cd가 정상인지. 가이드 예제에 00000(조회완료)과 00166(조회가 완료되었습니다)이 섞여 있다. */
    fun isOk(json: JSONObject): Boolean {
        val code = json.optString("rsp_cd")
        return code.isBlank() || code == "00000" || code == "00166"
    }

    fun message(json: JSONObject, fallback: String): String = json.optString("rsp_msg").ifBlank { fallback }

    @Synchronized
    private fun throttle() {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallAt)
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }
}

/** 나무증권 응답의 숫자는 숫자 또는 문자열로 온다 */
internal fun JSONObject.namuNum(key: String): Double? =
    if (!has(key) || isNull(key)) null else optString(key).trim().replace(",", "").toDoubleOrNull()
