package com.example.stocksgnal.data

import java.math.BigDecimal

enum class BuySession(val code: String, val label: String) {
    REGULAR("00", "정규장 지정가"), PRE("61", "프리마켓 지정가"),
    AFTER("62", "애프터마켓 지정가"), DAY("63", "주간거래 지정가")
}

data class ManualBuyDraft(
    val account: String, val ticker: String, val quantity: Long,
    val price: BigDecimal, val session: BuySession, val currencyCode: String
) {
    val totalUsd: BigDecimal get() = price.multiply(BigDecimal.valueOf(quantity))

    fun validate() {
        require(account.matches(Regex("[0-9]{11}"))) { "나무 계좌를 선택하세요" }
        require(ticker.matches(Regex("[A-Z][A-Z0-9.\\-]{0,11}"))) { "미국주식 티커를 확인하세요" }
        require(quantity in 1..999999999999999999L) { "수량은 양의 정수로 입력하세요" }
        require(price > BigDecimal.ZERO && price.stripTrailingZeros().scale() <= 2 && price.precision() - price.scale() <= 9) {
            "지정가는 달러 기준 양수, 소수점 두 자리까지 입력하세요"
        }
        require(currencyCode in setOf("1", "2")) { "증거금 통화를 선택하세요" }
    }

    /**
     * 실제 전송 본문. validate가 모든 문자열을 JSON 이스케이프가 필요 없는 ASCII로 제한한다.
     * Android JSONObject(Map)는 BigDecimal을 String으로 wrap하므로 사용하지 않는다.
     * 지정가는 Double 변환 없이 정확한 십진 JSON 숫자로 전송한다.
     */
    fun requestJson(): String {
        validate()
        return """{"Input_0":{"act_no":"$account","fc_sec_trd_nat_cd":"200","iem_cd":"$ticker","orr_qty":$quantity,"fc_orr_uit_pr":${price.setScale(2).toPlainString()},"ahi_nmn_pr_tp_cd":"${session.code}","wtm_cur_knd_cd":"$currencyCode"}}"""
    }
}

enum class ManualBuyState { PENDING, ACCEPTED, REJECTED, UNKNOWN }

internal fun classifyBuyResponse(http: Int, responseCode: String, orderNo: Long): ManualBuyState = when {
    http in 200..299 && responseCode in setOf("00171", "00000") && orderNo > 0 -> ManualBuyState.ACCEPTED
    http in setOf(400, 401, 403, 404, 405, 422, 429) && orderNo == 0L -> ManualBuyState.REJECTED
    else -> ManualBuyState.UNKNOWN
}

data class ManualBuyReceipt(
    val draft: ManualBuyDraft, val date: String, val state: ManualBuyState,
    val orderNo: Long = 0, val message: String = ""
) {
    // 알려진 접수번호가 있어야 의도적인 별도 주문을 작성할 수 있다.
    val canStartNextBuy: Boolean get() = state == ManualBuyState.ACCEPTED && orderNo > 0

    /** 저장 시 지수 표기가 일반 십진수로 바뀌어도 같은 주문으로 비교한다. */
    fun sameStoredOrder(other: ManualBuyReceipt): Boolean =
        copy(draft = draft.copy(price = draft.price.stripTrailingZeros())) ==
            other.copy(draft = other.draft.copy(price = other.draft.price.stripTrailingZeros()))
}

data class ManualBuyExecution(
    val orderNo: Long, val orderDate: String, val ticker: String,
    val ordered: Double, val filled: Double, val remaining: Double,
    val price: Double?, val status: String, val rejection: String
) {
    val fullyFilled: Boolean get() = ordered > 0 && filled >= ordered
    val summary: String get() = when {
        fullyFilled -> "체결 완료"
        filled > 0 -> "부분 체결"
        rejection.isNotBlank() -> "거부: $rejection"
        else -> status.ifBlank { "미체결" }
    }
}
