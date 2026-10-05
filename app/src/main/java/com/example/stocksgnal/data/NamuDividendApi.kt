package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 받은 배당 한 건 (해외주식 일별거래내역에서 찾은 줄). 금액은 달러 */
data class DividendRow(
    val acctNo: String,
    val date: String,          // yyyy-MM-dd (실거래일자 우선)
    val sno: Int,              // 거래일련번호 (중복 제거용)
    val ticker: String,        // "QLD" 처럼 티커만
    val name: String,
    val memo: String,          // 적요명 · 거래유형명 (원본 확인용)
    val qty: Double?,          // 거래수량 = 배당 기준 보유수량(으로 추정)
    val unitPrice: Double?,    // 거래단가 = 주당배당금(으로 추정)
    val grossUsd: Double,      // 세전
    val taxUsd: Double,        // 원천징수 세금 합계
    val netUsd: Double,        // 세후 (실제 입금)
    val fxRate: Double?        // 적용 환율
) {
    /** 주당 배당금(세전): 거래수량이 있으면 세전÷수량, 없으면 거래단가 */
    val perShareUsd: Double? get() = when {
        qty != null && qty > 0 -> grossUsd / qty
        unitPrice != null && unitPrice > 0 -> unitPrice
        else -> null
    }
    val netKrw: Double? get() = fxRate?.takeIf { it > 0 }?.let { netUsd * it }
}

/** 종목 하나의 배당 추정 */
data class DividendForecast(
    val ticker: String,
    val sharesNow: Double,
    val perShareUsd: Double,       // 가장 최근 주당배당금(세전)
    val paymentsPerYear: Int,      // 최근 배당 간격으로 추정한 연 지급 횟수
    val lastDate: LocalDate,
    val nextDate: LocalDate,       // 다음 지급일 (발표됐으면 확정, 아니면 마지막 지급일 + 간격)
    val taxRate: Double,           // 실제로 떼인 세율(세금÷세전), 기록 없으면 15%
    val declared: Boolean = false, // Massive에 발표된 배당이면 true
    val exDate: String = ""        // 발표된 배당의 배당락일
) {
    val nextGrossUsd get() = perShareUsd * sharesNow
    val nextNetUsd get() = nextGrossUsd * (1 - taxRate)
    val annualGrossUsd get() = nextGrossUsd * paymentsPerYear
    val annualNetUsd get() = annualGrossUsd * (1 - taxRate)
}

/**
 * 받은 배당 (조회 전용).
 * 나무 API에는 해외주식 배당 정보(일정·배당률) API가 없어서, 해외주식 일별거래내역에서 배당 입금 줄만 골라 쓴다.
 *   POST /gbstock/inquiry/v1/dailyTransaction
 *   Input_0 = { act_no, iqr_sta_dt, iqr_end_dt, act_trd_cfc_cd: "00"(전체), iem_mlf_cd: "00001"(외화주식) }
 * 배당 줄 판별: 적요명/거래유형명에 "배당"·"분배"가 있으면 배당. (실제 응답 확인 전이라 원본을 화면에서 볼 수 있게 저장)
 * 처음에는 올해 1월 1일부터, 이후에는 마지막 기록 10일 전부터 올해 범위 안에서 이어 받는다.
 */
object NamuDividendApi {

    private const val PREFS = "namu_dividends"
    private val compact = DateTimeFormatter.BASIC_ISO_DATE

    private fun isDividend(memo: String): Boolean =
        listOf("배당", "분배", "DIV", "Dividend").any { memo.contains(it, ignoreCase = true) } &&
            !memo.contains("배당락")

    fun fetch(context: Context, acctNo: String, from: LocalDate, to: LocalDate): Result<List<DividendRow>> = runCatching {
        val body = JSONObject().put(
            "Input_0", JSONObject()
                .put("act_no", acctNo)
                .put("iqr_sta_dt", from.format(compact))
                .put("iqr_end_dt", to.format(compact))
                .put("act_trd_cfc_cd", "00")
                .put("iem_mlf_cd", "00001")
        )
        val rows = mutableListOf<DividendRow>()
        var cts: String? = null
        var pages = 0
        do {
            val res = NamuHttp.post(context, "/gbstock/inquiry/v1/dailyTransaction", body, cts).getOrThrow()
            val json = res.json
            // 응답 블록은 데이터가 있을 때만 온다 → 없으면 거래 없음
            val array = json.optJSONArray("Output_0") ?: JSONArray()
            if (array.length() == 0 && pages == 0 && !NamuHttp.isOk(json) && !json.has("Output_0")) {
                val msg = NamuHttp.message(json, "")
                if (msg.isNotBlank() && !msg.contains("없") && !msg.contains("완료")) error(msg)
            }
            for (i in 0 until array.length()) {
                val r = array.optJSONObject(i) ?: continue
                val memo = listOf(r.optString("sps_cd_nm"), r.optString("act_trd_tp_nm"))
                    .map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" · ")
                if (!isDividend(memo)) continue
                val dateRaw = r.optString("ral_trd_dt").trim().ifBlank { r.optString("trd_dt").trim() }
                val date = runCatching { LocalDate.parse(dateRaw.take(8), compact).toString() }.getOrNull() ?: continue
                val ticker = (r.optString("oss_iem_cd").trim().ifBlank { r.optString("iem_cd").trim() })
                    .substringBefore(" ").uppercase()
                val tax = r.namuNum("fc_tax_sum")?.takeIf { it > 0 }
                    ?: ((r.namuNum("fc_icm_tax") ?: 0.0) + (r.namuNum("fc_rsd_tax") ?: 0.0))
                val base = r.namuNum("fc_sas_amt")?.takeIf { it > 0 }      // 과세표준 = 세전 배당
                val trdAmt = r.namuNum("fc_trd_amt")?.takeIf { it > 0 } ?: r.namuNum("fc_amt") ?: 0.0
                val gross = base ?: (trdAmt + tax)
                val net = if (base != null) (base - tax).coerceAtLeast(0.0) else trdAmt
                rows.add(
                    DividendRow(
                        acctNo = acctNo, date = date, sno = r.optInt("trd_sno", i),
                        ticker = ticker,
                        name = r.optString("oss_iem_nm").trim().ifBlank { r.optString("iem_krl_nm").trim() },
                        memo = memo,
                        qty = r.namuNum("trd_qty")?.takeIf { it > 0 },
                        unitPrice = r.namuNum("trd_uit_pr")?.takeIf { it > 0 },
                        grossUsd = gross, taxUsd = tax, netUsd = net,
                        fxRate = r.namuNum("aly_xcg_rt")?.takeIf { it > 0 }
                    )
                )
            }
            cts = if (res.hasMore) res.cts else null
            pages++
        } while (cts != null && pages < 60)
        rows
    }

    /** 가져온 계좌의 배당을 이어 받아 저장. 실패한 항목 이름 목록을 돌려준다 */
    fun refresh(context: Context, accounts: List<LinkedNamuAccount>): List<String> {
        val failures = mutableListOf<String>()
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val yearStart = today.withDayOfYear(1)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = load(context).toMutableList()
        val successfulScans = mutableListOf<String>()
        for (acct in accounts) {
            val mine = all.filter { it.acctNo == acct.acctNo }
            val checkpointKey = "last_scan_${acct.acctNo}"
            val lastScan = prefs.getString(checkpointKey, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val from = lastScan?.takeIf { !it.isBefore(yearStart) && !it.isAfter(today) }
                ?.minusDays(10)?.coerceAtLeast(yearStart) ?: yearStart
            val result = fetch(context, acct.acctNo, from, today)
            result.onSuccess { rows ->
                val keys = mine.map { key(it) }.toMutableSet()
                rows.forEach { if (keys.add(key(it))) all.add(it) }
                successfulScans.add(checkpointKey)
            }.onFailure {
                failures.add("${acct.label} 배당(${it.message})")
            }
            if (result.exceptionOrNull() is NamuHttp.Fatal) break
        }
        save(context, all.filter { r -> accounts.any { it.acctNo == r.acctNo } })
        prefs.edit().also { editor -> successfulScans.forEach { editor.putString(it, today.toString()) } }.apply()
        return failures
    }

    private fun key(r: DividendRow) = "${r.acctNo}|${r.date}|${r.sno}|${r.ticker}|${"%.4f".format(r.netUsd)}"

    /**
     * 앞으로 받을 배당 (종목별).
     * 1) Massive에 이미 발표된 다음 배당(지급일이 오늘 이후)이 있으면 그 주당 배당금·지급일을 쓴다 → "확정"
     * 2) 없으면 추정: 주당배당금 = 가장 최근 배당(Massive 우선, 없으면 나무 입금 기록의 세전÷수량)
     *    연 지급 횟수 = Massive frequency, 없으면 나무 기록의 배당 간격 중앙값(≤45일 월, ≤120일 분기, ≤240일 반기, 그 외 연)
     *    다음 지급일 = 마지막 지급일 + 간격
     * 세율 = 그 종목에서 실제로 떼인 세금÷세전(나무 기록), 기록 없으면 15%.
     * 지금 보유 수량 × 주당배당금이라, 배당금이나 수량이 바뀌면 달라진다.
     */
    fun forecast(
        rows: List<DividendRow>,
        sharesByTicker: Map<String, Double>,
        external: Map<String, List<ExtDividend>> = emptyMap(),
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
    ): List<DividendForecast> {
        return sharesByTicker.filter { it.value > 0 }.mapNotNull { (rawTicker, shares) ->
            val ticker = rawTicker.uppercase()
            val hist = rows.filter { it.ticker == ticker }.sortedBy { it.date }
            val ext = external[ticker].orEmpty().filter { it.cashAmount.isFinite() && it.cashAmount > 0 }.sortedByDescending { it.exDate }
            val regular = ext.filter { it.type !in setOf("special", "supplemental", "irregular") && it.frequency > 0 }
            val grossSum = hist.sumOf { it.grossUsd }
            val taxRate = if (grossSum > 0) (hist.sumOf { it.taxUsd } / grossSum).coerceIn(0.0, 0.5) else 0.15

            fun perYearOf(freq: Int) = when (freq) { 1, 2, 3, 4, 12, 24, 52, 104, 365 -> freq; else -> 0 }

            // 1) 발표된 다음 배당
            val declared = ext.filter {
                val pay = runCatching { LocalDate.parse(it.payDate) }.getOrNull()
                pay != null && !pay.isBefore(today)
            }.minByOrNull { it.payDate }
            if (declared != null) {
                val perYear = if (declared in regular) perYearOf(declared.frequency) else 0
                val next = LocalDate.parse(declared.payDate)
                val last = hist.lastOrNull()?.date?.let { LocalDate.parse(it) }
                    ?: ext.firstOrNull { it != declared }?.payDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: next
                return@mapNotNull DividendForecast(
                    ticker, shares, declared.cashAmount, perYear, last, next, taxRate,
                    declared = true, exDate = declared.exDate
                )
            }

            // 2) 추정
            val latestExt = regular.firstOrNull { it.exDate <= today.toString() }
            // 비정기 배당만 있는 종목을 정기 배당으로 연환산하지 않는다.
            if (latestExt == null && ext.isNotEmpty()) return@mapNotNull null
            val perShare = latestExt?.let { it.splitAdjustedCashAmount ?: it.cashAmount }
                ?: hist.reversed().firstNotNullOfOrNull { it.perShareUsd?.takeIf { v -> v.isFinite() && v > 0 } }
                ?: return@mapNotNull null
            val payDates = (hist.map { it.date } + regular.mapNotNull { it.payDate.ifBlank { null } })
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.distinct().sorted()
                .filter { !it.isAfter(today) }
            if (payDates.isEmpty()) return@mapNotNull null
            val gaps = payDates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.filter { it > 0 }.sorted()
            val perYear = perYearOf(latestExt?.frequency ?: 0).takeIf { it > 0 } ?: run {
                val gap = if (gaps.isEmpty()) 91L else gaps[gaps.size / 2]
                when { gap <= 9 -> 52; gap <= 20 -> 24; gap <= 45 -> 12; gap <= 120 -> 4; gap <= 240 -> 2; else -> 1 }
            }
            val last = payDates.last()
            fun advance(date: LocalDate): LocalDate = when (perYear) {
                1, 2, 3, 4, 12 -> date.plusMonths(12L / perYear)
                52 -> date.plusWeeks(1)
                else -> date.plusDays((365L / perYear).coerceAtLeast(1))
            }
            var next = advance(last)
            while (next.isBefore(today)) next = advance(next)
            DividendForecast(ticker, shares, perShare, perYear, last, next, taxRate)
        }.sortedBy { it.nextDate }
    }

    // ---------- 저장 ----------

    fun load(context: Context): List<DividendRow> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rows", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                DividendRow(
                    o.getString("a"), o.getString("d"), o.optInt("s"), o.optString("t"), o.optString("n"), o.optString("m"),
                    if (o.has("q")) o.optDouble("q") else null,
                    if (o.has("u")) o.optDouble("u") else null,
                    o.optDouble("g", 0.0), o.optDouble("x", 0.0), o.optDouble("net", 0.0),
                    if (o.has("fx")) o.optDouble("fx") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, rows: List<DividendRow>) {
        val array = JSONArray()
        rows.sortedBy { it.date }.takeLast(3000).forEach { r ->
            array.put(
                JSONObject().put("a", r.acctNo).put("d", r.date).put("s", r.sno).put("t", r.ticker).put("n", r.name)
                    .put("m", r.memo).put("g", r.grossUsd).put("x", r.taxUsd).put("net", r.netUsd)
                    .also { o ->
                        r.qty?.let { o.put("q", it) }; r.unitPrice?.let { o.put("u", it) }; r.fxRate?.let { o.put("fx", it) }
                    }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rows", array.toString()).apply()
    }
}
