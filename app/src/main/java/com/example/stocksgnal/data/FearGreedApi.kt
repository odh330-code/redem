package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * CNN Fear & Greed Index (공포탐욕지수)
 *
 * 공식 API가 없어 CNN 웹페이지가 그래프에 쓰는 내부 데이터 주소를 사용한다(비공식).
 * - 주소·형식이 예고 없이 바뀔 수 있으므로 실패해도 앱은 마지막 저장값을 보여준다.
 * - 브라우저처럼 요청하지 않으면 거절(HTTP 418 등)되므로 헤더를 붙인다.
 * - 하루 한 번(홈 새로고침 때)만 조회한다.
 */

data class FearGreed(
    val score: Double,
    val rating: String,          // extreme fear / fear / neutral / greed / extreme greed
    val previousClose: Double?,
    val previousWeek: Double?,
    val previousMonth: Double?,
    val timestamp: String,       // CNN 기준 시각
    val fetchedAt: String        // 앱이 받은 시각
) {
    /** 한국어 단계 (CNN 구간: 0-24 / 25-44 / 45-55 / 56-75 / 76-100) */
    val label: String
        get() = when {
            score < 25 -> "극단적 공포"
            score < 45 -> "공포"
            score <= 55 -> "중립"
            score <= 75 -> "탐욕"
            else -> "극단적 탐욕"
        }
}

object FearGreedApi {

    private const val URL_GRAPH = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata"

    data class Result(val data: FearGreed?, val message: String)

    fun fetch(fetchedAt: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(URL_GRAPH).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Referer", "https://www.cnn.com/")
            connection.setRequestProperty("Origin", "https://www.cnn.com")

            val code = connection.responseCode
            if (code !in 200..299) return Result(null, "공포탐욕지수 실패: HTTP $code")

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)

            val fg = json.optJSONObject("fear_and_greed")
            if (fg != null && fg.has("score")) {
                return Result(
                    FearGreed(
                        score = fg.optDouble("score"),
                        rating = fg.optString("rating"),
                        previousClose = fg.optDouble("previous_close").takeIf { !it.isNaN() },
                        previousWeek = fg.optDouble("previous_1_week").takeIf { !it.isNaN() },
                        previousMonth = fg.optDouble("previous_1_month").takeIf { !it.isNaN() },
                        timestamp = fg.optString("timestamp"),
                        fetchedAt = fetchedAt
                    ),
                    "공포탐욕지수 %.0f".format(fg.optDouble("score"))
                )
            }

            // 형식이 바뀐 경우: 이력의 마지막 값으로 대체
            val data = json.optJSONObject("fear_and_greed_historical")?.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val last = data.getJSONObject(data.length() - 1)
                return Result(
                    FearGreed(
                        score = last.optDouble("y"),
                        rating = last.optString("rating"),
                        previousClose = null, previousWeek = null, previousMonth = null,
                        timestamp = last.optString("x"),
                        fetchedAt = fetchedAt
                    ),
                    "공포탐욕지수 %.0f".format(last.optDouble("y"))
                )
            }
            Result(null, "공포탐욕지수 실패: 응답 형식이 바뀜")
        } catch (e: Exception) {
            Result(null, "공포탐욕지수 실패: ${e.message ?: "알 수 없는 오류"}")
        } finally {
            connection?.disconnect()
        }
    }
}

/** 마지막으로 받은 값 저장 (실패해도 이전 값 표시) */
object FearGreedStore {
    private const val PREFS = "fear_greed"

    fun save(context: Context, v: FearGreed) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("score", v.score.toString())
            .putString("rating", v.rating)
            .putString("prev_close", v.previousClose?.toString() ?: "")
            .putString("prev_week", v.previousWeek?.toString() ?: "")
            .putString("prev_month", v.previousMonth?.toString() ?: "")
            .putString("timestamp", v.timestamp)
            .putString("fetched_at", v.fetchedAt)
            .apply()
    }

    fun load(context: Context): FearGreed? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val score = p.getString("score", null)?.toDoubleOrNull() ?: return null
        return FearGreed(
            score = score,
            rating = p.getString("rating", "") ?: "",
            previousClose = p.getString("prev_close", null)?.toDoubleOrNull(),
            previousWeek = p.getString("prev_week", null)?.toDoubleOrNull(),
            previousMonth = p.getString("prev_month", null)?.toDoubleOrNull(),
            timestamp = p.getString("timestamp", "") ?: "",
            fetchedAt = p.getString("fetched_at", "") ?: ""
        )
    }
}
