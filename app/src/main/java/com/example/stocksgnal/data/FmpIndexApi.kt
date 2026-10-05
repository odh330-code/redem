package com.example.stocksgnal.data

import android.content.Context
import com.example.stocksgnal.strategy.DailyCandle
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/** DJUSSC 전용. FMP stable 일봉 API; 나무 키나 계좌 정보를 전송하지 않는다. */
object FmpIndexApi {
    fun isDjussc(symbol: String): Boolean = symbol.trim().uppercase(java.util.Locale.ROOT)
        .removePrefix(".").removePrefix("^") == "DJUSSC"

    internal fun windows(start: LocalDate, end: LocalDate): List<Pair<LocalDate, LocalDate>> {
        require(!start.isAfter(end))
        return buildList {
            var from = start
            while (!from.isAfter(end)) {
                // 공식 API의 요청당 5000행 제한보다 작은 기간으로 나눈다.
                val to = minOf(from.plusYears(10).minusDays(1), end)
                add(from to to)
                from = to.plusDays(1)
            }
        }
    }

    internal fun statusMessage(code: Int): String = when (code) {
        401 -> "FMP API 키 인증 실패"
        402, 403 -> "FMP 요금제 또는 지수 데이터 접근 권한을 확인하세요 (HTTP $code)"
        404 -> "FMP DJUSSC 일봉 경로에서 데이터를 찾지 못했습니다"
        429 -> "FMP 요청 한도 초과 · 잠시 후 다시 조회하세요"
        else -> "FMP 조회 실패 (HTTP $code)"
    }

    fun dailyCandles(context: Context, end: LocalDate, start: LocalDate?, wanted: Int): Result<List<DailyCandle>> = runCatching {
        val key = NamuSecretStore.loadFmpKey(context) ?: error("설정 → 데이터관리에서 FMP API 키를 저장하세요")
        fetch(key, end, start, wanted)
    }

    internal fun fetch(key: String, end: LocalDate, start: LocalDate?, wanted: Int): List<DailyCandle> {
        require(wanted > 0)
        val from = start ?: end.minusYears((wanted.toLong() / 250L + 3).coerceAtMost(40))
        val rows = linkedMapOf<String, DailyCandle>()
        windows(from, end).forEachIndexed { index, (first, last) ->
            if (index > 0) Thread.sleep(300)
            val json = get("historical-price-eod/full?symbol=%5EDJUSSC&from=$first&to=$last", key)
            val parsed = parse(json, first, last)
            parsed.forEach { rows[it.date] = it }
        }
        check(rows.isNotEmpty()) { "FMP에서 DJUSSC 일봉 0건 반환 · 종목 지원 및 조회 기간을 확인하세요" }
        check(start != null || rows.size >= 252) { "FMP DJUSSC 일봉 ${rows.size}건 · 전략 계산에 필요한 252거래일 이력 부족" }
        return rows.values.sortedBy { it.date }.let { if (start == null) it.takeLast(wanted) else it }
    }

    internal fun parse(text: String, from: LocalDate, to: LocalDate): List<DailyCandle> {
        check(text.trimStart().startsWith("[")) { "FMP가 일봉 대신 오류 응답을 반환했습니다. 키·요금제·지수 권한을 확인하세요" }
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { index ->
            val r = array.getJSONObject(index)
            check(isDjussc(r.optString("symbol"))) { "FMP 응답 종목이 DJUSSC와 다릅니다" }
            val date = LocalDate.parse(r.getString("date"))
            if (date < from || date > to) return@mapNotNull null
            fun price(field: String): Double = r.getDouble(field).also {
                check(it.isFinite() && it > 0) { "FMP 일봉 $date 의 $field 값이 유효하지 않습니다" }
            }
            val open = price("open")
            val high = price("high")
            val low = price("low")
            val close = price("close")
            check(high >= maxOf(open, close) && low <= minOf(open, close)) { "FMP 일봉 $date 의 고가·저가 불일치" }
            DailyCandle(date.toString(), open, high, low, close, r.optDouble("volume", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0)
        }.distinctBy { it.date }.sortedBy { it.date }
    }

    private fun get(path: String, key: String): String {
        val connection = (URL("https://financialmodelingprep.com/stable/$path&apikey=${URLEncoder.encode(key, "UTF-8")}").openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            check(code in 200..299) { statusMessage(code) }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            // 예외 URL에 포함될 수 있는 API 키를 화면/로그로 전달하지 않는다.
            throw IllegalStateException("FMP 연결 실패 · 인터넷 연결과 서버 상태를 확인하세요")
        } finally {
            connection.disconnect()
        }
    }
}
