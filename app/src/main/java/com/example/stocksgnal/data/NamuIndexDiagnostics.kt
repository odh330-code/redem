package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 화면에서 사용자가 누를 때만 조회. 정상 다우 코드와 반도체 후보의 전체 오류 문구를 비교한다. */
object NamuIndexDiagnostics {
    fun check(context: Context): List<String> {
        val results = mutableListOf<String>()
        val date = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)
        for (code in listOf(".DJI", ".DJUSSC", "DJUSSC")) {
            val input = JSONObject().put("iem_cd", code).put("end_dt", date).put("array_cnt", "2")
                .put("maxavg", "").put("gubun", "1").put("xtick", "001").put("today_cls", "0").put("scale_change", "")
            val response = NamuHttp.post(context, "/gbstock/quote/v1/symbolIndexFxPeriod", JSONObject().put("Input_0", input))
            response.onSuccess { res ->
                val json = res.json
                val out = json.optJSONObject("Output_0")
                val rows = json.optJSONArray("Output_1")
                val count = rows?.length() ?: 0
                val latest = rows?.optJSONObject(0)
                val price = out?.namuNum("ovrs_prpr") ?: latest?.namuNum("ovrs_prpr")
                results.add("$code · 응답 ${json.optString("rsp_cd")} · ${json.optString("rsp_msg")}\n" +
                    "종목 ${out?.optString("iem_nm").orEmpty()} / 일봉 ${count}개 / 값 ${price ?: "없음"} / 최근 ${latest?.optString("bsop_date").orEmpty()}")
            }.onFailure { results.add("$code · ${it.message.orEmpty()}") }
            if (response.exceptionOrNull() is NamuHttp.Fatal) break
        }
        return results
    }
}
