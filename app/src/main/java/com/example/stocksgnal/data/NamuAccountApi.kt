package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/** 나무증권 API로 볼 수 있는 계좌 (계좌목록조회 결과) */
data class NamuAccountInfo(val acctNo: String, val type: String) {
    val typeName: String get() = when (type) {
        "01" -> "자계좌"
        "02" -> "주문대리인계좌"
        "03" -> "모의투자"
        else -> "계좌"
    }
}

/** 앱에 가져온 나무증권 계좌 */
data class LinkedNamuAccount(val acctNo: String, val label: String)

data class NamuHolding(
    val ticker: String,
    val name: String,
    val quantity: Double,
    val avgPriceUsd: Double,     // 외화매입단가
    val closeUsd: Double,        // 외화증권종가
    val evalUsd: Double,         // 외화평가금액
    val evalKrw: Double,         // 원화평가금액
    val pnlKrw: Double,          // 원화평가손익
    val returnPct: Double,       // 평가수익율
    val dayChangePct: Double? = null,  // 오늘(최근 거래일) 등락률 — 현재가 조회
    val industry: String = ""          // 업종명 — 현재가 조회
)

/** 자산현황 API가 반환한 외화 RP 보유분. 평가금액은 API의 원화 금액이다. */
data class ForeignRpHolding(
    val name: String,
    val currency: String,
    val quantity: Double,
    val evalKrw: Double
)

internal fun isForeignRpAsset(category: String, name: String, balanceType: String, currency: String): Boolean {
    val label = java.text.Normalizer.normalize("$category $name $balanceType", java.text.Normalizer.Form.NFKC)
        .uppercase(java.util.Locale.ROOT)
    val rp = Regex("(^|[^A-Z])RP([^A-Z]|$)").containsMatchIn(label) ||
        Regex("(^|[^A-Z])(USD|EUR|JPY|CNY)RP([^A-Z]|$)").containsMatchIn(label) || "환매조건부" in label
    if (!rp || "원화" in label || "KRW" in label) return false
    // 명시적인 외화 상품명은 원화 평가 통화보다 우선한다.
    if (listOf("외화", "USD", "달러", "EUR", "JPY", "CNY").any { it in label }) return true
    return !currency.trim().equals("KRW", ignoreCase = true)
}

data class NamuBalance(
    val acctNo: String,
    val fetchedAt: String,
    val usdCash: Double,         // 외화예수금(달러)
    val krwCash: Double,         // 원화예수금
    val evalKrw: Double,         // 평가금액합계(원)
    val totalAssetKrw: Double,   // 총자산금액(원)
    val holdings: List<NamuHolding>,
    val principalKrw: Double = 0.0,  // 장부금액(원) = 주식 매입 원금
    val foreignRp: List<ForeignRpHolding>? = null // null: 조회 전/실패, 빈 목록: 응답에 항목 없음
)

/** 날짜별 계좌 자산 기록 (수익 추이 그래프용). 새로고침 때마다 그날 값으로 덮어쓴다 */
data class AssetSnapshot(val date: String, val acctNo: String, val totalKrw: Double, val principalKrw: Double)

/** 마스킹한 계좌번호. 예: 2010****4948 */
fun maskAccountNo(no: String): String =
    if (no.length <= 6) no else no.take(4) + "*".repeat((no.length - 8).coerceAtLeast(2)) + no.takeLast(4)

/**
 * 나무증권 계좌 조회 (조회 전용, 주문 없음).
 * - 계좌목록: POST /n2/acctinfo
 * - 해외주식 잔고: POST /gbstock/inquiry/v1/balance  (TR SCPSO758701)
 * - 외화 RP 현재 보유분: POST /krstock/inquiry/v1/assetStatus 의 Output_1
 *     Input_0 = { act_no, qut_iqr_dit_cd: "9"(전체), fc_sec_trd_nat_cd: "200"(미국), cur_cd: "KRW", xns_dit_cd: "0" }
 *     Output_0 = 계좌 합계(예수금 등), Output_1 = 보유종목(15건씩, 연속조회)
 */
object NamuAccountApi {

    private const val PREFS = "namu_accounts"
    private const val KEY_LINKED = "linked"
    private const val KEY_BALANCES = "balances"
    private const val KEY_HISTORY = "history"

    // ---------- 조회 ----------

    fun listAccounts(context: Context): Result<List<NamuAccountInfo>> =
        NamuHttp.post(context, "/n2/acctinfo", JSONObject()).mapCatching { res ->
            val json = res.json
            if (!NamuHttp.isOk(json)) error(NamuHttp.message(json, "계좌목록 조회 실패"))
            val array = json.optJSONArray("Output_0") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val no = item.optString("acct_no").trim()
                if (no.isBlank()) null else NamuAccountInfo(no, item.optString("acct_type").trim())
            }
        }

    fun balance(context: Context, acctNo: String): Result<NamuBalance> = runCatching {
        val input = JSONObject()
            .put("act_no", acctNo)
            .put("qut_iqr_dit_cd", "9")
            .put("fc_sec_trd_nat_cd", "200")
            .put("cur_cd", "KRW")
            .put("xns_dit_cd", "0")
        val body = JSONObject().put("Input_0", input)

        var summary: JSONObject? = null
        val holdings = mutableListOf<NamuHolding>()
        var cts: String? = null
        var pages = 0
        do {
            val res = NamuHttp.post(context, "/gbstock/inquiry/v1/balance", body, cts).getOrThrow()
            val json = res.json
            if (!NamuHttp.isOk(json) && json.optJSONObject("Output_0") == null) {
                error(NamuHttp.message(json, "잔고 조회 실패"))
            }
            if (summary == null) summary = json.optJSONObject("Output_0")
            val rows = json.optJSONArray("Output_1") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val qty = r.namuNum("cns_bse_bnc_qty") ?: 0.0
                if (qty == 0.0) continue
                holdings.add(
                    NamuHolding(
                        ticker = r.optString("iem_cd").trim().removeSuffix(" US"),
                        name = r.optString("iem_nm").trim(),
                        quantity = qty,
                        avgPriceUsd = r.namuNum("fc_phs_uit_pr") ?: 0.0,
                        closeUsd = r.namuNum("fc_sec_end_pr") ?: 0.0,
                        evalUsd = r.namuNum("fc_eal_amt") ?: 0.0,
                        evalKrw = r.namuNum("krw_eal_amt") ?: 0.0,
                        pnlKrw = r.namuNum("krw_eal_pls_amt") ?: 0.0,
                        returnPct = r.namuNum("eal_pft_rt") ?: 0.0
                    )
                )
            }
            cts = if (res.hasMore) res.cts else null
            pages++
        } while (cts != null && pages < 10)

        val s = summary ?: JSONObject()
        NamuBalance(
            acctNo = acctNo,
            fetchedAt = now(),
            usdCash = s.namuNum("fc_dca") ?: 0.0,
            krwCash = s.namuNum("krw_dca") ?: 0.0,
            evalKrw = s.namuNum("eal_amt_sum") ?: 0.0,
            totalAssetKrw = s.namuNum("tot_aet_amt") ?: 0.0,
            holdings = holdings,
            principalKrw = s.namuNum("abk_amt") ?: holdings.sumOf { it.evalKrw - it.pnlKrw }
        )
    }

    /** 거래내역을 재구성하지 않고 자산현황의 현재 보유분만 읽는다. */
    fun foreignRpDiagnostic(context: Context, acctNo: String): String =
        prefs(context).getString("rp_diagnostic:$acctNo", "아직 조회하지 않았습니다.").orEmpty()

    fun foreignRpBalance(context: Context, acctNo: String): Result<List<ForeignRpHolding>> {
      val diagnostic = mutableListOf("${ZonedDateTime.now(ZoneId.of("Asia/Seoul"))}",
          "자산현황 · 총자산/시가평가/전체장 · 계좌번호 길이 ${acctNo.length}")
      val result = runCatching {
        val body = JSONObject().put("Input_0", JSONObject()
            .put("act_no", acctNo)
            .put("eal_aly_cd", "2")
            .put("aet_bse", "2")
            .put("qut_dit_cd", "UNT")
            .put("aly_qut_cd", "2"))
        readForeignRpPages(diagnostic) { cts -> NamuHttp.post(context, "/krstock/inquiry/v1/assetStatus", body, cts).getOrThrow() }
      }
      result.exceptionOrNull()?.let { diagnostic.add("실패: ${it.message.orEmpty().replace(acctNo, "[계좌]")}") }
      prefs(context).edit().putString("rp_diagnostic:$acctNo", diagnostic.joinToString("\n").take(12000)).apply()
      return result
    }

    internal fun readForeignRpPages(diagnostic: MutableList<String>, fetch: (String?) -> NamuHttp.Response): List<ForeignRpHolding> {
        val holdings = mutableListOf<ForeignRpHolding>()
        var cts: String? = null
        var pages = 0
        val pagePolicy = AssetPagePolicy()
        do {
            val res = fetch(cts)
            val json = res.json
            diagnostic.add("페이지 ${pages + 1}: 응답 ${json.optString("rsp_cd")} · 연속조회 ${res.hasMore}")
            val nextCts = pagePolicy.accept(json.optString("rsp_cd"),
                NamuHttp.message(json, "외화 RP 잔고 조회 실패"), res.hasMore, res.cts)
            val rows = json.optJSONArray("Output_1") ?: error("자산현황 응답에 Output_1 목록이 없습니다.")
            diagnostic.add("자산 항목 ${rows.length()}건")
            val categories = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val category = row.optString("iem_mlf_nm").trim()
                    .ifBlank { row.optString("iem_tp_nm").trim() }
                    .ifBlank { row.optString("pdt_tp_nm").trim() }
                    .ifBlank { row.optString("pdt_tp_nm1").trim() }
                val name = row.optString("iem_nm").trim()
                    .ifBlank { row.optString("pdt_nm").trim() }
                    .ifBlank { row.optString("pdt_nm1").trim() }
                val balanceType = row.optString("bnc_tp_dit_cd_nm").trim()
                    .ifBlank { row.optString("bnc_tp_nm").trim() }
                val currency = row.optString("cur_cd").trim()
                    .ifBlank { row.optString("fc_cur_cd").trim() }
                categories.add("$category/$balanceType/${currency.ifBlank { "통화 없음" }}")
                val matched = isForeignRpAsset(category, name, balanceType, currency)
                if (("$category $name $balanceType").contains("rp", ignoreCase = true) || "환매조건부" in "$category $name") {
                    diagnostic.add("RP 후보: 상품코드 ${row.optString("iem_cd")} · $category · $name · $balanceType · 통화 ${currency.ifBlank { "없음" }} · ${if (matched) "포함" else "제외"} · 평가금액 필드 ${row.namuNum("eal_amt") != null}")
                }
                if (!matched) continue
                holdings.add(ForeignRpHolding(
                    name = name.ifBlank { category.ifBlank { "외화 RP" } },
                    currency = currency,
                    quantity = row.namuNum("itg_bnc_qty") ?: row.namuNum("bnc_qty") ?: 0.0,
                    evalKrw = row.namuNum("eal_amt") ?: error("RP 평가금액(eal_amt)이 없습니다.")
                ))
            }
            diagnostic.add("분류: ${categories.take(30).joinToString(", ")}")
            cts = nextCts
            pages++
        } while (cts != null && pages < 30)
        if (cts != null) error("외화 RP 자산현황의 연속조회가 끝나지 않았습니다.")
        diagnostic.add("외화 RP ${holdings.size}건")
        return holdings
    }

    /** 진단 버튼으로 받은 결과도 잔고 화면의 캐시에 반영한다. */
    @Synchronized
    fun refreshForeignRp(context: Context, acctNo: String): Result<List<ForeignRpHolding>> {
        val result = foreignRpBalance(context, acctNo)
        saveBalances(context, loadBalances(context).map { balance ->
            if (balance.acctNo == acctNo) balance.copy(foreignRp = result.getOrNull()) else balance
        })
        return result
    }

    /** 가져온 계좌 전부의 잔고를 새로 받는다. 실패한 계좌 이름 목록을 돌려준다. */
    @Synchronized
    fun refreshBalances(context: Context): List<String> {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val failures = mutableListOf<String>()
        val saved = loadBalances(context).associateBy { it.acctNo }.toMutableMap()
        val accounts = linkedAccounts(context)
        var fatal = false
        for ((index, acct) in accounts.withIndex()) {
            val result = balance(context, acct.acctNo)
            val fetched = result.getOrNull()
            // RP는 해외주식 잔고 성공 여부와 무관한 별도 자산현황 조회다.
            val rp = foreignRpBalance(context, acct.acctNo)
            if (rp.isFailure) failures.add("${acct.label} 외화 RP: ${rp.exceptionOrNull()?.message}")
            if (fetched != null) {
                saved[acct.acctNo] = fetched.copy(foreignRp = rp.getOrNull())
                continue
            }
            saved[acct.acctNo]?.let { previous -> saved[acct.acctNo] = previous.copy(foreignRp = rp.getOrNull()) }
            failures.add("${acct.label} 잔고")
            if (result.exceptionOrNull() is NamuHttp.Fatal) {
                fatal = true
                // 토큰·네트워크 문제면 나머지 계좌도 실패로 표시하고 멈춘다
                accounts.drop(index + 1).forEach { failures.add("${it.label} 잔고") }
                break
            }
        }
        val balanceAt = android.os.SystemClock.elapsedRealtime()
        // 보유 종목의 오늘 등락률·업종 (해외주식 현재가). 종목마다 한 번씩만 조회
        val tickers = saved.values.flatMap { it.holdings }.map { it.ticker }.distinct()
        val quotes = tickers.associateWith { NamuMarketApi.stockQuote(context, it).getOrNull() }
        val quoteAt = android.os.SystemClock.elapsedRealtime()
        val enriched = saved.values.map { b ->
            b.copy(holdings = b.holdings.map { h ->
                val q = quotes[h.ticker]
                if (q == null) h else h.copy(dayChangePct = q.changePct, industry = q.industry.ifBlank { h.industry })
            })
        }
        saveBalances(context, enriched)
        recordSnapshots(context, enriched)
        // 받은 배당 (해외주식 일별거래내역). 처음 2년, 이후 이어 받기
        if (!fatal) failures.addAll(NamuDividendApi.refresh(context, accounts))
        val dividendAt = android.os.SystemClock.elapsedRealtime()
        // 배당 일정(Massive 무료 키, 있을 때만): 보유 종목 중 필요한 것만, 1분에 4회 이내
        failures.addAll(MassiveDividendApi.refresh(context, enriched.flatMap { b -> b.holdings.map { it.ticker } }))
        val scheduleAt = android.os.SystemClock.elapsedRealtime()
        fun seconds(from: Long, to: Long) = "%.1f초".format((to - from) / 1000.0)
        context.getSharedPreferences("stock_signal_settings", Context.MODE_PRIVATE).edit()
            .putString("last_account_refresh_durations", "잔고 ${seconds(startedAt, balanceAt)} · 종목시세 ${seconds(balanceAt, quoteAt)} · 배당 ${seconds(quoteAt, dividendAt)} · 배당일정 ${seconds(dividendAt, scheduleAt)}")
            .apply()
        return failures.distinct()
    }

    // ---------- 가져온 계좌 저장 ----------

    fun linkedAccounts(context: Context): List<LinkedNamuAccount> {
        val text = prefs(context).getString(KEY_LINKED, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                LinkedNamuAccount(o.getString("no"), o.optString("label"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveLinkedAccounts(context: Context, accounts: List<LinkedNamuAccount>) {
        val array = JSONArray()
        accounts.forEach { array.put(JSONObject().put("no", it.acctNo).put("label", it.label)) }
        prefs(context).edit().putString(KEY_LINKED, array.toString()).apply()
        // 뺀 계좌의 잔고 기록도 지운다
        saveBalances(context, loadBalances(context).filter { b -> accounts.any { it.acctNo == b.acctNo } })
    }

    fun loadBalances(context: Context): List<NamuBalance> {
        val text = prefs(context).getString(KEY_BALANCES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val hs = o.optJSONArray("holdings") ?: JSONArray()
                NamuBalance(
                    acctNo = o.getString("no"),
                    fetchedAt = o.optString("at"),
                    usdCash = o.optDouble("usd", 0.0),
                    krwCash = o.optDouble("krw", 0.0),
                    evalKrw = o.optDouble("eval", 0.0),
                    totalAssetKrw = o.optDouble("total", 0.0),
                    principalKrw = o.optDouble("principal", 0.0),
                    foreignRp = o.optJSONArray("foreignRp")?.let { rpRows ->
                        (0 until rpRows.length()).mapNotNull { j ->
                            val row = rpRows.optJSONObject(j) ?: return@mapNotNull null
                            ForeignRpHolding(row.optString("name"), row.optString("currency"),
                                row.optDouble("quantity"), row.optDouble("evalKrw"))
                        }
                    },
                    holdings = (0 until hs.length()).map { j ->
                        val h = hs.getJSONObject(j)
                        NamuHolding(
                            h.getString("t"), h.optString("n"), h.optDouble("q", 0.0), h.optDouble("avg", 0.0),
                            h.optDouble("close", 0.0), h.optDouble("evu", 0.0), h.optDouble("evk", 0.0),
                            h.optDouble("pnl", 0.0), h.optDouble("pct", 0.0),
                            if (h.has("day")) h.optDouble("day") else null,
                            h.optString("ind")
                        )
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveBalances(context: Context, balances: List<NamuBalance>) {
        val array = JSONArray()
        balances.forEach { b ->
            val hs = JSONArray()
            b.holdings.forEach { h ->
                hs.put(
                    JSONObject().put("t", h.ticker).put("n", h.name).put("q", h.quantity).put("avg", h.avgPriceUsd)
                        .put("close", h.closeUsd).put("evu", h.evalUsd).put("evk", h.evalKrw)
                        .put("pnl", h.pnlKrw).put("pct", h.returnPct)
                        .put("ind", h.industry)
                        .also { o -> h.dayChangePct?.let { o.put("day", it) } }
                )
            }
            val rpRows = b.foreignRp?.let { rp -> JSONArray().also { array ->
                rp.forEach { h -> array.put(JSONObject().put("name", h.name).put("currency", h.currency)
                    .put("quantity", h.quantity).put("evalKrw", h.evalKrw)) }
            } }
            array.put(
                JSONObject().put("no", b.acctNo).put("at", b.fetchedAt).put("usd", b.usdCash).put("krw", b.krwCash)
                    .put("eval", b.evalKrw).put("total", b.totalAssetKrw).put("principal", b.principalKrw)
                    .also { o -> if (rpRows != null) o.put("foreignRp", rpRows) }
                    .put("holdings", hs)
            )
        }
        prefs(context).edit().putString(KEY_BALANCES, array.toString()).apply()
    }

    // ---------- 수익 추이 기록 ----------

    fun loadSnapshots(context: Context): List<AssetSnapshot> {
        val text = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                AssetSnapshot(o.getString("d"), o.getString("a"), o.optDouble("t", 0.0), o.optDouble("p", 0.0))
            }
        }.getOrDefault(emptyList())
    }

    private fun recordSnapshots(context: Context, balances: List<NamuBalance>) {
        val today = java.time.LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
        val map = LinkedHashMap<String, AssetSnapshot>()
        loadSnapshots(context).forEach { map["${it.date}|${it.acctNo}"] = it }
        balances.filter { it.totalAssetKrw > 0 }.forEach { b ->
            // 원금 = 주식 매입 원금 + 주식 외 자산. 총자산은 나무 잔고 응답을 그대로 사용한다.
            map["$today|${b.acctNo}"] = AssetSnapshot(
                today, b.acctNo, b.totalAssetKrw, b.principalKrw + (b.totalAssetKrw - b.evalKrw)
            )
        }
        val kept = map.values.sortedBy { it.date }.takeLast(2000)
        val array = JSONArray()
        kept.forEach { array.put(JSONObject().put("d", it.date).put("a", it.acctNo).put("t", it.totalKrw).put("p", it.principalKrw)) }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now(): String {
        val t = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        return "%02d-%02d %02d:%02d".format(t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }
}
