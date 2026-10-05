package com.example.stocksgnal.data

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * 미국 국채금리 — 미국 재무부 공식 "Daily Treasury Par Yield Curve Rates" (키·가입 불필요, 매일 종가 기준).
 * 나무증권 API에는 미국 국채금리가 없어서 이것만 예외로 재무부 자료를 쓴다. 전략 계산에는 쓰지 않고 홈 표시용.
 *
 * CSV 예:
 *   Date,"1 Mo",...,"2 Yr",...,"10 Yr","20 Yr","30 Yr"
 *   09/30/2026,4.02,...,4.88,...,5.29,5.68,5.64      ← 최신 날짜가 맨 위
 */
object TreasuryYieldApi {

    /** @param tenor CSV 열 이름 그대로 ("2 Yr", "10 Yr", "20 Yr") */
    data class Yield(val tenor: String, val value: Double, val changePoint: Double?, val date: LocalDate)

    private const val BASE = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv"

    /** 새로고침 한 번에 한 번만 받도록 잠깐 보관 */
    private var cached: Pair<Long, List<Map<String, String>>>? = null

    fun latest(tenor: String): Result<Yield> = runCatching {
        val rows = rows()
        val withValue = rows.filter { it[tenor]?.toDoubleOrNull() != null }
        val last = withValue.firstOrNull() ?: error("$tenor 금리 없음")
        val prev = withValue.getOrNull(1)
        val value = last.getValue(tenor).toDouble()
        Yield(
            tenor = tenor,
            value = value,
            changePoint = prev?.get(tenor)?.toDoubleOrNull()?.let { value - it },
            date = parseDate(last.getValue("Date"))
        )
    }

    @Synchronized
    private fun rows(): List<Map<String, String>> {
        cached?.let { (at, data) -> if (System.currentTimeMillis() - at < 60_000L && data.isNotEmpty()) return data }
        val year = LocalDate.now(ZoneId.of("America/New_York")).year
        var data = fetchYear(year)
        // 1월 초처럼 올해 자료가 1~2일뿐이면 작년 자료를 이어 붙인다(전일 대비 계산용)
        if (data.size < 2) data = data + runCatching { fetchYear(year - 1) }.getOrDefault(emptyList())
        cached = System.currentTimeMillis() to data
        return data
    }

    private fun fetchYear(year: Int): List<Map<String, String>> {
        val url = "$BASE/$year/all?type=daily_treasury_yield_curve&field_tdr_date_value=$year&page&_format=csv"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        try {
            if (connection.responseCode !in 200..299) error("재무부 금리 HTTP ${connection.responseCode}")
            val lines = connection.inputStream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList()
            val header = lines.first().split(",").map { it.trim().trim('"') }
            return lines.drop(1).map { line ->
                val cells = line.split(",").map { it.trim().trim('"') }
                header.indices.associate { i -> header[i] to cells.getOrElse(i) { "" } }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** "09/30/2026" → 2026-09-30 */
    private fun parseDate(text: String): LocalDate {
        val p = text.split("/")
        return LocalDate.of(p[2].toInt(), p[0].toInt(), p[1].toInt())
    }
}
