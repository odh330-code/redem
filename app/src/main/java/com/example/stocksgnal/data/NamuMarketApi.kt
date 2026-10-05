package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 나무증권(NH PLUG) 지수·선물·환율 현재값.
 *
 * - CME 지수선물(나스닥100·S&P500 선물): POST /gbfuture/quote/v1/current  (TR d6809)
 *     Input_0 = { exnm: "FCME", iem_cd: "NQZ26" }  → Output_0.last(체결가), rate(전일대비등락율)
 *     종목코드는 근월물(분기물 H·M·U·Z)을 날짜로 계산한다. 예: 2026-10-01 → NQZ26
 * - 해외 지수·환율: POST /gbstock/quote/v1/symbolIndexFxPeriod  (TR IVAGLZMUL01)
 *     Input_0 = { iem_cd: ".DJI", end_dt, array_cnt, gubun: "1"(일) ... } → Output_0.ovrs_prpr(현재가), prdy_ctrt(대비율)
 *     가이드 예제는 다우존스(.DJI)뿐이라, 환율·미국 국채금리 코드는 후보를 차례로 시도하고 성공한 코드를 기억한다.
 * 둘 다 초당 5회 한도.
 */
object NamuMarketApi {

    data class Quote(val price: Double, val changePct: Double?, val code: String)

    private const val FUTURE_URL = "${NamuAuthApi.BASE_URL}/gbfuture/quote/v1/current"
    private const val INDEX_URL = "${NamuAuthApi.BASE_URL}/gbstock/quote/v1/symbolIndexFxPeriod"
    private const val CURRENT_URL = "${NamuAuthApi.BASE_URL}/gbstock/quote/v1/current"
    private const val PREFS = "namu_market_codes"
    private val compact = DateTimeFormatter.BASIC_ISO_DATE

    data class StockQuote(val price: Double, val changePct: Double?, val industry: String, val name: String)

    /** 해외주식 현재가 (TR g6101): 현재가·등락률·업종명. 계좌 화면의 일간 손익·분야별 비중에 쓴다 */
    fun stockQuote(context: Context, ticker: String): Result<StockQuote> {
        val body = JSONObject().put("Input_0", JSONObject().put("iem_cd", ticker))
        return post(context, CURRENT_URL, body).mapCatching { json ->
            val out = json.optJSONObject("Output_0") ?: error(json.rspMessage("$ticker 현재가 없음"))
            val price = out.num("trdprc")?.takeIf { it > 0 } ?: error(json.rspMessage("$ticker 현재가 없음"))
            var pct = out.num("pctchng")
            if (pct != null && pct > 0 && out.optString("netchng_cls") == "5") pct = -pct
            StockQuote(price, pct, out.optString("industry_name").trim(), out.optString("iem_nm").trim())
        }
    }

    /**
     * 원/달러 환율. 해외주식 현재가(POST /gbstock/quote/v1/current, TR g6101) 응답의 currency_prc(환율)를 쓴다.
     * 가이드 예: AAPL 313.32달러 → cov_pric(환산가) 479,379원, currency_prc 1,530 → 원/달러 환율.
     * 미국 상장 종목이면 아무거나 같은 환율이 오므로 QQQ로 조회한다.
     */
    fun usdKrw(context: Context, ticker: String = "QQQ"): Result<Double> {
        val body = JSONObject().put("Input_0", JSONObject().put("iem_cd", ticker))
        return post(context, CURRENT_URL, body).mapCatching { json ->
            val out = json.optJSONObject("Output_0") ?: error(json.rspMessage("환율 없음"))
            out.num("currency_prc")?.takeIf { it in 500.0..5000.0 }
                ?: run {
                    // 환율 칸이 비어 있으면 환산가 ÷ 현재가로 계산
                    val krw = out.num("cov_pric")
                    val usd = out.num("trdprc")
                    if (krw != null && usd != null && usd > 0) (krw / usd).takeIf { it in 500.0..5000.0 } else null
                }
                ?: error("환율 값을 읽지 못했습니다")
        }
    }

    /** CME 지수선물 근월물 현재값. [root] = "NQ", "ES" */
    fun futureQuote(context: Context, root: String): Result<Quote> {
        val code = frontMonthCode(root, LocalDate.now(ZoneId.of("America/New_York")))
        val body = JSONObject().put("Input_0", JSONObject().put("exnm", "FCME").put("iem_cd", code))
        return post(context, FUTURE_URL, body).mapCatching { json ->
            val out = json.optJSONObject("Output_0") ?: error(json.rspMessage("선물 시세 없음"))
            val price = out.num("last")?.takeIf { it > 0 }
                ?: out.num("setp")?.takeIf { it > 0 }
                ?: out.num("pcls")?.takeIf { it > 0 }
                ?: error(json.rspMessage("$code 시세 없음"))
            Quote(price, out.num("rate"), code)
        }
    }

    /**
     * 해외 지수·환율·금리 현재값. [codes] 후보를 앞에서부터 시도하고, 성공한 코드는 [key]로 기억해 다음부터 먼저 쓴다.
     */
    fun indexQuote(context: Context, key: String, codes: List<String>): Result<Quote> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remembered = prefs.getString(key, null)
        val ordered = (listOfNotNull(remembered) + codes).distinct()
        var lastError: Throwable = IllegalStateException("종목코드 후보 없음")
        for (code in ordered) {
            val result = indexQuoteFor(context, code)
            if (result.isSuccess) {
                if (code != remembered) prefs.edit().putString(key, code).apply()
                return result
            }
            lastError = result.exceptionOrNull() ?: lastError
            if (lastError is NamuHttp.Fatal) break   // 토큰·네트워크 문제면 다른 코드도 소용없음
        }
        return Result.failure(lastError)
    }

    private fun indexQuoteFor(context: Context, code: String): Result<Quote> {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val body = JSONObject().put(
            "Input_0",
            JSONObject()
                .put("iem_cd", code)
                .put("end_dt", today.format(compact))
                .put("array_cnt", "2")
                .put("maxavg", "")
                .put("gubun", "1")
                .put("xtick", "")
                .put("today_cls", "0")
                .put("scale_change", "")
        )
        return post(context, INDEX_URL, body).mapCatching { json ->
            val out = json.optJSONObject("Output_0")
            var price = out?.num("ovrs_prpr")?.takeIf { it != 0.0 }
            if (price == null) {
                // Output_0이 비어 있으면 일봉 목록(Output_1)의 최신 값
                val rows = json.optJSONArray("Output_1")
                price = rows?.optJSONObject(0)?.num("ovrs_prpr")?.takeIf { it != 0.0 }
            }
            if (price == null) error(json.rspMessage("$code 시세 없음"))
            var pct = out?.num("prdy_ctrt")
            val sign = out?.optString("prdy_vrss_sign").orEmpty()
            if (pct != null && pct > 0 && (sign == "4" || sign == "5")) pct = -pct
            Quote(price, pct, code)
        }
    }

    /**
     * 해외 지수의 일봉 (종목/지수/환율 기간별 시세, gubun=1).
     * 주식 기간별 시세(/gbstock/quote/v1/period)로는 지수를 받을 수 없어서, 반도체 지수(DJUSSC) 같은
     * 신호 지수는 이 API로 받는다. [codes] 후보를 차례로 시도하고 성공한 코드를 [key]로 기억한다.
     * Output_1: bsop_date, ovrs_oprc/hgpr/lwpr/prpr(문자열), vol — 최신 날짜가 먼저.
     */
    fun indexDailyCandles(
        context: Context,
        key: String,
        codes: List<String>,
        endDate: LocalDate,
        startDate: LocalDate?,
        wanted: Int
    ): Result<List<com.example.stocksgnal.strategy.DailyCandle>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remembered = prefs.getString("candle:$key", null)?.takeIf { it in codes }
        val errors = mutableListOf<String>()
        var lastError: Throwable = IllegalStateException("종목코드 후보 없음")
        for (code in (listOfNotNull(remembered) + codes).distinct()) {
            val result = indexDailyFor(context, code, endDate, startDate, wanted)
            if (result.isSuccess && result.getOrNull().orEmpty().isNotEmpty()) {
                if (code != remembered) prefs.edit().putString("candle:$key", code).apply()
                return result
            }
            lastError = result.exceptionOrNull() ?: IllegalStateException("$code 일봉 없음")
            errors.add("$code: ${lastError.message}")
            if (lastError is NamuHttp.Fatal) break
        }
        prefs.edit().putString("diagnostic:$key", errors.joinToString("\n")).apply()
        return Result.failure(if (lastError is NamuHttp.Fatal) lastError else IllegalStateException(errors.joinToString(" / ")))
    }

    private fun indexDailyFor(
        context: Context,
        code: String,
        endDate: LocalDate,
        startDate: LocalDate?,
        wanted: Int
    ): Result<List<com.example.stocksgnal.strategy.DailyCandle>> = runCatching {
        val rows = LinkedHashMap<String, com.example.stocksgnal.strategy.DailyCandle>()
        var end = endDate
        var pages = 0
        while (pages < 15) {
            val count = if (startDate != null) {
                (java.time.temporal.ChronoUnit.DAYS.between(startDate, end) + 3).toInt().coerceIn(5, 500)
            } else {
                (wanted - rows.size).coerceIn(1, 500)
            }
            val body = JSONObject().put(
                "Input_0",
                JSONObject()
                    .put("iem_cd", code)
                    .put("end_dt", end.format(compact))
                    .put("array_cnt", count.toString())
                    .put("maxavg", "")
                    .put("gubun", "1")
                    .put("xtick", "001")
                    .put("today_cls", "0")
                    .put("scale_change", "")
            )
            val json = post(context, INDEX_URL, body).getOrThrow()
            if (!NamuHttp.isOk(json)) error("응답 ${json.optString("rsp_cd")}: ${json.rspMessage("지수 조회 거부")}")
            val array = json.optJSONArray("Output_1")
            if (array == null || array.length() == 0) {
                if (rows.isEmpty()) error("응답 ${json.optString("rsp_cd")}: 일봉 0건 · ${json.rspMessage("자료 없음")}")
                break
            }
            val before = rows.size
            var oldest: String? = null
            for (i in 0 until array.length()) {
                val r = array.optJSONObject(i) ?: continue
                val date = runCatching { LocalDate.parse(r.optString("bsop_date").trim(), compact) }.getOrNull() ?: continue
                val close = r.num("ovrs_prpr")?.takeIf { it > 0 } ?: continue
                val iso = date.toString()
                if (date.isAfter(endDate)) continue
                rows[iso] = com.example.stocksgnal.strategy.DailyCandle(
                    date = iso,
                    open = r.num("ovrs_oprc")?.takeIf { it > 0 } ?: close,
                    high = r.num("ovrs_hgpr")?.takeIf { it > 0 } ?: close,
                    low = r.num("ovrs_lwpr")?.takeIf { it > 0 } ?: close,
                    close = close,
                    volume = r.num("vol") ?: 0.0
                )
                if (oldest == null || iso < oldest) oldest = iso
            }
            pages++
            if (rows.size == before || oldest == null) break
            val done = if (startDate != null) oldest <= startDate.toString() else rows.size >= wanted
            if (done) break
            end = LocalDate.parse(oldest).minusDays(1)
        }
        rows.values.sortedBy { it.date }
    }

    /**
     * CME 분기 지수선물 근월물 코드. 만기(3월·6월·9월·12월 셋째 금요일) 8일 전에 다음 분기물로 넘긴다.
     * 예: root="NQ", 2026-10-01 → "NQZ26"
     */
    fun frontMonthCode(root: String, today: LocalDate): String {
        val letters = mapOf(3 to 'H', 6 to 'M', 9 to 'U', 12 to 'Z')
        var year = today.year
        var month = ((today.monthValue - 1) / 3 + 1) * 3
        repeat(8) {
            val expiry = LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY))
            if (today.isBefore(expiry.minusDays(8))) {
                return "${root.uppercase()}${letters.getValue(month)}${"%02d".format(year % 100)}"
            }
            month += 3
            if (month > 12) { month = 3; year++ }
        }
        return "${root.uppercase()}Z${"%02d".format(year % 100)}"
    }

    private fun post(context: Context, url: String, body: JSONObject): Result<JSONObject> =
        NamuHttp.post(context, url.removePrefix(NamuAuthApi.BASE_URL), body).map { it.json }

    private fun JSONObject.num(key: String): Double? = namuNum(key)

    private fun JSONObject.rspMessage(fallback: String): String = NamuHttp.message(this, fallback)
}
