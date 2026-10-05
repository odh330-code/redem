package com.example.stocksgnal.strategy

import kotlin.math.abs
import kotlin.math.max

data class DailyCandle(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

enum class StrategyType(val label: String) {
    BREAKOUT("돌파"),
    MEAN_REVERSION("평균회귀"),
    PULLBACK_SWING("눌림목")
}

enum class SignalStage(val label: String, val rank: Int) {
    NOT_ELIGIBLE("조건 미충족", 0),
    WATCH("관찰", 1),
    READY("준비", 2),
    ENTRY("진입 조건 충족", 3)
}

data class RuleCheck(
    val label: String,
    val passed: Boolean
)

data class StrategySignal(
    val ticker: String,
    val date: String,
    val type: StrategyType,
    val stage: SignalStage,
    val summary: String,
    val checks: List<RuleCheck>,
    val rsi14: Double?,
    val sma20: Double?,
    val sma50: Double?,
    val sma200: Double?,
    val atr14: Double?
)

data class StrategySnapshot(
    val ticker: String,
    val date: String,
    val signals: List<StrategySignal>
) {
    val bestSignal: StrategySignal?
        get() = signals.maxByOrNull { it.stage.rank }
}

data class OpenPosition(
    val ticker: String,
    val type: StrategyType,
    val entryPrice: Double,
    val initialAtr14: Double,
    val highestClose: Double,
    val tradingDaysHeld: Int
)

enum class ExitReason(val label: String) {
    HOLD("보유"),
    STOP_LOSS("손절"),
    TRAILING_STOP("추적 손절"),
    PROFIT_TARGET("목표가 도달"),
    MEAN_REACHED("평균 회귀"),
    TIME_EXIT("기간 청산")
}

data class ExitDecision(
    val shouldExit: Boolean,
    val reason: ExitReason,
    val referencePrice: Double?,
    val note: String
)

object TradingStrategyEngine {

    fun evaluate(
        ticker: String,
        candles: List<DailyCandle>
    ): StrategySnapshot {

        require(candles.size >= 201) {
            "단기전략 계산에는 최소 201거래일의 일봉 데이터가 필요합니다."
        }

        val closes = candles.map { it.close }

        val sma20 = calculateSma(closes, 20)
        val sma50 = calculateSma(closes, 50)
        val sma200 = calculateSma(closes, 200)
        val rsi14 = calculateRsi(closes, 14)
        val atr14 = calculateAtr(candles, 14)

        val index = candles.lastIndex

        val signals = listOf(
            evaluateBreakout(
                ticker,
                candles,
                sma50,
                sma200,
                rsi14,
                atr14,
                index
            ),
            evaluateMeanReversion(
                ticker,
                candles,
                sma20,
                sma200,
                rsi14,
                atr14,
                index
            ),
            evaluatePullback(
                ticker,
                candles,
                sma20,
                sma50,
                sma200,
                rsi14,
                atr14,
                index
            )
        )

        return StrategySnapshot(
            ticker = ticker,
            date = candles[index].date,
            signals = signals
        )
    }

    private fun evaluateBreakout(
        ticker: String,
        candles: List<DailyCandle>,
        sma50: List<Double?>,
        sma200: List<Double?>,
        rsi14: List<Double?>,
        atr14: List<Double?>,
        index: Int
    ): StrategySignal {

        val today = candles[index]

        val currentSma50 = sma50[index]
        val currentSma200 = sma200[index]
        val currentRsi = rsi14[index]
        val currentAtr = atr14[index]

        val previous20DayHigh =
            candles.subList(index - 20, index)
                .maxOf { it.high }

        val trendOk =
            currentSma50 != null &&
                    currentSma200 != null &&
                    today.close > currentSma50 &&
                    currentSma50 > currentSma200

        val breakout =
            today.close > previous20DayHigh

        val nearBreakout =
            currentAtr != null &&
                    today.close >= previous20DayHigh - (currentAtr * 0.5)

        val momentumOk =
            currentRsi != null &&
                    currentRsi >= 55.0

        val stage = when {
            trendOk && breakout && momentumOk ->
                SignalStage.ENTRY

            trendOk && nearBreakout ->
                SignalStage.READY

            trendOk ->
                SignalStage.WATCH

            else ->
                SignalStage.NOT_ELIGIBLE
        }

        val checks = listOf(
            RuleCheck(
                "종가 > 50일선 > 200일선",
                trendOk
            ),
            RuleCheck(
                "직전 20일 최고가 돌파",
                breakout
            ),
            RuleCheck(
                "RSI(14) 55 이상",
                momentumOk
            )
        )

        return createSignal(
            ticker = ticker,
            date = today.date,
            type = StrategyType.BREAKOUT,
            stage = stage,
            checks = checks,
            rsi14 = currentRsi,
            sma20 = null,
            sma50 = currentSma50,
            sma200 = currentSma200,
            atr14 = currentAtr
        )
    }

    private fun evaluateMeanReversion(
        ticker: String,
        candles: List<DailyCandle>,
        sma20: List<Double?>,
        sma200: List<Double?>,
        rsi14: List<Double?>,
        atr14: List<Double?>,
        index: Int
    ): StrategySignal {

        val today = candles[index]

        val currentSma20 = sma20[index]
        val currentSma200 = sma200[index]
        val currentRsi = rsi14[index]
        val previousRsi = rsi14[index - 1]
        val currentAtr = atr14[index]

        val aboveLongTrend =
            currentSma200 != null &&
                    today.close > currentSma200

        val oversoldRecently =
            (index - 4..index).any { i ->
                val value = rsi14[i]
                value != null && value <= 30.0
            }

        val rsiRecovery =
            previousRsi != null &&
                    currentRsi != null &&
                    previousRsi <= 30.0 &&
                    currentRsi > 30.0

        val priceConfirmation =
            today.close > candles[index - 1].high

        val stage = when {
            aboveLongTrend &&
                    oversoldRecently &&
                    rsiRecovery &&
                    priceConfirmation ->
                SignalStage.ENTRY

            aboveLongTrend &&
                    oversoldRecently ->
                SignalStage.READY

            aboveLongTrend &&
                    currentRsi != null &&
                    currentRsi <= 40.0 ->
                SignalStage.WATCH

            else ->
                SignalStage.NOT_ELIGIBLE
        }

        val checks = listOf(
            RuleCheck(
                "종가가 200일선 위",
                aboveLongTrend
            ),
            RuleCheck(
                "최근 5일 내 RSI 30 이하",
                oversoldRecently
            ),
            RuleCheck(
                "RSI가 30 상향 돌파",
                rsiRecovery
            ),
            RuleCheck(
                "종가가 전일 고가 초과",
                priceConfirmation
            )
        )

        return createSignal(
            ticker = ticker,
            date = today.date,
            type = StrategyType.MEAN_REVERSION,
            stage = stage,
            checks = checks,
            rsi14 = currentRsi,
            sma20 = currentSma20,
            sma50 = null,
            sma200 = currentSma200,
            atr14 = currentAtr
        )
    }

    private fun evaluatePullback(
        ticker: String,
        candles: List<DailyCandle>,
        sma20: List<Double?>,
        sma50: List<Double?>,
        sma200: List<Double?>,
        rsi14: List<Double?>,
        atr14: List<Double?>,
        index: Int
    ): StrategySignal {

        val today = candles[index]

        val currentSma20 = sma20[index]
        val currentSma50 = sma50[index]
        val currentSma200 = sma200[index]
        val currentRsi = rsi14[index]
        val previousRsi = rsi14[index - 1]
        val currentAtr = atr14[index]

        val trendOk =
            currentSma50 != null &&
                    currentSma200 != null &&
                    today.close > currentSma50 &&
                    currentSma50 > currentSma200

        val pulledBackRecently =
            (index - 4 until index).any { i ->
                val average = sma20[i]

                average != null &&
                        candles[i].close < average
            }

        val previousSma20 =
            sma20[index - 1]

        val crossedSma20 =
            previousSma20 != null &&
                    currentSma20 != null &&
                    candles[index - 1].close <= previousSma20 &&
                    today.close > currentSma20

        val momentumRecovery =
            currentRsi != null &&
                    previousRsi != null &&
                    currentRsi >= 45.0 &&
                    currentRsi > previousRsi

        val stage = when {
            trendOk &&
                    pulledBackRecently &&
                    crossedSma20 &&
                    momentumRecovery ->
                SignalStage.ENTRY

            trendOk &&
                    pulledBackRecently ->
                SignalStage.READY

            trendOk ->
                SignalStage.WATCH

            else ->
                SignalStage.NOT_ELIGIBLE
        }

        val checks = listOf(
            RuleCheck(
                "종가 > 50일선 > 200일선",
                trendOk
            ),
            RuleCheck(
                "최근 5일 내 20일선 아래 조정",
                pulledBackRecently
            ),
            RuleCheck(
                "종가가 20일선 재돌파",
                crossedSma20
            ),
            RuleCheck(
                "RSI 45 이상이며 상승",
                momentumRecovery
            )
        )

        return createSignal(
            ticker = ticker,
            date = today.date,
            type = StrategyType.PULLBACK_SWING,
            stage = stage,
            checks = checks,
            rsi14 = currentRsi,
            sma20 = currentSma20,
            sma50 = currentSma50,
            sma200 = currentSma200,
            atr14 = currentAtr
        )
    }

    fun evaluateExit(
        position: OpenPosition,
        today: DailyCandle,
        sma20: Double?
    ): ExitDecision {

        val stopMultiplier =
            when (position.type) {
                StrategyType.MEAN_REVERSION -> 1.5
                StrategyType.BREAKOUT -> 2.0
                StrategyType.PULLBACK_SWING -> 2.0
            }

        val initialStop =
            position.entryPrice -
                    (position.initialAtr14 * stopMultiplier)

        if (today.low <= initialStop) {
            return ExitDecision(
                shouldExit = true,
                reason = ExitReason.STOP_LOSS,
                referencePrice = initialStop,
                note = "초기 손절가 도달"
            )
        }

        return when (position.type) {

            StrategyType.BREAKOUT -> {

                val trailingStop =
                    max(
                        initialStop,
                        position.highestClose -
                                (position.initialAtr14 * 3.0)
                    )

                when {
                    today.low <= trailingStop ->
                        ExitDecision(
                            true,
                            ExitReason.TRAILING_STOP,
                            trailingStop,
                            "추적 손절가 도달"
                        )

                    position.tradingDaysHeld >= 40 ->
                        ExitDecision(
                            true,
                            ExitReason.TIME_EXIT,
                            today.close,
                            "40거래일 경과"
                        )

                    else ->
                        ExitDecision(
                            false,
                            ExitReason.HOLD,
                            trailingStop,
                            "보유 유지"
                        )
                }
            }

            StrategyType.MEAN_REVERSION -> {

                when {
                    sma20 != null &&
                            today.close >= sma20 ->
                        ExitDecision(
                            true,
                            ExitReason.MEAN_REACHED,
                            today.close,
                            "20일선 복귀"
                        )

                    position.tradingDaysHeld >= 10 ->
                        ExitDecision(
                            true,
                            ExitReason.TIME_EXIT,
                            today.close,
                            "10거래일 경과"
                        )

                    else ->
                        ExitDecision(
                            false,
                            ExitReason.HOLD,
                            initialStop,
                            "평균 회귀 대기"
                        )
                }
            }

            StrategyType.PULLBACK_SWING -> {

                val target =
                    position.entryPrice +
                            (position.initialAtr14 * 4.0)

                when {
                    today.high >= target ->
                        ExitDecision(
                            true,
                            ExitReason.PROFIT_TARGET,
                            target,
                            "목표가 도달"
                        )

                    position.tradingDaysHeld >= 20 ->
                        ExitDecision(
                            true,
                            ExitReason.TIME_EXIT,
                            today.close,
                            "20거래일 경과"
                        )

                    else ->
                        ExitDecision(
                            false,
                            ExitReason.HOLD,
                            initialStop,
                            "보유 유지"
                        )
                }
            }
        }
    }

    private fun createSignal(
        ticker: String,
        date: String,
        type: StrategyType,
        stage: SignalStage,
        checks: List<RuleCheck>,
        rsi14: Double?,
        sma20: Double?,
        sma50: Double?,
        sma200: Double?,
        atr14: Double?
    ): StrategySignal {

        val passedRules =
            checks
                .filter { it.passed }
                .joinToString(" · ") { it.label }

        return StrategySignal(
            ticker = ticker,
            date = date,
            type = type,
            stage = stage,
            summary =
                if (passedRules.isBlank()) {
                    "충족 조건 없음"
                } else {
                    passedRules
                },
            checks = checks,
            rsi14 = rsi14,
            sma20 = sma20,
            sma50 = sma50,
            sma200 = sma200,
            atr14 = atr14
        )
    }

    private fun calculateSma(
        values: List<Double>,
        period: Int
    ): List<Double?> {

        val result =
            MutableList<Double?>(values.size) {
                null
            }

        var sum = 0.0

        for (i in values.indices) {

            sum += values[i]

            if (i >= period) {
                sum -= values[i - period]
            }

            if (i >= period - 1) {
                result[i] = sum / period
            }
        }

        return result
    }

    private fun calculateRsi(
        closes: List<Double>,
        period: Int
    ): List<Double?> {

        val result =
            MutableList<Double?>(closes.size) {
                null
            }

        var gainSum = 0.0
        var lossSum = 0.0

        for (i in 1..period) {

            val change =
                closes[i] - closes[i - 1]

            if (change > 0) {
                gainSum += change
            } else {
                lossSum += -change
            }
        }

        var averageGain =
            gainSum / period

        var averageLoss =
            lossSum / period

        result[period] =
            makeRsi(
                averageGain,
                averageLoss
            )

        for (i in period + 1 until closes.size) {

            val change =
                closes[i] - closes[i - 1]

            val gain =
                max(change, 0.0)

            val loss =
                max(-change, 0.0)

            averageGain =
                (
                        averageGain * (period - 1) +
                                gain
                        ) / period

            averageLoss =
                (
                        averageLoss * (period - 1) +
                                loss
                        ) / period

            result[i] =
                makeRsi(
                    averageGain,
                    averageLoss
                )
        }

        return result
    }

    private fun makeRsi(
        averageGain: Double,
        averageLoss: Double
    ): Double {

        if (
            averageGain == 0.0 &&
            averageLoss == 0.0
        ) {
            return 50.0
        }

        if (averageLoss == 0.0) {
            return 100.0
        }

        val rs =
            averageGain / averageLoss

        return 100.0 -
                (100.0 / (1.0 + rs))
    }

    private fun calculateAtr(
        candles: List<DailyCandle>,
        period: Int
    ): List<Double?> {

        val result =
            MutableList<Double?>(candles.size) {
                null
            }

        val trueRanges =
            MutableList(candles.size) {
                0.0
            }

        trueRanges[0] =
            candles[0].high -
                    candles[0].low

        for (i in 1 until candles.size) {

            val highLow =
                candles[i].high -
                        candles[i].low

            val highPreviousClose =
                abs(
                    candles[i].high -
                            candles[i - 1].close
                )

            val lowPreviousClose =
                abs(
                    candles[i].low -
                            candles[i - 1].close
                )

            trueRanges[i] =
                maxOf(
                    highLow,
                    highPreviousClose,
                    lowPreviousClose
                )
        }

        var atr =
            trueRanges
                .subList(1, period + 1)
                .average()

        result[period] = atr

        for (i in period + 1 until candles.size) {

            atr =
                (
                        atr * (period - 1) +
                                trueRanges[i]
                        ) / period

            result[i] = atr
        }

        return result
    }
}