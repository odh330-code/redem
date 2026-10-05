package com.example.stocksgnal.longterm

import android.content.Context
import com.example.stocksgnal.data.CandleSource
import com.example.stocksgnal.data.CandleStorage
import com.example.stocksgnal.data.NamuMarketApi
import com.example.stocksgnal.strategy.DailyCandle
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** SharedPreferences 저장소 */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("long_strategy", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

/**
 * 계곡다이빙 데이터: 전략 인스턴스별 신호 종목·매수 종목 일봉, USD/KRW 환율 수신과 계산.
 * 네트워크·계산 함수는 모두 백그라운드 스레드에서 호출한다.
 */
class LongStrategyRepository(private val context: Context) {

    private val store = PrefsStore(context)
    val ledger = LongStrategyLedger(store) { LocalDate.now(ZoneId.of("Asia/Seoul")) }

    fun instances(): List<StrategyInstance> = ledger.instances()

    /** 계곡다이빙이 쓰는 모든 종목 (관심종목 갱신에서 중복 수신을 피하는 데 사용) */
    fun allSymbols(): Set<String> = instances().flatMap { listOf(it.signalTicker, it.tradeTicker) }.toSet()

    /** 수동 입력 환율이 있으면 우선 */
    fun usdKrw(): Double? =
        store.getString("usdkrw_manual")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: store.getString("usdkrw_auto")?.toDoubleOrNull()?.takeIf { it > 0 }

    fun usdKrwManual(): Double? = store.getString("usdkrw_manual")?.toDoubleOrNull()?.takeIf { it > 0 }

    fun setUsdKrwManual(v: Double?) = store.putString("usdkrw_manual", v?.toString() ?: "")

    fun lastRefreshText(): String = store.getString("last_refresh").orEmpty()

    /** 장 마감 전 당일 봉(미완성)은 제외 */
    fun completedCutoff(): LocalDate {
        val ny = ZonedDateTime.now(ZoneId.of("America/New_York"))
        return if (!ny.toLocalTime().isBefore(LocalTime.of(16, 20))) ny.toLocalDate() else ny.toLocalDate().minusDays(1)
    }

    fun latestCompletedTradingDay(): LocalDate {
        var d = completedCutoff()
        while (!UsMarketCalendar.isTradingDay(d)) d = d.minusDays(1)
        return d
    }

    private fun toBars(candles: List<DailyCandle>): List<QqqBar> {
        val cutoff = completedCutoff()
        return candles.mapNotNull { c ->
            runCatching { QqqBar(LocalDate.parse(c.date.take(10)), c.close) }.getOrNull()
        }.filter { !it.date.isAfter(cutoff) }.distinctBy { it.date }.sortedBy { it.date }
    }

    fun bars(symbol: String): List<QqqBar> =
        if (SemiIndexProxy.isIndex(symbol)) semiDerived().bars
        else toBars(CandleStorage.loadCandles(context, symbol))

    /** 반도체 지수(DJUSSC)는 USD 일봉 ÷ 2 로 환산한 값을 쓴다 (SemiIndexProxy 설명 참고) */
    private fun semiDerived(): SemiIndexProxy.Derived =
        SemiIndexProxy.fromEtf(toBars(CandleStorage.loadCandles(context, SemiIndexProxy.ETF_SYMBOL)))

    fun lastClose(symbol: String): Double? = bars(symbol).lastOrNull()?.close

    /** 인스턴스 하나 계산. 새로 확정된 예산 배율은 저장 */
    fun compute(inst: StrategyInstance): PlannerResult? {
        val semi = SemiIndexProxy.isIndex(inst.signalTicker)
        val derived = if (semi) semiDerived() else null
        if (derived?.problem != null) return null
        val signalBars = derived?.bars ?: bars(inst.signalTicker)
        // 화면에는 환산지수임을 드러내는 이름으로 표시한다 (신호 판정에는 영향 없음)
        val signalLabel = if (semi) "${inst.signalTicker}(USD÷2)" else inst.signalTicker
        val result = LongStrategyPlanner.compute(
            signalBars, lastClose(inst.tradeTicker), usdKrw(), ledger.snapshotFor(inst),
            signalLabel, inst.tradeTicker
        )
        result?.let { ledger.putScales(it.newScales) }
        return result
    }

    /** 모든 인스턴스 계산 (데이터가 없으면 결과 null) */
    fun computeAll(): List<Pair<StrategyInstance, PlannerResult?>> =
        instances().map { it to runCatching { compute(it) }.getOrNull() }

    /** 나스닥(QQQ→QLD) 계산 — 예전 호출부 호환용 */
    fun compute(): PlannerResult? = compute(StrategyInstance.NASDAQ)

    /** 나무증권에서 모든 인스턴스 종목과 환율 갱신. 결과 메시지 반환 */
    fun refresh(): String {

        val latestTradingDay = latestCompletedTradingDay()
        val messages = mutableListOf<String>()

        // minRows: 저장된 일봉이 이보다 적으면 전체를 다시 받는다 (USD는 예전에 최근 300일만 받았을 수 있음)
        fun update(symbol: String, fullSize: Int, minRows: Int = 0) {
            // 나무증권 기준이 아닌 예전 일봉은 전체를 다시 받아 통째로 바꾼다
            val namuBased = CandleSource.isCurrentSource(context, symbol)
            val saved = if (namuBased) CandleStorage.loadCandles(context, symbol) else emptyList()
            val lastSaved = saved.maxOfOrNull { it.date.take(10) }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val thin = saved.size < minRows
            if (!thin && saved.isNotEmpty() && lastSaved != null && !lastSaved.isBefore(latestTradingDay)) {
                messages.add("$symbol 최신")
                return
            }
            // 전체 받기는 나무증권 기준 일봉이 하나도 없을 때(또는 minRows보다 적을 때)만. 나무증권이 주는 기간이
            // fullSize보다 짧아도 한 번 받은 뒤에는 새 날짜만 이어 받는다(매번 전체를 다시 받지 않도록).
            val full = saved.isEmpty() || thin
            val result = if (full) {
                CandleSource.getDailyCandles(context, symbol, outputSize = fullSize)
            } else {
                CandleSource.getDailyCandles(
                    context,
                    symbol,
                    outputSize = 500,
                    startDate = saved.maxOf { it.date }.take(10),
                    endDate = latestTradingDay.toString()
                )
            }
            if (result.success) {
                val n = if (full) {
                    CandleStorage.saveCandles(context, symbol, result.candles.sortedBy { it.date })
                    result.candles.size
                } else {
                    CandleStorage.mergeAndSave(context, symbol, result.candles)
                }
                CandleSource.markCurrentSource(context, symbol)
                messages.add("$symbol ${n}일 (${result.message})")
            } else {
                messages.add("$symbol 실패: ${result.message}")
            }
        }

        val insts = instances()
        // 신호 종목: 252일 고점·RSI·재생을 위해 긴 이력 필요 (처음 한 번 5000일)
        // 반도체 지수(DJUSSC)는 나무증권에 없어서 USD 일봉을 받아 ÷2 로 환산한다
        val signalSymbols = insts.map { SemiIndexProxy.dataSymbol(it.signalTicker) }.distinct()
        signalSymbols.forEach { symbol ->
            update(symbol, fullSize = 5000, minRows = if (symbol == SemiIndexProxy.ETF_SYMBOL) 400 else 0)
        }
        // 매수 종목: 최근 가격만 필요
        insts.map { it.tradeTicker }.distinct()
            .filter { it !in signalSymbols }
            .forEach { update(it, fullSize = 300) }

        // 반도체 환산지수: 홈의 데이터 현황이 읽도록 저장해 두고, 환산이 안 되면(분할 미반영 등) 이유를 알린다
        insts.map { it.signalTicker }.filter { SemiIndexProxy.isIndex(it) }.distinct().forEach { symbol ->
            val derived = semiDerived()
            if (derived.problem != null) {
                messages.add("$symbol 환산 실패: ${derived.problem}")
            } else if (derived.bars.isNotEmpty()) {
                CandleStorage.saveCandles(
                    context, symbol,
                    derived.bars.map { DailyCandle(it.date.toString(), it.close, it.close, it.close, it.close, 0.0) }
                )
                messages.add("$symbol ${derived.bars.size}일 (USD÷2 환산)")
            }
        }

        // 환율: 나무증권 해외주식 현재가 응답의 환율
        NamuMarketApi.usdKrw(context)
            .onSuccess {
                store.putString("usdkrw_auto", it.toString())
                messages.add("환율 %,.1f".format(it))
            }
            .onFailure { messages.add("환율 실패: ${it.message}") }

        val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val text = "%04d-%02d-%02d %02d:%02d · %s".format(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, messages.joinToString(" · "))
        store.putString("last_refresh", text)
        return text
    }
}
