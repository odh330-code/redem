package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 사용자의 주문 확인과 기기 인증 후에만 UI에서 호출한다. 조회용 재시도 로직을 사용하지 않는다. */
object NamuOrderApi {
    private fun decodeReceipt(text: String): ManualBuyReceipt {
        val o = JSONObject(text)
        val d = ManualBuyDraft(o.getString("account"), o.getString("ticker"), o.getLong("qty"),
            BigDecimal(o.getString("price")), BuySession.valueOf(o.getString("session")), o.getString("currency"))
        return ManualBuyReceipt(d, o.getString("date"), ManualBuyState.valueOf(o.getString("state")), o.optLong("order"), o.optString("message"))
    }

    fun loadReceipt(context: Context): ManualBuyReceipt? = NamuSecretStore.loadManualBuy(context)?.let(::decodeReceipt)

    fun loadHistory(context: Context): List<ManualBuyReceipt> {
        val history = JSONArray(NamuSecretStore.loadManualBuyHistory(context) ?: "[]")
        return (0 until history.length()).map { decodeReceipt(history.getJSONObject(it).toString()) }.reversed()
    }

    /** 사용자가 새 주문 작성을 선택할 때만 접수 건을 보관한다. 기존 주문 취소·재전송은 하지 않는다. */
    @Synchronized
    fun startNextBuy(context: Context, expected: ManualBuyReceipt) {
        val current = loadReceipt(context)
        check(current?.canStartNextBuy == true && current.sameStoredOrder(expected)) { "접수 여부가 확인된 현재 주문만 보관할 수 있습니다." }
        NamuSecretStore.archiveManualBuy(context)
    }

    private fun save(context: Context, r: ManualBuyReceipt) {
        val d = r.draft
        NamuSecretStore.saveManualBuy(context, JSONObject().put("account", d.account).put("ticker", d.ticker)
            .put("qty", d.quantity).put("price", d.price.toPlainString()).put("session", d.session.name)
            .put("currency", d.currencyCode).put("date", r.date).put("state", r.state.name)
            .put("order", r.orderNo).put("message", r.message).toString())
    }

    @Synchronized
    fun clearRejected(context: Context) {
        check(loadReceipt(context)?.state == ManualBuyState.REJECTED)
        NamuSecretStore.clearRejectedManualBuy(context)
    }

    /** UI에서 사용자가 나무 앱의 미접수/미체결·취소 상태를 확인했다고 명시한 경우만 호출. */
    @Synchronized
    fun clearAfterManualCheck(context: Context) {
        check(loadReceipt(context)?.state in setOf(ManualBuyState.UNKNOWN, ManualBuyState.PENDING))
        NamuSecretStore.clearRejectedManualBuy(context)
    }

    @Synchronized
    fun submitBuy(context: Context, draft: ManualBuyDraft): ManualBuyReceipt {
        draft.validate()
        check(loadReceipt(context) == null) { "이전 주문 결과를 먼저 확인하세요. 자동 재주문하지 않습니다." }
        val payload = draft.requestJson().toByteArray(Charsets.UTF_8)
        val token = NamuAuthApi.accessToken(context).getOrThrow()
        val date = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)
        val pending = ManualBuyReceipt(draft, date, ManualBuyState.PENDING, message = "주문 응답 확인 중")
        // 통신 전에 저장해 앱이 종료돼도 중복 매수를 막는다.
        save(context, pending)
        var connection: HttpURLConnection? = null
        val result = try {
            connection = (URL("${NamuAuthApi.BASE_URL}/gbstock/order/v1/buy").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                instanceFollowRedirects = false
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(payload) }
            val http = connection.responseCode
            val text = (if (http in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val code = json?.optString("rsp_cd").orEmpty()
            val order = json?.optJSONObject("Output_0")?.optLong("orr_no") ?: 0
            val msg = json?.optString("rsp_msg").orEmpty()
            when (classifyBuyResponse(http, code, order)) {
                ManualBuyState.ACCEPTED ->
                    pending.copy(state = ManualBuyState.ACCEPTED, orderNo = order, message = msg.ifBlank { "주문 접수" })
                ManualBuyState.REJECTED ->
                    pending.copy(state = ManualBuyState.REJECTED, message = msg.ifBlank { "주문 거부 ($code)" })
                else -> pending.copy(state = ManualBuyState.UNKNOWN, orderNo = order,
                    message = "접수 여부 확인 필요 (HTTP $http / $code). $msg 나무 앱 또는 주문 조회로 확인하세요.")
            }
        } catch (_: Exception) {
            pending.copy(state = ManualBuyState.UNKNOWN, message = "통신 중단으로 접수 여부를 알 수 없습니다. 다시 매수하지 말고 주문 내역을 확인하세요.")
        } finally {
            connection?.disconnect()
        }
        save(context, result)
        return result
    }

    /** 조회는 공통 요청 사용 가능. 입력한 일자에서 해당 주문번호의 체결수량을 확인한다. */
    fun executions(context: Context, receipt: ManualBuyReceipt, queryDate: String): Result<List<ManualBuyExecution>> = runCatching {
        LocalDate.parse(queryDate, DateTimeFormatter.BASIC_ISO_DATE)
        val input = JSONObject().put("act_no", receipt.draft.account).put("orr_dt", queryDate)
            .put("iem_cd", receipt.draft.ticker).put("oss_sby_dit_cd", "2").put("sot_dit", "1").put("ost_cns_dit", "0")
        if (receipt.orderNo > 0) input.put("orr_no", receipt.orderNo)
        val rows = mutableListOf<ManualBuyExecution>()
        var cts: String? = null
        var pages = 0
        do {
            val res = NamuHttp.post(context, "/gbstock/inquiry/v1/unexecuted", JSONObject().put("Input_0", input), cts).getOrThrow()
            check(NamuHttp.isOk(res.json)) { NamuHttp.message(res.json, "주문 조회 실패") }
            val array = res.json.optJSONArray("Output_0")
            for (i in 0 until (array?.length() ?: 0)) {
                val r = array!!.getJSONObject(i)
                val no = r.optLong("orr_no")
                if (receipt.orderNo > 0 && no != receipt.orderNo) continue
                if (r.optString("iem_cd").substringBefore(" ").trim() != receipt.draft.ticker) continue
                rows.add(ManualBuyExecution(no, r.optString("orr_dt"), receipt.draft.ticker,
                    r.namuNum("orr_qty") ?: 0.0, r.namuNum("cns_qty") ?: 0.0, r.namuNum("ny_cns_orr_qty") ?: 0.0,
                    r.namuNum("cns_pr"), r.optString("orr_sts_nm"), r.optString("rjt_rsn_cts").trim().takeUnless { it == "_" }.orEmpty()))
            }
            cts = if (res.hasMore) res.cts else null
            pages++
        } while (cts != null && pages < 20)
        check(cts == null) { "주문 조회가 끝나지 않았습니다. 나무 앱에서 확인하세요." }
        rows.distinctBy { it.orderNo }
    }
}
