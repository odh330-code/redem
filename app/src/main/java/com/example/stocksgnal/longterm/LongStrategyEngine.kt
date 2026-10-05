package com.example.stocksgnal.longterm

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/*
 * 계곡다이빙: 매수 1 · 매도 2 계산 엔진
 *
 * 기준 구현: buy1_claude/strategy1_sim.py (매수 1), make_golden.py (매도 2).
 * 규칙을 바꾸지 않고 그대로 옮긴 코드이며, Android 의존성이 없어 JVM 테스트로 정답 CSV와 대조한다.
 *
 * 단위: pp = 매수 예산 25를 기준으로 한 값 (25pp = 해당 계좌의 매수 사용 예산 전체).
 *   1번 계좌: 25pp = 총자산 기준금액 × 25%  →  원화 = pp / 100 × 총자산
 *   2번 계좌: 25pp = 직접 입력한 예산 금액  →  원화 = pp / 25 × 예산
 * 신호: QQQ 종가(분할조정). 체결: 신호 다음 거래일 QLD 시가.
 */

data class QqqBar(val date: LocalDate, val close: Double)

enum class BuySleeve(val label: String) {
    CAMPAIGN("1-1/1-2"),
    RECOVERY("1-3")
}

object LongStrategyParams {
    val LEVELS = doubleArrayOf(.05, .10, .15, .20, .25, .30)          // 캠페인 1~6
    val WEIGHTS = doubleArrayOf(5.0, 5.0) + DoubleArray(4) { (25.0 * 2 / 3 - 10) / 4 }
    const val B13 = 25.0 / 3                                          // 1-3 예산
    const val BUDGET = 25.0                                           // 매수 사용 예산 전체 (pp)
    const val PIVOT = 2
    const val RSI_LIMIT = 40.0
    const val WAIT_DAYS = 5
    const val DEFAULT_SELL_DROP_PCT = 3.0
}

/* =========================
   지표
   ========================= */

class QqqIndicators(val bars: List<QqqBar>) {
    val n = bars.size
    val close = DoubleArray(n) { bars[it].close }
    val date: List<LocalDate> = bars.map { it.date }

    /** 직전 252거래일 종가 최고 (당일 제외) */
    val high252 = DoubleArray(n) { Double.NaN }

    /** 60일 이동평균 (당일 포함) */
    val ma60 = DoubleArray(n) { Double.NaN }

    /** RSI(14) Wilder — strategy1_sim.py와 같은 초기화 */
    val rsi14 = DoubleArray(n) { Double.NaN }

    init {
        for (t in 252 until n) {
            var m = Double.NEGATIVE_INFINITY
            for (i in t - 252 until t) m = max(m, close[i])
            high252[t] = m
        }
        for (t in 59 until n) {
            var s = 0.0
            for (i in t - 59..t) s += close[i]
            ma60[t] = s / 60
        }
        if (n > 15) {
            val g = DoubleArray(n); val l = DoubleArray(n)
            for (i in 1 until n) {
                val d = close[i] - close[i - 1]
                g[i] = max(d, 0.0); l[i] = max(-d, 0.0)
            }
            var ag = (1..14).sumOf { g[it] } / 14
            var al = (1..14).sumOf { l[it] } / 14
            for (i in 14 until n) {
                if (i > 14) {
                    ag = (ag * 13 + g[i]) / 14
                    al = (al * 13 + l[i]) / 14
                }
                rsi14[i] = if (al == 0.0) 100.0 else 100 - 100 / (1 + ag / al)
            }
        }
    }

    fun indexOf(day: LocalDate): Int {
        val i = date.binarySearch(day)
        return if (i >= 0) i else -(i + 1)    // day 이상인 첫 거래일
    }
}

/* =========================
   1-3 신호 탐지 (rec_events)
   ========================= */

class RecoveryDetector(private val ind: QqqIndicators, private val s: Int, private val pn: Int = LongStrategyParams.PIVOT) {
    private val c = ind.close
    private val lows = mutableListOf<Pair<Int, Double>>()
    private val highs = mutableListOf<Pair<Int, Double>>()
    private var pend: Pending? = null
    private var run = 0

    private class Pending(val sup: Double, val th: Double) {
        var br = false
        var sent = false
    }

    /** 화면 표시용 상태 */
    val maRun get() = run
    val pendingSupport get() = pend?.sup
    val pendingThreshold get() = pend?.th
    val pendingBroken get() = pend?.br ?: false
    val lastLow get() = lows.lastOrNull()

    /** t일 종가까지 반영. 1-3 신호가 나면 보호 저점을 반환 */
    fun step(t: Int): Double? {
        run = if (c[t] > ind.ma60[t]) run + 1 else 0
        val k = t - pn
        if (k >= s + pn) {
            val leftMin = (k - pn until k).minOf { c[it] }
            val rightMin = (k + 1..k + pn).minOf { c[it] }
            if (c[k] < leftMin && c[k] <= rightMin) {
                val last = lows.lastOrNull()
                if (last != null && c[k] > last.second) {
                    val hs = highs.filter { it.first > last.first && it.first < k }
                    if (hs.isNotEmpty()) pend = Pending(c[k], hs.maxOf { it.second })
                }
                lows.add(k to c[k])
            }
            val leftMax = (k - pn until k).maxOf { c[it] }
            val rightMax = (k + 1..k + pn).maxOf { c[it] }
            if (c[k] > leftMax && c[k] >= rightMax) highs.add(k to c[k])
        }
        pend?.let { if (c[t] < it.sup) pend = null }
        val p = pend ?: return null
        if (c[t] > p.th && c[t - 1] <= p.th) p.br = true
        if (p.br && run >= 3 && !p.sent) {
            p.sent = true
            return p.sup
        }
        return null
    }
}

/* =========================
   매수 1 — 한 구간(에피소드)
   ========================= */

enum class Campaign1State(val label: String) {
    NONE("-"), WAITING("RSI 대기"), BUYING("분할 매수"), STOPPED("반등 정지"), DONE("종료")
}

/** 분할 일정의 한 회차 */
data class PlanItem(
    val signalIndex: Int,
    val order: Int,            // 0부터
    val count: Int,
    val weekly: Boolean,
    val campaign: Int,         // 1~6
    val amount: Double         // pp
)

data class BuyOrder(
    val sleeve: BuySleeve,
    val amount: Double,        // pp
    val campaign: Int?,        // 1-1/1-2일 때 캠페인 번호
    val round: Int,            // 회차 (1부터)
    val rounds: Int
)

class Buy1Episode(
    private val ind: QqqIndicators,
    val start: Int,
    val H: Double,
    val scale: Double = 1.0,
    private val truncateAtSeriesEnd: Boolean = false
) {
    private val c = ind.close
    val w = DoubleArray(6) { LongStrategyParams.WEIGHTS[it] * scale }
    val b13 = LongStrategyParams.B13 * scale

    var spent12 = 0.0; private set
    var spent13 = 0.0; private set
    var depth = -1; private set
    var c1 = Campaign1State.NONE; private set
    var waitStart = -1; private set
    var after13 = false; private set
    var support: Double? = null; private set
    var active13 = false; private set
    var maxDrawdown = 0.0; private set
    var rounds13 = 0; private set
    val plan = mutableListOf<PlanItem>();
    val detector = RecoveryDetector(ind, start)
    var ended = -1; private set

    /** 주간 일정 k회차의 목표 날짜 (첫 회 = 신호 다음 거래일) */
    private fun weeklyTarget(item: PlanItem): LocalDate? {
        val first = item.signalIndex + 1
        if (first >= ind.n) return null
        return ind.date[first].plusWeeks(item.order.toLong())
    }

    /** t일에 체결해야 하는(또는 이미 지난) 일정인지 */
    private fun dueBy(item: PlanItem, t: Int): Boolean {
        if (t <= item.signalIndex) return false
        return if (!item.weekly) item.signalIndex + 1 + item.order <= t
        else {
            val target = weeklyTarget(item) ?: return false
            !ind.date[t].isBefore(target)
        }
    }

    private fun sched(t: Int, amt: Double, weekly: Boolean, campaign: Int): List<PlanItem> {
        if (amt <= 1e-12) return emptyList()
        var count = if (weekly) 8 else 5
        if (truncateAtSeriesEnd) {
            // 검증 전용: 데이터 끝에서 일정이 잘리는 원본 동작 재현
            count = if (!weekly) (t + 1..t + 5).count { it < ind.n }
            else if (t + 1 >= ind.n) 0
            else (0 until 8).count { k -> ind.indexOf(ind.date[t + 1].plusWeeks(k.toLong())) < ind.n }
            if (count == 0) return emptyList()
            return (0 until count).map { PlanItem(t, it, count, weekly, campaign, amt / count) }
        }
        return (0 until count).map { PlanItem(t, it, count, weekly, campaign, amt / count) }
    }

    /** t일 시가에 낼 주문 (t-1일 종가까지 반영된 상태 기준) */
    fun ordersFor(t: Int): List<BuyOrder> {
        val out = mutableListOf<BuyOrder>()
        plan.filter { dueBy(it, t) }.forEach {
            out.add(BuyOrder(BuySleeve.CAMPAIGN, it.amount, it.campaign, it.order + 1, it.count))
        }
        if (active13 && spent13 < b13 - 1e-9) {
            out.add(BuyOrder(BuySleeve.RECOVERY, min(b13 / 5, b13 - spent13), null, rounds13 + 1, 5))
        }
        return out
    }

    /** t일 실제 체결 반영 */
    fun applyFills(t: Int, campaignAmount: Double, recoveryAmount: Double) {
        spent12 += campaignAmount
        if (recoveryAmount > 0) { spent13 += recoveryAmount; rounds13 += 1 }
        plan.removeAll { dueBy(it, t) }
    }

    private fun startOrWait(t: Int) {
        if (ind.rsi14[t] <= LongStrategyParams.RSI_LIMIT) {
            replacePlan(sched(t, w[0] - spent12, false, 1)); c1 = Campaign1State.BUYING
        } else {
            c1 = Campaign1State.WAITING; waitStart = t
        }
    }

    private fun replacePlan(items: List<PlanItem>) { plan.clear(); plan.addAll(items) }

    /** t일 종가 처리. 구간이 끝나면(QQQ 종가 ≥ H) true */
    fun onClose(t: Int): Boolean {
        if (c[t] >= H && t > start) { ended = t; plan.clear(); active13 = false; return true }
        val levels = LongStrategyParams.LEVELS
        val dd = 1 - c[t] / H
        maxDrawdown = max(maxDrawdown, dd)
        val nd = levels.count { it <= dd + 1e-12 } - 1
        if (nd > depth) {
            depth = nd
            if (depth == 0) startOrWait(t)
            else {
                replacePlan(sched(t, w.take(depth + 1).sum() - spent12, depth >= 2, depth + 1))
                c1 = Campaign1State.DONE
            }
        } else {
            if (depth <= 1 && nd < depth && plan.isNotEmpty()) {
                plan.clear(); if (depth == 0) c1 = Campaign1State.STOPPED
            }
            if (depth == 0) {
                if (c1 == Campaign1State.WAITING && nd < 0) c1 = Campaign1State.STOPPED
                else if (c1 == Campaign1State.WAITING &&
                    (ind.rsi14[t] <= LongStrategyParams.RSI_LIMIT || t - waitStart >= LongStrategyParams.WAIT_DAYS)) {
                    replacePlan(sched(t, w[0] - spent12, false, 1)); c1 = Campaign1State.BUYING
                } else if (c1 == Campaign1State.STOPPED && nd == 0 && !after13 && spent12 < w[0] - 1e-9) {
                    startOrWait(t)
                }
            }
        }
        val ev = detector.step(t)
        if (ev != null && spent13 < b13 - 1e-9) {
            support = ev; plan.clear(); after13 = true
            if (c1 != Campaign1State.NONE) c1 = Campaign1State.DONE
        }
        active13 = support != null && spent13 < b13 - 1e-9 && c[t] > ind.ma60[t] && c[t] > support!!
        if (support != null && !active13) support = null
        return false
    }

    /** 원본 simulate()는 구간 시작일에 종가 처리를 하고, 종료일은 체결만 한 뒤 끝낸다 */
    fun currentCampaign(): Int = depth + 1
}

/* =========================
   전체 재생 (대기 → 매수 1 → 매도 대기)
   ========================= */

data class SellCycle(
    val recoveredIndex: Int,
    var peakM: Double,
    var signalIndex: Int = -1
) {
    val signaled get() = signalIndex >= 0
}

data class EpisodeRecord(
    val episode: Buy1Episode,
    val sellCycle: SellCycle?,
    val sameDayAsPrevSell: Boolean
)

data class DayFill(val campaign: Double, val recovery: Double)

enum class EngineMode(val label: String) {
    WAIT("대기"), BUY("매수 1 진행"), SELL_WAIT("매도 대기")
}

/** 재생 결과 + 다음 거래일 할 일 */
class ReplayResult(
    val ind: QqqIndicators,
    val episodes: List<EpisodeRecord>,
    val mode: EngineMode,
    val nextOrders: List<BuyOrder>,
    val sellSignalToday: Boolean,
    val newCampaignToday: Boolean,
    val sellDropPct: Double
) {
    val last get() = ind.n - 1
    val current: EpisodeRecord? get() = episodes.lastOrNull()
    val activeEpisode: Buy1Episode? get() = episodes.lastOrNull()?.episode?.takeIf { it.ended < 0 }
    val activeSell: SellCycle? get() = episodes.lastOrNull()?.sellCycle?.takeIf { !it.signaled || it.signalIndex == last }
}

class LongStrategyEngine(
    val ind: QqqIndicators,
    private val sellDropPct: Double = LongStrategyParams.DEFAULT_SELL_DROP_PCT,
    private val truncateAtSeriesEnd: Boolean = false
) {
    /**
     * @param startDate 재생 시작일 (대기 상태로 시작). 252거래일 이전 데이터가 있어야 한다.
     * @param scaleFor 새 캠페인 시작 시 예산 배율 f (0~1). sameDaySell = 같은 날 매도 신호가 있었는지.
     * @param fillsFor t일 체결액. 기본은 주문대로 체결(검증·과거 구간용).
     */
    fun replay(
        startDate: LocalDate,
        scaleFor: (startIndex: Int, sameDaySell: Boolean) -> Double = { _, _ -> 1.0 },
        fillsFor: (t: Int, orders: List<BuyOrder>) -> DayFill = { _, o -> autoFill(o) }
    ): ReplayResult {
        val c = ind.close
        val records = mutableListOf<EpisodeRecord>()
        var ep: Buy1Episode? = null
        var sell: SellCycle? = null
        var sellToday = false
        var newToday = false
        val begin = max(ind.indexOf(startDate), 252)

        for (t in begin until ind.n) {
            sellToday = false; newToday = false
            // 1) 시가 체결 (전일 종가 기준 주문)
            ep?.let { e ->
                val f = fillsFor(t, e.ordersFor(t))
                e.applyFills(t, f.campaign, f.recovery)
            }
            // 2) 종가 처리
            if (ep != null) {
                if (ep.onClose(t)) {
                    sell = SellCycle(t, c[t])
                    records[records.lastIndex] = records.last().copy(sellCycle = sell)
                    ep = null
                }
                continue
            }
            sell?.let { s ->
                if (!s.signaled && t > s.recoveredIndex) {
                    s.peakM = max(s.peakM, c[t])
                    if (c[t] <= s.peakM * (1 - sellDropPct / 100)) { s.signalIndex = t; sellToday = true }
                }
            }
            if (c[t] / ind.high252[t] - 1 <= -0.05) {
                val sameDay = sellToday
                val f = scaleFor(t, sameDay).coerceIn(0.0, 1.0)
                val e = Buy1Episode(ind, t, ind.high252[t], f, truncateAtSeriesEnd)
                e.onClose(t)
                ep = e; newToday = true
                records.add(EpisodeRecord(e, null, sameDay))
            }
        }
        val nextOrders = ep?.let { e -> nextOrders(e) } ?: emptyList()
        val mode = when {
            ep != null -> EngineMode.BUY
            sell != null && (!sell!!.signaled || sellToday) -> EngineMode.SELL_WAIT
            else -> EngineMode.WAIT
        }
        return ReplayResult(ind, records, mode, nextOrders, sellToday, newToday, sellDropPct)
    }

    /** 마지막 날 종가 이후 다음 거래일 주문. 다음 거래일은 미국 휴장일 달력으로 추정 */
    private fun nextOrders(e: Buy1Episode): List<BuyOrder> {
        val last = ind.n - 1
        val nextDay = nextWeekday(ind.date[last])
        val out = mutableListOf<BuyOrder>()
        e.plan.forEach { item ->
            val due = if (!item.weekly) item.signalIndex + 1 + item.order == last + 1
            else if (item.signalIndex == last) item.order == 0
            else {
                val target = ind.date[item.signalIndex + 1].plusWeeks(item.order.toLong())
                !nextDay.isBefore(target)
            }
            if (due) out.add(BuyOrder(BuySleeve.CAMPAIGN, item.amount, item.campaign, item.order + 1, item.count))
        }
        if (e.active13 && e.spent13 < e.b13 - 1e-9) {
            out.add(BuyOrder(BuySleeve.RECOVERY, min(e.b13 / 5, e.b13 - e.spent13), null, e.rounds13 + 1, 5))
        }
        return out
    }

    /** 남은 일정의 예정일 (표시용) */
    fun scheduleDate(item: PlanItem): LocalDate {
        val last = ind.n - 1
        return if (!item.weekly) {
            val idx = item.signalIndex + 1 + item.order
            if (idx <= last) ind.date[idx] else addWeekdays(ind.date[last], idx - last)
        } else {
            val first = if (item.signalIndex + 1 <= last) ind.date[item.signalIndex + 1] else nextWeekday(ind.date[last])
            UsMarketCalendar.onOrAfter(first.plusWeeks(item.order.toLong()))
        }
    }

    companion object {
        fun autoFill(orders: List<BuyOrder>) = DayFill(
            orders.filter { it.sleeve == BuySleeve.CAMPAIGN }.sumOf { it.amount },
            orders.filter { it.sleeve == BuySleeve.RECOVERY }.sumOf { it.amount }
        )

        /** 다음 거래일 (미국 휴장일 반영) */
        fun nextWeekday(d: LocalDate): LocalDate = UsMarketCalendar.nextTradingDay(d)

        fun addWeekdays(d: LocalDate, n: Int): LocalDate = UsMarketCalendar.addTradingDays(d, n)

        /** 1번 계좌 매도액 (pp 또는 원): 부족분만큼, 보유 평가액 한도 */
        fun account1SellAmount(budget: Double, cash: Double, holdingValue: Double): Double =
            min(max(budget - cash, 0.0), max(holdingValue, 0.0))

        /** 새 캠페인 예산 배율 f = min(예수금, 예산) / 예산 */
        fun budgetScale(cash: Double, budget: Double): Double =
            if (budget <= 0) 0.0 else (min(cash, budget) / budget).coerceIn(0.0, 1.0)
    }
}
