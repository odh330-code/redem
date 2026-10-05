package com.example.stocksgnal.data

import android.content.Context
import com.example.stocksgnal.strategy.DailyCandle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 나무증권(NH PLUG) 해외주식 기간별시세 → 일봉.
 * 가이드: POST /gbstock/quote/v1/period, 초당 5회, 응답 Output_1이 일봉(최신 날짜가 먼저).
 * 거래량(movolume)은 천 주 단위라 1,000을 곱해 다른 출처(Twelve Data)와 단위를 맞춘다.
 */
object NamuQuoteApi {

    /** fatal = 연결 자체가 안 되는 문제(토큰·네트워크). true면 이번 새로고침에서는 나무증권을 더 시도하지 않는다. */
    data class Fetch(val candles: List<DailyCandle>, val message: String, val fatal: Boolean)

    private const val PERIOD_URL = "${NamuAuthApi.BASE_URL}/gbstock/quote/v1/period"
    private const val MIN_GAP_MS = 250L      // 초당 5회 한도 → 4회 이하로
    private const val PAGE_COUNT = 1000
    private const val MAX_PAGES = 15
    private val compact = DateTimeFormatter.BASIC_ISO_DATE

    private var lastCallAt = 0L

    /**
     * @param endDate   이 날짜(포함)까지의 일봉
     * @param startDate 있으면 이 날짜까지 거슬러 올라가 받는다(증분 갱신). 없으면 [wanted]개를 받는다.
     */
    fun dailyCandles(context: Context, symbol: String, endDate: LocalDate, startDate: LocalDate?, wanted: Int): Fetch {
        val rows = LinkedHashMap<String, DailyCandle>()
        var end = endDate
        var pages = 0
        var error: String? = null
        var fatal = false

        while (pages < MAX_PAGES) {
            val count = if (startDate != null) {
                (ChronoUnit.DAYS.between(startDate, end) + 3).toInt().coerceIn(5, PAGE_COUNT)
            } else {
                (wanted - rows.size).coerceIn(1, PAGE_COUNT)
            }
            val page = requestPage(context, symbol, end, count)
            if (page.error != null) {
                error = page.error
                fatal = page.fatal
                break
            }
            if (page.rows.isEmpty()) break
            val before = rows.size
            page.rows.filter { it.date <= endDate.toString() }.forEach { rows[it.date] = it }
            if (rows.size == before) break
            pages++
            val oldest = page.rows.minOf { it.date }
            val done = if (startDate != null) oldest <= startDate.toString() else rows.size >= wanted
            if (done) break
            end = LocalDate.parse(oldest).minusDays(1)
        }

        val sorted = rows.values.sortedBy { it.date }
        return when {
            sorted.isNotEmpty() -> Fetch(sorted, error.orEmpty(), false)
            else -> Fetch(emptyList(), error ?: "나무증권 시세 데이터 없음", fatal)
        }
    }

    private data class Page(val rows: List<DailyCandle>, val error: String?, val fatal: Boolean)

    private fun requestPage(context: Context, symbol: String, end: LocalDate, count: Int): Page {
        // 가이드 표의 값을 먼저, 응답이 실패로 오면 가이드 예제 형식으로 한 번 더 시도
        val first = post(context, symbol, end, count, xtick = "0001", maxavg = "0")
        if (first.error == null || first.fatal) return first
        val second = post(context, symbol, end, count, xtick = "", maxavg = "")
        return if (second.error == null) second else first
    }

    private fun post(context: Context, symbol: String, end: LocalDate, count: Int, xtick: String, maxavg: String): Page {
        val body = JSONObject().put(
            "Input_0",
            JSONObject()
                .put("iem_cd", symbol.uppercase())
                .put("end_dt", end.format(compact))
                .put("count", count.toString())
                .put("maxavg", maxavg)
                .put("maxavgz", maxavg)
                .put("gubun", "3")
                .put("xtick", xtick)
                .put("today_cls", "0")
                .put("market_cls", "1")
        ).toString()

        var token = NamuAuthApi.accessToken(context).getOrElse {
            return Page(emptyList(), it.message ?: "나무증권 토큰 발급 실패", true)
        }

        repeat(3) { attempt ->
            throttle()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(PERIOD_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                when {
                    code == 401 && attempt == 0 -> {
                        NamuAuthApi.discardAccessToken(context)
                        token = NamuAuthApi.accessToken(context).getOrElse {
                            return Page(emptyList(), it.message ?: "나무증권 토큰 재발급 실패", true)
                        }
                    }
                    code == 429 && attempt < 2 -> Thread.sleep(1_200L)
                    code !in 200..299 -> return Page(emptyList(), "나무증권 시세 HTTP $code", code == 401 || code == 429)
                    else -> return parse(text)
                }
            } catch (e: Exception) {
                return Page(emptyList(), "나무증권 연결 실패: ${e.message ?: "네트워크 오류"}", true)
            } finally {
                connection?.disconnect()
            }
        }
        return Page(emptyList(), "나무증권 시세 요청 실패", true)
    }

    private fun parse(text: String): Page {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return Page(emptyList(), "나무증권 시세 응답을 읽을 수 없습니다.", false)
        val array = json.optJSONArray("Output_1")
        val rows = buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val raw = item.optString("trade_date").ifBlank { item.optString("bsop_date") }
                    val date = runCatching { LocalDate.parse(raw, compact).toString() }.getOrNull() ?: continue
                    val open = item.optString("open_prc").toDoubleOrNull() ?: continue
                    val high = item.optString("high").toDoubleOrNull() ?: continue
                    val low = item.optString("low").toDoubleOrNull() ?: continue
                    val close = item.optString("close_prc").toDoubleOrNull() ?: continue
                    if (close <= 0.0) continue
                    val volume = (item.optString("movolume").toDoubleOrNull() ?: 0.0) * 1_000.0
                    add(DailyCandle(date, open, high, low, close, volume))
                }
            }
        }
        val ok = json.optString("rsp_cd") == "00000"
        return if (rows.isEmpty() && !ok) {
            Page(emptyList(), json.optString("rsp_msg").ifBlank { "나무증권 시세 조회 실패" }, false)
        } else {
            Page(rows, null, false)
        }
    }

    @Synchronized
    private fun throttle() {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallAt)
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }
}
