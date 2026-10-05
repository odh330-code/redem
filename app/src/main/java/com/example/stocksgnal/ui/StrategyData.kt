package com.example.stocksgnal.ui

import android.content.Context
import com.example.stocksgnal.data.CandleStorage
import com.example.stocksgnal.data.loadConfiguredWatchStocks
import com.example.stocksgnal.longterm.LedgerFill
import com.example.stocksgnal.longterm.LongStrategyRepository
import com.example.stocksgnal.longterm.PlannerResult
import com.example.stocksgnal.longterm.StrategyInstance
import com.example.stocksgnal.strategy.SignalStage
import com.example.stocksgnal.strategy.StrategySignal
import com.example.stocksgnal.strategy.TradingStrategyEngine

/*
 * 전략 메뉴(신호·계곡다이빙·단기전략·캘린더)가 함께 쓰는 데이터.
 * 백그라운드 스레드에서 호출한다.
 */

/** 관심종목 한 개의 단기전략 판정 */
data class ShortStrategyRow(
    val ticker: String,
    val name: String,
    val date: String,
    val signals: List<StrategySignal>
) {
    val best: StrategySignal? get() = signals.maxByOrNull { it.stage.rank }
}

/** 단기전략 판정 결과 */
data class ShortStrategyResult(
    val rows: List<ShortStrategyRow>,
    /** 데이터 부족(201일 미만)으로 계산하지 못한 종목 */
    val skipped: List<String>
)

class StrategyData(
    /** 계곡다이빙 전체 (나스닥, 반도체 등) */
    val plans: List<Pair<StrategyInstance, PlannerResult?>>,
    val short: ShortStrategyResult,
    val fills: List<LedgerFill>
) {
    /** 기준일·다음 거래일 표시용 (계산된 첫 전략) */
    val primaryMarket get() = plans.firstNotNullOfOrNull { it.second }?.market
}

fun loadShortStrategy(context: Context, repository: LongStrategyRepository): ShortStrategyResult {
    val cutoff = repository.completedCutoff().toString()
    val rows = mutableListOf<ShortStrategyRow>()
    val skipped = mutableListOf<String>()
    loadConfiguredWatchStocks(context).filter { it.enabled }.forEach { stock ->
        val candles = CandleStorage.loadCandles(context, stock.ticker).filter { it.date.take(10) <= cutoff }
        if (candles.size < 201) {
            skipped.add(stock.ticker)
            return@forEach
        }
        val snapshot = runCatching { TradingStrategyEngine.evaluate(stock.ticker, candles) }.getOrNull()
        if (snapshot == null) skipped.add(stock.ticker)
        else rows.add(ShortStrategyRow(stock.ticker, stock.name, snapshot.date.take(10), snapshot.signals))
    }
    return ShortStrategyResult(rows, skipped)
}

fun loadStrategyData(context: Context, repository: LongStrategyRepository): StrategyData = StrategyData(
    plans = runCatching { repository.computeAll() }.getOrDefault(emptyList()),
    short = loadShortStrategy(context, repository),
    fills = repository.ledger.fills()
)

/** 단기전략 진입(ENTRY) 신호만 */
fun ShortStrategyResult.entries(): List<Pair<ShortStrategyRow, StrategySignal>> =
    rows.flatMap { row -> row.signals.filter { it.stage == SignalStage.ENTRY }.map { row to it } }

/** 단기전략 준비(READY) 신호만 */
fun ShortStrategyResult.ready(): List<Pair<ShortStrategyRow, StrategySignal>> =
    rows.flatMap { row -> row.signals.filter { it.stage == SignalStage.READY }.map { row to it } }
