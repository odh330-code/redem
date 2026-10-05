package com.example.stocksgnal.longterm

/*
 * 화면에 보여줄 "지금 전략이 어느 단계인가" 한 줄 요약.
 *
 * - 계산(엔진)은 건드리지 않고 표시용으로만 묶는다.
 * - 신호 종목(QQQ 등) 종가만으로 정해진다. 계좌 보유·예수금과는 무관하다.
 */
enum class StrategyStage(val label: String) {
    WAIT("대기"),
    BUY_1_1("매수 1-1"),
    BUY_1_2("매수 1-2"),
    BUY_1_3("매수 1-3"),
    SELL_WAIT("매도 대기"),
    SELL_SIGNAL("매도 2 신호");

    companion object {
        /**
         * 캠페인 1 ~ 이 값까지를 "1-1", 그 다음 캠페인부터를 "1-2"로 부른다.
         * 1-1/1-2의 정의가 바뀌면 이 숫자만 고치면 된다.
         */
        const val LAST_CAMPAIGN_OF_1_1 = 2

        fun forCampaign(campaign: Int): StrategyStage =
            if (campaign <= LAST_CAMPAIGN_OF_1_1) BUY_1_1 else BUY_1_2
    }
}
