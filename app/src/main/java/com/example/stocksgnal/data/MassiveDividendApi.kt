package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/** Massive(옛 Polygon.io) 배당 한 건. 금액은 주당, 통화는 보통 USD */
data class ExtDividend(
    val ticker: String,
    val exDate: String,          // 배당락일 yyyy-MM-dd
    val payDate: String,         // 지급일
    val declarationDate: String, // 발표일
    val cashAmount: Double,      // 주당 배당금
    val frequency: Int,          // 연 지급 횟수 (0이면 비정기)
    val type: String,            // recurring / special / supplemental / irregular / unknown
    val splitAdjustedCashAmount: Double? = null
)

/**
 * 배당 일정·주당 배당금: Massive 무료 키 (나무 API에 해외주식 배당 정보가 없어서 이것만 외부에서 받는다).
 *   GET https://api.massive.com/stocks/v1/dividends?ticker=QLD&limit=20&sort=ex_dividend_date.desc
 *   Authorization: Bearer KEY (키를 URL에 포함하지 않는다)
 * 무료 플랜 분당 5회 → 이 앱은 1분에 최대 4회만 부르고, 종목별로 저장해 두고 필요한 종목만 다시 받는다.
 *   - 이미 발표된 다음 배당이 있으면 7일마다, 없으면 하루 한 번
 *   - 한 번 새로고침에 최대 4종목, 남은 종목은 다음 새로고침 때(오래된 것부터)
 * 일봉·전략 계산에는 쓰지 않는다(표시용).
 */
object MassiveDividendApi {

    private const val BASE = "https://api.massive.com/stocks/v1/dividends"
    private const val PREFS = "massive_dividends"
    private const val MAX_PER_MINUTE = 4

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = LocalDate.now(ZoneId.of("Asia/Seoul"))

    /** 저장된 종목별 배당 (최근 것 먼저) */
    fun load(context: Context): Map<String, List<ExtDividend>> {
        val text = prefs(context).getString("data", null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(text)
            root.keys().asSequence().associateWith { t ->
                val arr = root.getJSONObject(t).optJSONArray("rows") ?: JSONArray()
                (0 until arr.length()).map { i -> fromJson(t, arr.getJSONObject(i)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun fetchedAt(context: Context): Map<String, String> {
        val text = prefs(context).getString("data", null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(text)
            root.keys().asSequence().associateWith { root.getJSONObject(it).optString("at") }
        }.getOrDefault(emptyMap())
    }

    /** 조회 성공한 빈 결과와 아직 조회하지 못한 종목을 구분한다. */
    fun pendingTickers(context: Context, tickers: Collection<String>): List<String> {
        return pendingTickers(tickers, fetchedAt(context))
    }

    internal fun pendingTickers(tickers: Collection<String>, at: Map<String, String>): List<String> {
        return tickers.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
            .filter { at[it].isNullOrBlank() }
    }

    fun hasKey(context: Context): Boolean = !NamuSecretStore.loadMassiveKey(context).isNullOrBlank()

    /**
     * 필요한 종목만 받는다. 키가 없으면 아무것도 하지 않는다(실패로 보지 않음).
     * 반환: 실패 메시지 목록(홈 "받지 못함"에 표시)
     */
    @Synchronized
    fun refresh(context: Context, tickers: Collection<String>): List<String> {
        val key = NamuSecretStore.loadMassiveKey(context) ?: return emptyList()
        val wanted = tickers.map { it.uppercase() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyList()

        val data = load(context).toMutableMap()
        val at = fetchedAt(context).toMutableMap()
        val todayText = today().toString()

        // 다시 받을 필요가 있는 종목 (오래된 것부터)
        val due = wanted.filter { t ->
            val last = at[t]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@filter true
            val hasUpcoming = data[t].orEmpty().any { it.payDate >= todayText || it.exDate >= todayText }
            val ageDays = java.time.temporal.ChronoUnit.DAYS.between(last, today())
            if (hasUpcoming) ageDays >= 7 else ageDays >= 1
        }.sortedBy { at[it] ?: "" }

        val failures = mutableListOf<String>()
        for (t in due) {
            if (callsInLastMinute(context) >= MAX_PER_MINUTE) break   // 나머지는 다음 새로고침 때
            val result = fetch(context, key, t)
            result.onSuccess { rows ->
                data[t] = rows
                at[t] = todayText
            }.onFailure { failures.add("배당일정 $t(${it.message?.replace(key, "[숨김]")})") }
            // 한도 초과·키 문제면 다른 종목도 소용없으니 멈춘다
            val err = result.exceptionOrNull()
            if (err is RateLimited || err is BadKey) break
        }
        save(context, data, at)
        val pending = pendingTickers(wanted, at)
        if (pending.isNotEmpty()) failures.add("배당일정 미조회 ${pending.joinToString(", ")} — 1분 뒤 추가 조회")
        return failures
    }

    private class RateLimited : Exception("호출 한도 초과 — 1분 뒤 다시")
    private class BadKey(msg: String) : Exception(msg)

    private fun fetch(context: Context, key: String, ticker: String): Result<List<ExtDividend>> {
        val t = URLEncoder.encode(ticker, "UTF-8")
        val withSort = "$BASE?ticker=$t&limit=20&sort=ex_dividend_date.desc"
        val plain = "$BASE?ticker=$t&limit=50"
        val first = get(context, withSort, key)
        // 정렬 문법을 안 받아주면(400) 정렬 없이 한 번 더 — 앱에서 직접 정렬한다
        val res = if (first.exceptionOrNull()?.message == "HTTP 400" && callsInLastMinute(context) < MAX_PER_MINUTE) get(context, plain, key) else first
        return res.mapCatching { json ->
            val status = json.optString("status")
            if (status.isNotBlank() && status != "OK" && status != "DELAYED") error(json.optString("error").ifBlank { status })
            val arr = json.optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).map { fromJson(ticker, arr.getJSONObject(it)) }
                .filter { it.cashAmount > 0 && it.exDate.isNotBlank() }
                .sortedByDescending { it.exDate }
                .take(20)
        }
    }

    private fun get(context: Context, url: String, key: String): Result<JSONObject> = runCatching {
        recordCall(context)
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 429 -> throw RateLimited()
                code == 401 || code == 403 -> throw BadKey("키 확인 필요 HTTP $code")
                code !in 200..299 -> error("HTTP $code")
            }
            JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    // ---------- 분당 호출 수 기록 (새로고침을 연달아 눌러도 한도를 넘지 않게) ----------

    private fun callTimes(context: Context): List<Long> =
        prefs(context).getString("calls", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }

    private fun callsInLastMinute(context: Context): Int {
        val now = System.currentTimeMillis()
        return callTimes(context).count { now - it < 60_000 }
    }

    @Synchronized
    private fun recordCall(context: Context) {
        val now = System.currentTimeMillis()
        val kept = callTimes(context).filter { now - it < 60_000 } + now
        prefs(context).edit().putString("calls", kept.joinToString(",")).commit()
    }

    // ---------- 저장 ----------

    private fun fromJson(ticker: String, o: JSONObject) = ExtDividend(
        ticker = o.optString("ticker").ifBlank { ticker }.uppercase(),
        exDate = o.optString("ex_dividend_date"),
        payDate = o.optString("pay_date"),
        declarationDate = o.optString("declaration_date"),
        cashAmount = o.optDouble("cash_amount", 0.0).takeIf { !it.isNaN() } ?: 0.0,
        frequency = o.optInt("frequency", 0),
        type = o.optString("distribution_type").ifBlank { o.optString("dividend_type") },
        splitAdjustedCashAmount = o.optDouble("split_adjusted_cash_amount", Double.NaN).takeIf { it.isFinite() && it > 0 }
    )

    private fun save(context: Context, data: Map<String, List<ExtDividend>>, at: Map<String, String>) {
        val root = JSONObject()
        data.forEach { (t, rows) ->
            val arr = JSONArray()
            rows.forEach {
                arr.put(
                    JSONObject().put("ticker", it.ticker).put("ex_dividend_date", it.exDate).put("pay_date", it.payDate)
                        .put("declaration_date", it.declarationDate).put("cash_amount", it.cashAmount)
                        .put("frequency", it.frequency).put("distribution_type", it.type)
                        .also { row -> it.splitAdjustedCashAmount?.let { amount -> row.put("split_adjusted_cash_amount", amount) } }
                )
            }
            root.put(t, JSONObject().put("at", at[t].orEmpty()).put("rows", arr))
        }
        prefs(context).edit().putString("data", root.toString()).apply()
    }
}
