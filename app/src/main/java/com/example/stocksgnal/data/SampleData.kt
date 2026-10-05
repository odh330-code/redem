package com.example.stocksgnal.data

import com.example.stocksgnal.model.IndicatorResult
import com.example.stocksgnal.model.SignalStatus
import com.example.stocksgnal.model.StockSignal
import com.example.stocksgnal.model.WatchStock


object SampleData {

    val watchStocks = listOf(
        WatchStock(
            ticker = "QLD",
            name = "ProShares Ultra QQQ"
        ),
        WatchStock(
            ticker = "USD",
            name = "ProShares Ultra Semiconductors"
        ),
        WatchStock(
            ticker = "GOOGL",
            name = "Alphabet"
        )
    )


    val signals = listOf(

        StockSignal(
            ticker = "QLD",
            name = "ProShares Ultra QQQ",
            price = 72.40,
            changePercent = -2.35,
            buyScore = 8,
            cautionScore = 2,
            status = SignalStatus.BUY_WATCH,

            indicators = listOf(
                IndicatorResult(
                    name = "RSI(14)",
                    value = "29.4",
                    score = 2,
                    description = "과매도 구간 진입"
                ),
                IndicatorResult(
                    name = "MACD",
                    value = "하락 둔화",
                    score = 2,
                    description = "히스토그램 하락폭 축소"
                ),
                IndicatorResult(
                    name = "볼린저",
                    value = "%B 0.08",
                    score = 2,
                    description = "하단 밴드 부근"
                ),
                IndicatorResult(
                    name = "거래량",
                    value = "증가",
                    score = 1,
                    description = "최근 평균 대비 거래량 증가"
                ),
                IndicatorResult(
                    name = "고점 대비 낙폭",
                    value = "-14.2%",
                    score = 1,
                    description = "최근 고점 대비 의미 있는 조정"
                )
            )
        ),


        StockSignal(
            ticker = "USD",
            name = "ProShares Ultra Semiconductors",
            price = 61.80,
            changePercent = -1.10,
            buyScore = 6,
            cautionScore = 3,
            status = SignalStatus.OBSERVE,

            indicators = listOf(
                IndicatorResult(
                    name = "RSI(14)",
                    value = "34.8",
                    score = 1,
                    description = "과매도 접근"
                ),
                IndicatorResult(
                    name = "MACD",
                    value = "하락",
                    score = 1,
                    description = "아직 하락 모멘텀 유지"
                ),
                IndicatorResult(
                    name = "볼린저",
                    value = "%B 0.18",
                    score = 2,
                    description = "하단 밴드 접근"
                ),
                IndicatorResult(
                    name = "거래량",
                    value = "보통",
                    score = 1,
                    description = "특별한 거래량 변화 없음"
                ),
                IndicatorResult(
                    name = "고점 대비 낙폭",
                    value = "-11.7%",
                    score = 1,
                    description = "조정 진행 중"
                )
            )
        ),


        StockSignal(
            ticker = "GOOGL",
            name = "Alphabet",
            price = 248.30,
            changePercent = 1.25,
            buyScore = 3,
            cautionScore = 5,
            status = SignalStatus.NEUTRAL,

            indicators = listOf(
                IndicatorResult(
                    name = "RSI(14)",
                    value = "55.2",
                    score = 0,
                    description = "중립 구간"
                ),
                IndicatorResult(
                    name = "MACD",
                    value = "상승",
                    score = 1,
                    description = "상승 모멘텀 유지"
                ),
                IndicatorResult(
                    name = "볼린저",
                    value = "%B 0.62",
                    score = 0,
                    description = "밴드 중앙 상단"
                ),
                IndicatorResult(
                    name = "거래량",
                    value = "보통",
                    score = 1,
                    description = "평균적인 거래량"
                ),
                IndicatorResult(
                    name = "고점 대비 낙폭",
                    value = "-3.1%",
                    score = 1,
                    description = "고점 부근"
                )
            )
        )
    )
}