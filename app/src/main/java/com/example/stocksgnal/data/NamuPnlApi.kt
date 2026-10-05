package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 주문일 하루의 실현손익 합계 (기간손익 Output_1 한 줄) */
data class PnlDay(
    val acctNo: String,
    val date: LocalDate,
    val currency: String,      // "KRW"(원화기준) 또는 "USD"(거래통화기준)
    val buyAmount: Double,
    val sellAmount: Double,
    val tradePnl: Double,      // 매매손익(제비용 전)
    val costs: Double,         // 제비용(수수료·세금)
    val realizedPnl: Double,   // 실현손익(제비용 후)
    val realizedPct: Double?
)

/** 주문일 하루 · 종목 하나의 실현손익 (기간손익 상세 Output_0 한 줄) */
data class PnlItem(
    val acctNo: String,
    val date: LocalDate,
    val currency: String,
    val ticker: String,
    val name: String,
    val buyQty: Double,
    val buyUnitPrice: Double,
    val sellQty: Double,
    val sellUnitPrice: Double,
    val sellAmount: Double,
    val tradePnl: Double,
    val costs: Double,
    val realizedPnl: Double,
    val realizedPct: Double?
)

/**
 * 해외주식 실현손익 (조회 전용, 2026-10-05 추가 · 실계좌 미검증).
 * 증권사가 계산한 매도 실현손익을 그대로 쓴다(앱에서 원가를 다시 계산하지 않음).
 *   기간손익      POST /gbstock/inquiry/v1/periodPnl
 *     Input_0 = { act_no, iqr_dit(1 거래통화 / 2 원화), sta_orr_dt, end_orr_dt, iem_cd, trd_cur_cd, fc_sec_trd_nat_cd }
 *     Output_0 = 기간 합계, Output_1[] = 주문일별 (orr_dt, fc_sll_amt, fc_sby_pls, fc_sdr_xps, fc_rzt_pls, fc_rzt_pft_rt …)
 *   기간손익 상세 POST /gbstock/inquiry/v1/periodPnlDetail
 *     Input_0 = { act_no, iqr_dit, iem_cd, orr_dt, fc_sec_trd_nat_cd, trd_cur_cd }
 *     Output_0[] = 종목별 (iem_cd, iem_nm, byn/sll 수량·단가, fc_sll_amt, fc_sby_pls, fc_sdr_xps, fc_rzt_pls, fc_rzt_pft_rt)
 * 출처: PLUG-OpenAPI/nhplug-mcp specs/gbstock.openapi.json
 * 확인 필요: 원화기준(iqr_dit=2)에서도 필드 이름이 fc_* 로 오는지, 원화 환산 환율 기준, 조회 가능한 과거 기간.
 */
object NamuPnlApi {

    const val PERIOD_PATH = "/gbstock/inquiry/v1/periodPnl"
    const val DETAIL_PATH = "/gbstock/inquiry/v1/periodPnlDetail"
    private val compact = DateTimeFormatter.BASIC_ISO_DATE

    fun fetchPeriod(
        context: Context,
        acctNo: String,
        from: LocalDate,
        to: LocalDate,
        krwBasis: Boolean = true,
        ticker: String = ""
    ): Result<List<PnlDay>> = runCatching {
        val body = JSONObject().put(
            "Input_0", JSONObject()
                .put("act_no", acctNo)
                .put("iqr_dit", if (krwBasis) "2" else "1")
                .put("sta_orr_dt", from.format(compact))
                .put("end_orr_dt", to.format(compact))
                .put("iem_cd", ticker)
                .put("trd_cur_cd", "USD")
                .put("fc_sec_trd_nat_cd", "200")
        )
        val currency = if (krwBasis) "KRW" else "USD"
        val days = mutableListOf<PnlDay>()
        var cts: String? = null
        var pages = 0
        do {
            val res = NamuHttp.post(context, PERIOD_PATH, body, cts).getOrThrow()
            val json = res.json
            if (!NamuHttp.isOk(json) && !json.has("Output_1") && json.optString("rsp_cd") !in setOf("00165", "00218")) {
                val msg = NamuHttp.message(json, "")
                if (msg.isNotBlank() && !msg.contains("없")) error(msg)
            }
            val array = json.optJSONArray("Output_1") ?: JSONArray()
            for (i in 0 until array.length()) {
                val r = array.optJSONObject(i) ?: continue
                val date = runCatching { LocalDate.parse(r.optString("orr_dt").trim().take(8), compact) }.getOrNull() ?: continue
                days.add(
                    PnlDay(
                        acctNo = acctNo, date = date, currency = currency,
                        buyAmount = r.namuNum("fc_byn_amt1") ?: 0.0,
                        sellAmount = r.namuNum("fc_sll_amt") ?: 0.0,
                        tradePnl = r.namuNum("fc_sby_pls") ?: 0.0,
                        costs = r.namuNum("fc_sdr_xps") ?: 0.0,
                        realizedPnl = r.namuNum("fc_rzt_pls") ?: 0.0,
                        realizedPct = r.namuNum("fc_rzt_pft_rt")
                    )
                )
            }
            cts = if (res.hasMore) res.cts else null
            pages++
        } while (cts != null && pages < 60)
        days
    }

    fun fetchDetail(
        context: Context,
        acctNo: String,
        date: LocalDate,
        krwBasis: Boolean = true
    ): Result<List<PnlItem>> = runCatching {
        val body = JSONObject().put(
            "Input_0", JSONObject()
                .put("act_no", acctNo)
                .put("iqr_dit", if (krwBasis) "2" else "1")
                .put("iem_cd", "")
                .put("orr_dt", date.format(compact))
                .put("fc_sec_trd_nat_cd", "200")
                .put("trd_cur_cd", "USD")
        )
        val currency = if (krwBasis) "KRW" else "USD"
        val res = NamuHttp.post(context, DETAIL_PATH, body).getOrThrow()
        val array = res.json.optJSONArray("Output_0") ?: JSONArray()
        (0 until array.length()).mapNotNull { i ->
            val r = array.optJSONObject(i) ?: return@mapNotNull null
            PnlItem(
                acctNo = acctNo, date = date, currency = currency,
                ticker = r.optString("iem_cd").trim().substringBefore(" ").uppercase(),
                name = r.optString("iem_nm").trim(),
                buyQty = r.namuNum("byn_qty") ?: 0.0,
                buyUnitPrice = r.namuNum("byn_uit_pr") ?: 0.0,
                sellQty = r.namuNum("sll_qty") ?: 0.0,
                sellUnitPrice = r.namuNum("sll_uit_pr") ?: 0.0,
                sellAmount = r.namuNum("fc_sll_amt") ?: 0.0,
                tradePnl = r.namuNum("fc_sby_pls") ?: 0.0,
                costs = r.namuNum("fc_sdr_xps") ?: 0.0,
                realizedPnl = r.namuNum("fc_rzt_pls") ?: 0.0,
                realizedPct = r.namuNum("fc_rzt_pft_rt")
            )
        }
    }
}
