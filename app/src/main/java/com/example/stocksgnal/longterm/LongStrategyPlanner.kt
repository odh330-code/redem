package com.example.stocksgnal.longterm

import java.time.LocalDate
import kotlin.math.max

/*
 * 엔진 결과 + 장부 → 화면에 보여줄 값 (원화 금액, 다음 거래일 할 일, 조건 상태).
 * Android 의존성 없음.
 */

data class ConditionView(val label: String, val passed: Boolean, val detail: String = "")

data class MarketView(
    val date: LocalDate,
    val nextTradingDay: LocalDate,
    val mode: EngineMode,
    val qqqClose: Double,
    /** 대기: 현재 252일 고점, 매수 1: 고정된 H, 매도 대기: 구간 H */
    val referenceHigh: Double,
    val drawdownPct: Double,
    val rsi14: Double,
    val ma60: Double,
    val maRun: Int,
    val campaignStart: LocalDate?,
    val reachedCampaign: Int,             // 0 = 없음
    val maxDrawdownPct: Double,
    val campaign1State: Campaign1State?,
    val waitDays: Int?,
    val recoveryConditions: List<ConditionView>,
    val sellPeak: Double?,                // M
    val sellTrigger: Double?,             // M × (1 - x%)
    val sellSignalToday: Boolean,
    val sellSignalDate: LocalDate?,
    val newCampaignToday: Boolean,
    val sellDropPct: Double,
    /** 신호(판정) 종목, 예: QQQ */
    val signalTicker: String = "QQQ",
    /** 매수(체결) 종목, 예: QLD */
    val tradeTicker: String = "QLD",
    /** 화면용 현재 단계 (대기 / 매수 1-1 / 1-2 / 1-3 / 매도 대기 / 매도 2 신호) */
    val stage: StrategyStage = StrategyStage.WAIT,
    /** 단계 보충 설명 한 줄 */
    val stageDetail: String = ""
) {
    val campaignPrices: List<Pair<Int, Double>>
        get() = LongStrategyParams.LEVELS.mapIndexed { i, l -> (i + 1) to referenceHigh * (1 - l) }
}

data class OrderView(
    val kind: FillKind,
    val title: String,
    val detail: String,
    val amountKrw: Double,
    val approxShares: Double?,
    val recorded: Boolean
)

data class ScheduleView(val date: LocalDate, val title: String, val amountKrw: Double)

data class AccountView(
    val settings: AccountSettings,
    val scale: Double?,
    val campaignStart: LocalDate?,
    val spent12Krw: Double,
    val target12Krw: Double,              // 도달한 캠페인까지 누적 목표
    val total12Krw: Double,               // 1-1+1-2 전체 (16⅔pp × f)
    val spent13Krw: Double,
    val total13Krw: Double,
    val rounds13: Int,
    val orders: List<OrderView>,
    val schedule: List<ScheduleView>,
    val notes: List<String>
)

class PlannerResult(
    val market: MarketView,
    val accounts: Map<Int, AccountView>,
    val newScales: Map<Pair<Int, LocalDate>, Double>,
    val history: List<EpisodeSummary>
)

data class EpisodeSummary(
    val start: LocalDate,
    val end: LocalDate?,
    val maxDrawdownPct: Double,
    val reachedCampaign: Int,
    val spent12Pp: Double,
    val spent13Pp: Double,
    val sellSignal: LocalDate?
)

object LongStrategyPlanner {

    fun campaignName(n: Int) = "캠페인$n (-${n * 5}%)"

    /**
     * @param bars 신호 종목 일봉 (QQQBar는 이름만 QQQ, 어떤 종목이든 가능)
     * @param qldLastClose 매수 종목 최근 종가(달러)
     * @param usdKrw 원/달러 환율 (평가액·수량 추정용)
     */
    fun compute(
        bars: List<QqqBar>, qldLastClose: Double?, usdKrw: Double?, ledger: LedgerSnapshot,
        signalTicker: String = "QQQ", tradeTicker: String = "QLD"
    ): PlannerResult? {
        if (bars.size < 320 || ledger.accounts.isEmpty()) return null
        val qldKrw = if (qldLastClose != null && usdKrw != null && qldLastClose > 0 && usdKrw > 0) qldLastClose * usdKrw else null
        val ind = QqqIndicators(bars)
        val engine = LongStrategyEngine(ind, ledger.sellDropPct)
        val last = ind.n - 1
        val nextDay = LongStrategyEngine.nextWeekday(ind.date[last])
        val newScales = mutableMapOf<Pair<Int, LocalDate>, Double>()

        val results = ledger.accounts.mapValues { (acct, settings) ->
            val fillsByDate = ledger.fills.filter { it.account == acct }.groupBy { it.date }
            engine.replay(
                startDate = LocalDate.MIN,
                scaleFor = { t, sameDay ->
                    val d = ind.date[t]
                    ledger.scales[acct to d] ?: if (d.isBefore(ledger.recordStart)) 1.0 else {
                        val expectedSale = if (sameDay) expectedSaleKrw(acct, settings, qldKrw) else 0.0
                        LongStrategyEngine.budgetScale(settings.cashKrw + expectedSale, settings.budgetKrw)
                            .also { newScales[acct to d] = it }
                    }
                },
                fillsFor = { t, orders ->
                    val d = ind.date[t]
                    if (d.isBefore(ledger.recordStart)) LongStrategyEngine.autoFill(orders)
                    else {
                        val f = fillsByDate[d].orEmpty()
                        DayFill(
                            settings.krwToPp(f.filter { it.kind == FillKind.CAMPAIGN }.sumOf { it.amountKrw }),
                            settings.krwToPp(f.filter { it.kind == FillKind.RECOVERY }.sumOf { it.amountKrw })
                        )
                    }
                }
            )
        }

        val r1 = results.getValue(ledger.accounts.keys.min())
        val market = marketView(ind, r1, nextDay).copy(signalTicker = signalTicker, tradeTicker = tradeTicker)
        val accounts = results.mapValues { (acct, r) ->
            accountView(engine, ind, r, ledger.accounts.getValue(acct), ledger, qldKrw, nextDay, tradeTicker)
        }
        val history = r1.episodes.map { rec ->
            val e = rec.episode
            EpisodeSummary(ind.date[e.start], if (e.ended >= 0) ind.date[e.ended] else null, e.maxDrawdown * 100,
                e.depth + 1, e.spent12, e.spent13, rec.sellCycle?.takeIf { it.signaled }?.let { ind.date[it.signalIndex] })
        }
        return PlannerResult(market, accounts, newScales, history)
    }

    private fun marketView(ind: QqqIndicators, r: ReplayResult, nextDay: LocalDate): MarketView {
        val last = ind.n - 1
        val c = ind.close[last]
        val ep = r.activeEpisode
        val lastRec = r.episodes.lastOrNull()
        val sell = lastRec?.sellCycle?.takeIf { r.mode == EngineMode.SELL_WAIT }
        val refHigh = when {
            ep != null -> ep.H
            sell != null -> lastRec.episode.H
            else -> ind.high252[last]
        }
        val conditions = if (ep != null) {
            val d = ep.detector
            listOf(
                ConditionView("이전 저점보다 높은 저점", d.pendingSupport != null,
                    d.pendingSupport?.let { "보호 저점 %.2f".format(it) } ?: (d.lastLow?.let { "최근 저점 %.2f".format(it.second) } ?: "저점 탐색 중")),
                ConditionView("반등 고점 종가 돌파", d.pendingBroken,
                    d.pendingThreshold?.let { "기준 %.2f".format(it) } ?: "-"),
                ConditionView("60일선 위 3거래일 연속", d.maRun >= 3, "${d.maRun}일째 · 60일선 %.2f".format(ind.ma60[last]))
            )
        } else emptyList()
        val lastSignal = r.episodes.lastOrNull { it.sellCycle != null }?.sellCycle?.takeIf { it.signaled }?.let { ind.date[it.signalIndex] }
        val stageInfo = stageOf(r)
        return MarketView(
            date = ind.date[last],
            nextTradingDay = nextDay,
            mode = r.mode,
            qqqClose = c,
            referenceHigh = refHigh,
            drawdownPct = (c / refHigh - 1) * 100,
            rsi14 = ind.rsi14[last],
            ma60 = ind.ma60[last],
            maRun = ep?.detector?.maRun ?: 0,
            campaignStart = ep?.let { ind.date[it.start] },
            reachedCampaign = ep?.let { it.depth + 1 } ?: 0,
            maxDrawdownPct = ep?.let { -it.maxDrawdown * 100 } ?: 0.0,
            campaign1State = ep?.c1,
            waitDays = ep?.takeIf { it.c1 == Campaign1State.WAITING }?.let { last - it.waitStart },
            recoveryConditions = conditions,
            sellPeak = sell?.peakM,
            sellTrigger = sell?.let { it.peakM * (1 - r.sellDropPct / 100) },
            sellSignalToday = r.sellSignalToday,
            sellSignalDate = lastSignal,
            newCampaignToday = r.newCampaignToday,
            sellDropPct = r.sellDropPct,
            stage = stageInfo.first,
            stageDetail = stageInfo.second
        )
    }

    /** 엔진 재생 결과 → 화면용 단계와 설명. 엔진 계산은 바꾸지 않고 읽기만 한다. */
    private fun stageOf(r: ReplayResult): Pair<StrategyStage, String> {
        val ep = r.activeEpisode
        return when (r.mode) {
            EngineMode.WAIT ->
                StrategyStage.WAIT to "낙폭이 -5%에 닿으면 매수 1이 시작됩니다"
            EngineMode.SELL_WAIT ->
                if (r.sellSignalToday) StrategyStage.SELL_SIGNAL to "다음 거래일 시가에 매도"
                else StrategyStage.SELL_WAIT to "최고 종가 M 대비 -%.1f%%에서 매도 신호".format(r.sellDropPct)
            EngineMode.BUY -> {
                if (ep == null) {
                    StrategyStage.WAIT to ""
                } else if (ep.after13) {
                    val detail = when {
                        ep.spent13 >= ep.b13 - 1e-9 -> "1-3 집행 완료 (5/5회)"
                        ep.active13 -> "1-3 집행 중 ${ep.rounds13}/5회"
                        else -> "1-3 조건 이탈 · 추가 매수 없음"
                    }
                    StrategyStage.BUY_1_3 to detail
                } else {
                    val campaign = max(ep.currentCampaign(), 1)
                    StrategyStage.forCampaign(campaign) to
                        "${campaignName(campaign)} · ${ep.c1.label}"
                }
            }
        }
    }

    /** 같은 날 매도·매수 신호일 때 예산 배율에 넣을 예상 매도 대금 */
    private fun expectedSaleKrw(acct: Int, s: AccountSettings, qldKrw: Double?): Double {
        val px = qldKrw ?: return 0.0
        return if (!s.sellAll) LongStrategyEngine.account1SellAmount(s.budgetKrw, s.cashKrw, s.qldShares * px)
        else s.qldShares * px   // 2번: 캠페인 매수분 전량 (정확한 수량은 화면에서 표시)
    }

    private fun accountView(
        engine: LongStrategyEngine, ind: QqqIndicators, r: ReplayResult, s: AccountSettings,
        ledger: LedgerSnapshot, qldKrw: Double?, nextDay: LocalDate, tradeTicker: String = "QLD"
    ): AccountView {
        val last = ind.n - 1
        val notes = mutableListOf<String>()
        val orders = mutableListOf<OrderView>()
        val acctFills = ledger.fills.filter { it.account == s.account }
        val nextFills = acctFills.filter { it.date.isAfter(ind.date[last]) }
        fun recorded(kind: FillKind) = nextFills.any { it.kind == kind }
        fun shares(krw: Double) = qldKrw?.let { krw / it }

        if (s.budgetKrw <= 0) notes.add(if (s.account == 1) "총자산 기준금액을 입력해야 금액이 계산됩니다." else "이 계좌의 예산 금액을 입력해야 금액이 계산됩니다. (계좌 메뉴)")

        // 매수 주문
        r.nextOrders.forEach { o ->
            val krw = s.ppToKrw(o.amount)
            val (kind, title) = if (o.sleeve == BuySleeve.CAMPAIGN)
                FillKind.CAMPAIGN to "${campaignName(o.campaign ?: 0)} ${if (o.rounds == 8) "주간" else "일간"} 분할 ${o.round}/${o.rounds}회"
            else FillKind.RECOVERY to "1-3 상승 확인 매수 ${o.round}/${o.rounds}회"
            orders.add(OrderView(kind, title, "다음 거래일 $tradeTicker 시가", krw, shares(krw), recorded(kind)))
        }

        // 매도 2
        val sellRec = r.episodes.lastOrNull { it.sellCycle != null }
        val sc = sellRec?.sellCycle
        if (sellRec != null && sc != null && sc.signaled) {
            val signalDay = ind.date[sc.signalIndex]
            val soldAfter = acctFills.any { it.kind == FillKind.SELL && it.date.isAfter(signalDay) }
            val ep = sellRec.episode
            if (sc.signalIndex == last || (!soldAfter && !signalDay.isBefore(ledger.recordStart))) {
                if (!s.sellAll) {
                    val holding = qldKrw?.let { s.qldShares * it }
                    val amt = if (holding != null) LongStrategyEngine.account1SellAmount(s.budgetKrw, s.cashKrw, holding)
                    else max(s.budgetKrw - s.cashKrw, 0.0)
                    if (amt > 0) orders.add(OrderView(FillKind.SELL, "매도 2 · 부족 예수금만큼 매도",
                        "예산 %,.0f원 − 예수금 %,.0f원 (체결 시점 예수금 기준)".format(s.budgetKrw, s.cashKrw),
                        amt, shares(amt), soldAfter || recorded(FillKind.SELL)))
                    else notes.add("매도 2 신호가 났지만 예수금이 이미 예산 이상이라 매도하지 않습니다.")
                } else {
                    val campFills = acctFills.filter {
                        it.kind != FillKind.SELL && it.date.isAfter(ind.date[ep.start]) && (ep.ended < 0 || !it.date.isAfter(ind.date[ep.ended]))
                    }
                    val sh = campFills.sumOf { it.shares }.takeIf { it > 0 } ?: s.qldShares
                    val amt = qldKrw?.let { sh * it } ?: 0.0
                    orders.add(OrderView(FillKind.SELL, "매도 2 · 이번 캠페인 매수분 전량",
                        "$tradeTicker %,.2f주${if (campFills.isEmpty()) " (체결 기록이 없어 보유 수량 전체)" else ""}".format(sh),
                        amt, sh, soldAfter || recorded(FillKind.SELL)))
                }
                if (sc.signalIndex != last) notes.add("매도 2 신호(${signalDay}) 이후 매도 체결 기록이 없습니다.")
            }
        }
        if (r.sellSignalToday && r.newCampaignToday) {
            notes.add("매도 신호와 새 캠페인 신호가 같은 날입니다. 매도 체결을 먼저 기록한 뒤 매수하세요. 예산 배율은 매도 대금을 포함해 계산했습니다.")
        }

        // 예산 현황
        val ep = r.activeEpisode
        val scale = ep?.scale
        val schedule = ep?.plan?.map { item ->
            ScheduleView(engine.scheduleDate(item), "${campaignName(item.campaign)} ${item.order + 1}/${item.count}회", s.ppToKrw(item.amount))
        }?.filter { !it.date.isBefore(nextDay) }.orEmpty()
        if (ep != null && scale != null && scale < 0.999) notes.add("예수금 부족으로 이번 캠페인 예산을 %.0f%%로 축소했습니다.".format(scale * 100))
        if (ep != null && ind.date[ep.start].isBefore(ledger.recordStart)) {
            notes.add("기록 시작일(${ledger.recordStart}) 이전 매수는 규칙대로 체결된 것으로 계산했습니다.")
        }

        return AccountView(
            settings = s,
            scale = scale,
            campaignStart = ep?.let { ind.date[it.start] },
            spent12Krw = s.ppToKrw(ep?.spent12 ?: 0.0),
            target12Krw = s.ppToKrw(ep?.let { e -> e.w.take(e.depth + 1).sum() } ?: 0.0),
            total12Krw = s.ppToKrw((ep?.w?.sum()) ?: 0.0),
            spent13Krw = s.ppToKrw(ep?.spent13 ?: 0.0),
            total13Krw = s.ppToKrw(ep?.b13 ?: 0.0),
            rounds13 = ep?.rounds13 ?: 0,
            orders = orders,
            schedule = schedule,
            notes = notes
        )
    }
}
