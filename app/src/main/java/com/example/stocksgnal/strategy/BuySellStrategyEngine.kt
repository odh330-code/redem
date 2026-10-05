package com.example.stocksgnal.strategy

import kotlin.math.max

enum class BuySellPhase(val label: String) {
    WAITING("대기"),
    BUYING("매수 1 진행"),
    SELL_WAITING("매도 2 대기")
}

enum class NextOrderSide(val label: String) {
    NONE("주문 없음"),
    BUY("매수"),
    SELL("매도")
}

data class BuySellStrategyResult(
    val phase: BuySellPhase,
    val qqqDate: String,
    val qqqClose: Double,
    val h: Double?,
    val m: Double?,
    val drawdownPercent: Double?,
    val rsi14: Double?,
    val ma60: Double?,
    val nextOrderSide: NextOrderSide,
    val nextOrderTitle: String,
    val nextOrderDetail: String,
    val reason: String,
    val warning: String? = null
)

object BuySellStrategyEngine {

    private val campaignLevels = listOf(0.05, 0.10, 0.15, 0.20, 0.25, 0.30)

    fun evaluate(
        qqqCandles: List<DailyCandle>,
        qldCandles: List<DailyCandle>
    ): BuySellStrategyResult {
        val qqq = qqqCandles.sortedBy { it.date }
        val qld = qldCandles.sortedBy { it.date }

        if (qqq.size < 253) {
            return emptyResult("QQQ 일봉이 최소 253개 필요합니다.")
        }

        if (qld.isEmpty()) {
            return emptyResult("QLD 일봉이 아직 저장되지 않았습니다.")
        }

        val closes = qqq.map { it.close }
        val rsi = calculateRsi(closes, 14)
        val ma60 = calculateSma(closes, 60)
        val high252 = previousRollingHigh(closes, 252)

        var phase = BuySellPhase.WAITING
        var h: Double? = null
        var m: Double? = null
        var firstCampaignDate: String? = null
        var campaign1WaitingIndex: Int? = null
        var lastTriggeredCampaign = 0
        var nextOrderSide = NextOrderSide.NONE
        var nextOrderTitle = "다음 거래일 주문 없음"
        var nextOrderDetail = "오늘 기준 새 주문 조건은 없습니다."
        var reason = "아직 매수 1 시작 조건이 아닙니다."

        for (index in 252 until qqq.size) {
            val candle = qqq[index]
            val currentHigh = high252[index] ?: continue
            val currentRsi = rsi[index]
            val close = candle.close

            nextOrderSide = NextOrderSide.NONE
            nextOrderTitle = "다음 거래일 주문 없음"
            nextOrderDetail = "오늘 기준 새 주문 조건은 없습니다."

            when (phase) {
                BuySellPhase.WAITING -> {
                    val drawdown = 1.0 - close / currentHigh

                    if (drawdown >= 0.05) {
                        phase = BuySellPhase.BUYING
                        h = currentHigh
                        firstCampaignDate = candle.date
                        lastTriggeredCampaign = 1

                        if (drawdown >= 0.10 || (currentRsi != null && currentRsi <= 40.0)) {
                            nextOrderSide = NextOrderSide.BUY
                            nextOrderTitle = "매수 1 · 캠페인1 시작"
                            nextOrderDetail = "다음 거래일 QLD 시가 기준, 총자산의 1%씩 5거래일 분할매수 시작"
                            reason =
                                if (drawdown >= 0.10) {
                                    "-10% 이상 도달로 RSI 확인 없이 시작"
                                } else {
                                    "-5% 도달 후 RSI(14) 40 이하"
                                }
                        } else {
                            campaign1WaitingIndex = index
                            nextOrderTitle = "매수 1 · RSI 대기"
                            nextOrderDetail = "RSI가 40 이하가 되거나, -10% 도달 또는 5거래일 경과 시 시작"
                            reason = "-5%는 도달했지만 RSI(14)가 40 초과"
                        }
                    }
                }

                BuySellPhase.BUYING -> {
                    val fixedH = h ?: continue
                    val drawdown = 1.0 - close / fixedH

                    if (close >= fixedH) {
                        phase = BuySellPhase.SELL_WAITING
                        m = close
                        campaign1WaitingIndex = null
                        nextOrderTitle = "매도 2 대기 시작"
                        nextOrderDetail = "H 회복 완료. QLD는 즉시 매도하지 않고 M 기준 -3%를 기다립니다."
                        reason = "QQQ 종가가 H를 회복"
                    } else {
                        val reachedCampaign = campaignLevels
                            .indexOfLast { drawdown >= it }
                            .let { it + 1 }

                        if (reachedCampaign > lastTriggeredCampaign) {
                            lastTriggeredCampaign = reachedCampaign
                            nextOrderSide = NextOrderSide.BUY
                            nextOrderTitle = "매수 1 · 캠페인$reachedCampaign 도달"
                            nextOrderDetail =
                                if (reachedCampaign == 2) {
                                    "다음 거래일 QLD 시가 기준, 누적 목표액에서 실제 누적 매수액을 뺀 금액을 5거래일 재분할"
                                } else {
                                    "다음 거래일 QLD 시가 기준, 누적 목표액에서 실제 누적 매수액을 뺀 금액을 8주 주 1회 재분할"
                                }
                            reason = "QQQ 낙폭이 -${reachedCampaign * 5}% 구간에 도달"
                        } else if (campaign1WaitingIndex != null) {
                            val waitedDays = index - campaign1WaitingIndex!!
                            if (
                                drawdown >= 0.10 ||
                                (currentRsi != null && currentRsi <= 40.0) ||
                                waitedDays >= 5
                            ) {
                                campaign1WaitingIndex = null
                                nextOrderSide = NextOrderSide.BUY
                                nextOrderTitle = "매수 1 · 캠페인1 대기 종료"
                                nextOrderDetail = "다음 거래일 QLD 시가 기준, 남은 캠페인1 금액을 5거래일 분할매수"
                                reason = when {
                                    drawdown >= 0.10 -> "-10% 도달"
                                    currentRsi != null && currentRsi <= 40.0 -> "RSI(14) 40 이하"
                                    else -> "5거래일 대기 완료"
                                }
                            }
                        }
                    }
                }

                BuySellPhase.SELL_WAITING -> {
                    val currentM = max(m ?: close, close)
                    m = currentM

                    if (close <= currentM * 0.97) {
                        nextOrderSide = NextOrderSide.SELL
                        nextOrderTitle = "매도 2 · 매도 신호"
                        nextOrderDetail = "다음 거래일 QLD 시가 기준, 1번 계좌는 부족 예수금만큼 · 2번 계좌는 이번 캠페인 매수분 전량 매도"
                        reason = "QQQ 종가가 M 대비 -3% 이하"

                        if (index < qqq.lastIndex) {
                            phase = BuySellPhase.WAITING
                            h = null
                            m = null
                            firstCampaignDate = null
                            lastTriggeredCampaign = 0
                        }
                    }
                }
            }
        }

        val last = qqq.last()
        val activeH = h
        val drawdownPercent =
            activeH?.let { (last.close / it - 1.0) * 100.0 }

        return BuySellStrategyResult(
            phase = phase,
            qqqDate = last.date,
            qqqClose = last.close,
            h = activeH,
            m = m,
            drawdownPercent = drawdownPercent,
            rsi14 = rsi.lastOrNull(),
            ma60 = ma60.lastOrNull(),
            nextOrderSide = nextOrderSide,
            nextOrderTitle = nextOrderTitle,
            nextOrderDetail = nextOrderDetail,
            reason = reason,
            warning =
                if (firstCampaignDate == null && phase == BuySellPhase.WAITING) {
                    "현재 저장된 데이터 기준으로 진행 중인 매수/매도 사이클은 없습니다."
                } else {
                    null
                }
        )
    }

    private fun emptyResult(message: String): BuySellStrategyResult {
        return BuySellStrategyResult(
            phase = BuySellPhase.WAITING,
            qqqDate = "-",
            qqqClose = 0.0,
            h = null,
            m = null,
            drawdownPercent = null,
            rsi14 = null,
            ma60 = null,
            nextOrderSide = NextOrderSide.NONE,
            nextOrderTitle = "데이터 필요",
            nextOrderDetail = message,
            reason = message,
            warning = message
        )
    }

    private fun previousRollingHigh(values: List<Double>, period: Int): List<Double?> {
        val result = MutableList<Double?>(values.size) { null }

        for (index in period until values.size) {
            result[index] = values.subList(index - period, index).maxOrNull()
        }

        return result
    }

    private fun calculateSma(values: List<Double>, period: Int): List<Double?> {
        val result = MutableList<Double?>(values.size) { null }
        var sum = 0.0

        for (index in values.indices) {
            sum += values[index]

            if (index >= period) {
                sum -= values[index - period]
            }

            if (index >= period - 1) {
                result[index] = sum / period
            }
        }

        return result
    }

    private fun calculateRsi(closes: List<Double>, period: Int): List<Double?> {
        val result = MutableList<Double?>(closes.size) { null }

        if (closes.size <= period) {
            return result
        }

        var gainSum = 0.0
        var lossSum = 0.0

        for (index in 1..period) {
            val change = closes[index] - closes[index - 1]
            if (change > 0) gainSum += change else lossSum += -change
        }

        var averageGain = gainSum / period
        var averageLoss = lossSum / period
        result[period] = makeRsi(averageGain, averageLoss)

        for (index in period + 1 until closes.size) {
            val change = closes[index] - closes[index - 1]
            val gain = max(change, 0.0)
            val loss = max(-change, 0.0)

            averageGain = (averageGain * (period - 1) + gain) / period
            averageLoss = (averageLoss * (period - 1) + loss) / period
            result[index] = makeRsi(averageGain, averageLoss)
        }

        return result
    }

    private fun makeRsi(averageGain: Double, averageLoss: Double): Double {
        if (averageGain == 0.0 && averageLoss == 0.0) return 50.0
        if (averageLoss == 0.0) return 100.0
        val rs = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}
