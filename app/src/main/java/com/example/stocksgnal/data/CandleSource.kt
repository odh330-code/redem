package com.example.stocksgnal.data

import android.content.Context
import com.example.stocksgnal.strategy.DailyCandle
import java.time.LocalDate
import java.time.ZoneId

/**
 * DJUSSC 일봉은 FMP, 나머지는 나무증권에서 받는다.
 * 출처를 섞으면 분할·배당 보정 기준이 달라 사상최고가·낙폭 계산이 틀어질 수 있기 때문.
 *
 * 예전에 다른 출처로 저장해 둔 일봉(isNamuBased=false)은 다음 새로고침 때 나무증권에서 전체를 다시 받아
 * 통째로 바꾼다(합치지 않음). 받기에 실패하면 기존 일봉은 그대로 둔다.
 */
object CandleSource {

    data class CandleResult(val success: Boolean, val candles: List<DailyCandle>, val message: String)

    private const val PREFS = "candle_source"
    private const val NAMU_MARK = "namu_v1:"

    private var namuUsable = false
    private var namuDownReason = ""
    private var namuCount = 0
    private var fmpCount = 0
    private val namuFailed = mutableListOf<String>()
    private val splitWarnings = mutableListOf<String>()

    /** 새로고침 시작 때 호출. 나무증권 연결 확인 결과를 넘긴다. */
    @Synchronized
    fun beginRefresh(namuConnected: Boolean, reason: String) {
        namuUsable = namuConnected
        namuDownReason = if (namuConnected) "" else reason
        namuCount = 0
        fmpCount = 0
        namuFailed.clear()
        splitWarnings.clear()
    }

    @Synchronized
    fun namuAvailable(): Boolean = namuUsable

    @Synchronized
    fun summary(): String {
        if (!namuUsable && namuCount == 0 && fmpCount == 0) return "나무증권 연결 안 됨 — 시세를 받지 못했습니다" +
            (if (namuDownReason.isNotBlank()) " ($namuDownReason)" else "")
        // 실패 종목은 홈의 "받지 못함" 줄에 이유와 함께 나오므로 여기서는 빼고, 받은 종목 수만 적는다
        val okCount = namuCount
        var text = when {
            okCount > 0 -> "나무증권에서 ${okCount}종목 새로 받음"
            namuFailed.isEmpty() -> "모든 종목 이미 최신(새로 받을 날짜 없음)"
            else -> "새로 받은 종목 없음"
        }
        if (splitWarnings.isNotEmpty()) text += " · 확인 필요: ${splitWarnings.joinToString(", ")}"
        if (fmpCount > 0) text += " · FMP DJUSSC 수신"
        return text
    }

    fun isNamuBased(context: Context, symbol: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(NAMU_MARK + symbol.uppercase(), false)

    fun isCurrentSource(context: Context, symbol: String): Boolean = if (FmpIndexApi.isDjussc(symbol)) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("source:${symbol.uppercase()}", null) == "fmp_v1"
    } else isNamuBased(context, symbol)

    /** 일봉 저장에 성공한 다음 출처를 기록한다. 출처 변경 때는 전체 교체한다. */
    fun markCurrentSource(context: Context, symbol: String) {
        if (FmpIndexApi.isDjussc(symbol)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("source:${symbol.uppercase()}", "fmp_v1").apply()
        } else markNamuBased(context, symbol)
    }

    private fun markNamuBased(context: Context, symbol: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(NAMU_MARK + symbol.uppercase(), true).apply()
    }

    /**
     * @param startDate 있으면 그 날짜부터(증분), 없으면 [outputSize]개 전체를 받는다.
     */
    fun getDailyCandles(
        context: Context,
        symbol: String,
        outputSize: Int,
        startDate: String? = null,
        endDate: String? = null
    ): CandleResult {
        val end = endDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            ?: LocalDate.now(ZoneId.of("America/New_York"))
        val start = startDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

        if (FmpIndexApi.isDjussc(symbol)) {
            return FmpIndexApi.dailyCandles(context, end, start, outputSize).fold(
                onSuccess = { synchronized(this) { fmpCount++ }; CandleResult(true, it, "FMP") },
                onFailure = { CandleResult(false, emptyList(), it.message ?: "FMP 조회 실패") }
            )
        }
        if (!namuAvailable()) return CandleResult(false, emptyList(), "나무증권 연결 안 됨")

        val namu = if (isIndexSymbol(symbol)) {
            // 지수(예: 반도체 DJUSSC)는 주식 시세 API로 안 나와서 지수 API로 받는다
            NamuMarketApi.indexDailyCandles(context, symbol.uppercase(), indexCodes(symbol), end, start, outputSize).fold(
                onSuccess = { NamuQuoteApi.Fetch(it, "", false) },
                onFailure = { NamuQuoteApi.Fetch(emptyList(), it.message ?: "지수 일봉 실패", it is NamuHttp.Fatal) }
            )
        } else {
            NamuQuoteApi.dailyCandles(context, symbol, end, start, outputSize)
        }
        if (namu.candles.isNotEmpty()) {
            synchronized(this) { namuCount++ }
            if (start == null) {
                // 전체를 새로 받은 경우: 나무증권 기준 표시 + 분할 미보정 의심 구간 점검
                suspiciousJump(namu.candles)?.let { synchronized(this) { splitWarnings.add("$symbol $it") } }
            }
            return CandleResult(true, namu.candles, "나무증권")
        }
        synchronized(this) {
            namuFailed.add(symbol.uppercase())
            if (namu.fatal) {
                namuUsable = false
                namuDownReason = namu.message
            }
        }
        return CandleResult(false, emptyList(), namu.message)
    }

    /** 주식이 아니라 지수인 신호 종목. 나무증권 지수 코드는 앞에 점(.)이 붙는다(예: 다우 .DJI) */
    private val INDEX_SYMBOLS = setOf("DJUSSC", "SOX", "NDX", "SPX", "DJI", "IXIC")

    fun isIndexSymbol(symbol: String): Boolean {
        val s = symbol.uppercase()
        return s.startsWith(".") || s.startsWith("^") || s in INDEX_SYMBOLS
    }

    private fun indexCodes(symbol: String): List<String> {
        val bare = symbol.uppercase().removePrefix(".").removePrefix("^")
        return listOf(".$bare", bare)
    }

    /**
     * 하루 만에 종가가 절반 이하로 떨어지거나 2배 가까이 뛰면 액면분할·병합이 보정되지 않은 것으로 의심한다.
     * (레버리지 ETF라도 하루 -45%·+80%는 사실상 없음) 의심 날짜를 돌려준다.
     */
    private fun suspiciousJump(candles: List<DailyCandle>): String? {
        val sorted = candles.sortedBy { it.date }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1].close
            val cur = sorted[i].close
            if (prev <= 0 || cur <= 0) continue
            val ratio = cur / prev
            if (ratio < 0.55 || ratio > 1.8) return "${sorted[i].date.take(10)} 분할 보정 의심"
        }
        return null
    }
}
