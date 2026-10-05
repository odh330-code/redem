package com.example.stocksgnal.model

// 종목의 현재 투자 상태
enum class SignalStatus {
    OBSERVE,        // 관찰
    BUY_WATCH,      // 분할매수 검토
    BUY,            // 매수 구간
    NEUTRAL,        // 중립
    CAUTION         // 경계
}


// 개별 지표 결과
data class IndicatorResult(
    val name: String,
    val value: String,
    val score: Int,
    val description: String
)


// 종목 하나의 전체 신호 데이터
data class StockSignal(
    val ticker: String,
    val name: String,

    // 현재 가격
    val price: Double,

    // 전일 대비 등락률
    val changePercent: Double,

    // 매수 점수
    val buyScore: Int,

    // 매도/경계 점수
    val cautionScore: Int,

    // 현재 상태
    val status: SignalStatus,

    // 각 지표의 상세 결과
    val indicators: List<IndicatorResult>
)