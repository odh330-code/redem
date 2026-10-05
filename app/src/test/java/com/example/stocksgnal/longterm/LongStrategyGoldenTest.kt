package com.example.stocksgnal.longterm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/**
 * 명세 8장 검증: 2007-01-01부터 엔진을 돌려 정답 CSV와 비교 (허용 오차 1e-6).
 * 정답: buy1_claude/results/trades.csv, episodes.csv, golden_sell2.csv (test/resources/longterm)
 */
class LongStrategyGoldenTest {

    private fun readCsv(name: String): List<Map<String, String>> {
        val text = javaClass.classLoader!!.getResourceAsStream("longterm/$name")!!.bufferedReader().readText()
        val lines = text.lines().filter { it.isNotBlank() }
        val head = lines[0].split(",")
        return lines.drop(1).map { l -> head.zip(l.split(",")).toMap() }
    }

    private fun r(x: Double, d: Int): Double { val p = Math.pow(10.0, d.toDouble()); return round(x * p) / p }
    private fun near(a: Double, b: Double, tol: Double = 1e-6) = abs(a - b) <= tol
    private val failures = mutableListOf<String>()
    private fun check(label: String, ok: Boolean, detail: () -> String) { if (!ok) failures.add("$label: ${detail()}") }

    @Test
    fun matchesGoldenData() {
        // --- 데이터: strategy1_sim.py와 같은 방식으로 결합 ---
        val q0 = readCsv("qqq_2000_base.csv")
        val q1 = readCsv("qqq_yahoo.csv")
        val q1Start = LocalDate.parse(q1.first()["date"])
        val bars = (q0.filter { LocalDate.parse(it["date"]).isBefore(q1Start) } + q1)
            .map { QqqBar(LocalDate.parse(it["date"]), it["close"]!!.toDouble()) }
        val ind = QqqIndicators(bars)
        val z = readCsv("qld_yahoo.csv").associateBy { LocalDate.parse(it["date"]) }
        val op = DoubleArray(ind.n) { i -> z[ind.date[i]]?.let { it["open"]!!.toDouble() * it["adj_close"]!!.toDouble() / it["close"]!!.toDouble() } ?: Double.NaN }
        val cl = DoubleArray(ind.n) { i -> z[ind.date[i]]?.get("adj_close")?.toDouble() ?: Double.NaN }

        // --- 재생 (주문대로 체결) ---
        data class Tr(val date: LocalDate, val sleeve: String, val amt: Double, val t: Int)
        val trades = mutableListOf<Tr>()
        val engine = LongStrategyEngine(ind, truncateAtSeriesEnd = true)
        val res = engine.replay(LocalDate.of(2007, 1, 1), fillsFor = { t, orders ->
            orders.forEach { trades.add(Tr(ind.date[t], it.sleeve.label, it.amount, t)) }
            LongStrategyEngine.autoFill(orders)
        })

        // --- 1) trades.csv ---
        val gt = readCsv("trades.csv")
        // 같은 날 1-1/1-2 여러 건은 원본에서 행이 따로 있음 → 순서대로 비교
        val mine = trades.sortedWith(compareBy({ it.date }, { if (it.sleeve == "1-3") 1 else 0 }))
        val theirs = gt.sortedWith(compareBy({ it["date"] }, { if (it["sleeve"] == "1-3") 1 else 0 }))
        check("trades count", mine.size == theirs.size) { "${mine.size} vs ${theirs.size}" }
        mine.zip(theirs).forEachIndexed { i, (a, b) ->
            check("trade $i", a.date.toString() == b["date"] && a.sleeve == b["sleeve"] && near(a.amt, b["amount_pp"]!!.toDouble())) {
                "${a.date} ${a.sleeve} ${a.amt} vs ${b["date"]} ${b["sleeve"]} ${b["amount_pp"]}"
            }
        }

        // --- 2) episodes.csv ---
        val ge = readCsv("episodes.csv")
        check("episode count", res.episodes.size == ge.size) { "${res.episodes.size} vs ${ge.size}" }
        res.episodes.zip(ge).forEachIndexed { i, (rec, g) ->
            val e = rec.episode
            val end = if (e.ended >= 0) e.ended else ind.n - 1
            val trs = trades.filter { it.t in (e.start + 1)..end }
            var sh = 0.0; var sp = 0.0; var sh13 = 0.0; var s13 = 0.0; var minp = 0.0
            for (t in e.start..end) {
                trs.filter { it.t == t }.forEach { if (it.sleeve == "1-3") { s13 += it.amt; sh13 += it.amt / op[t] } else { sp += it.amt; sh += it.amt / op[t] } }
                minp = min(minp, sh * cl[t] - sp + sh13 * cl[t] - s13)
            }
            val vals = mapOf(
                "start" to (ind.date[e.start].toString() == g["start"]),
                "end" to (ind.date[end].toString() == g["end"]),
                "complete" to ((e.ended >= 0).toString().equals(g["complete"], true)),
                "qqq_dd_pct" to near(-e.maxDrawdown * 100, g["qqq_dd_pct"]!!.toDouble()),
                "spent12_pp" to near(e.spent12, g["spent12_pp"]!!.toDouble()),
                "spent13_pp" to near(e.spent13, g["spent13_pp"]!!.toDouble()),
                "profit12_pp" to near(sh * cl[end] - sp, g["profit12_pp"]!!.toDouble()),
                "profit13_pp" to near(sh13 * cl[end] - s13, g["profit13_pp"]!!.toDouble()),
                "min_pnl_pp" to near(minp, g["min_pnl_pp"]!!.toDouble())
            )
            vals.filter { !it.value }.forEach { (k, _) -> check("episode $i $k", false) { "${g["start"]} mine=${ind.date[e.start]}..${ind.date[end]} sp=${e.spent12} s13=${e.spent13} dd=${-e.maxDrawdown*100}" } }
        }

        // --- 3) golden_sell2.csv: make_golden.py 장부를 엔진 신호로 재현 ---
        val gg = readCsv("golden_sell2.csv")
        var cash = 25.0; var sh = 0.0; var cash2 = 25.0
        val eps = res.episodes
        check("golden count", eps.size == gg.size) { "${eps.size} vs ${gg.size}" }
        eps.forEachIndexed { k, rec ->
            val g = gg[k]; val e = rec.episode
            val f = LongStrategyEngine.budgetScale(cash, 25.0)
            val buys = trades.filter { it.t > e.start && (e.ended < 0 || it.t <= e.ended) }
            buys.forEach { cash -= it.amt * f; sh += it.amt * f / op[it.t] }
            val f2 = LongStrategyEngine.budgetScale(cash2, 25.0); var camp2 = 0.0
            buys.forEach { cash2 -= it.amt * f2; camp2 += it.amt * f2 / op[it.t] }
            check("g$k start", ind.date[e.start].toString() == g["campaign_start"]) { "${ind.date[e.start]} vs ${g["campaign_start"]}" }
            check("g$k H", near(r(e.H, 4), g["H"]!!.toDouble())) { "${e.H} vs ${g["H"]}" }
            check("g$k frac", near(r(f, 6), g["budget_frac_acct1"]!!.toDouble())) { "$f" }
            check("g$k spent", near(r(buys.sumOf { it.amt * f }, 6), g["spent_acct1"]!!.toDouble())) { "${buys.sumOf { it.amt }} vs ${g["spent_acct1"]}" }
            val sc = rec.sellCycle
            if (e.ended >= 0) {
                check("g$k recovered", ind.date[e.ended].toString() == g["H_recovered"]) { "${ind.date[e.ended]} vs ${g["H_recovered"]}" }
                check("g$k sellcycle", sc != null && sc.signaled) { "no signal" }
                if (sc != null && sc.signaled) {
                    val trig = sc.signalIndex; val sale = trig + 1
                    val next = eps[k + 1]
                    val need = maxOf(25 - cash, 0.0)
                    val v = LongStrategyEngine.account1SellAmount(25.0, cash, sh * op[sale])
                    check("g$k need", near(v, min(need, sh * op[sale]), 1e-12)) { "" }
                    sh -= v / op[sale]; cash += v
                    val v2 = camp2 * op[sale]; cash2 += v2
                    check("g$k M", near(r(sc.peakM, 4), g["M_at_signal"]!!.toDouble())) { "${sc.peakM} vs ${g["M_at_signal"]}" }
                    check("g$k sig", ind.date[trig].toString() == g["sell_signal_date"]) { "${ind.date[trig]} vs ${g["sell_signal_date"]}" }
                    check("g$k exec", ind.date[sale].toString() == g["sell_exec_date"]) { "" }
                    check("g$k open", near(r(op[sale], 6), g["qld_open_exec"]!!.toDouble())) { "" }
                    check("g$k nextsig", ind.date[next.episode.start].toString() == g["next_campaign_signal"]) { "" }
                    check("g$k sameday", next.sameDayAsPrevSell.toString().equals(g["same_day_as_next_buy_signal"], true)) { "${next.sameDayAsPrevSell}" }
                    check("g$k nextH", near(r(ind.high252[next.episode.start], 4), g["next_H_252"]!!.toDouble())) { "" }
                    check("g$k M=H", (abs(sc.peakM - ind.high252[next.episode.start]) <= 1e-8 + 1e-5 * abs(ind.high252[next.episode.start])).toString().equals(g["M_equals_next_H252"], true)) { "" }
                    check("g$k sold1", near(r(v, 6), g["sold_acct1"]!!.toDouble())) { "$v vs ${g["sold_acct1"]}" }
                    check("g$k cash1", near(r(cash, 6), g["cash_after_acct1"]!!.toDouble())) { "" }
                    check("g$k left1", near(r(sh * op[sale], 6), g["qld_value_left_acct1"]!!.toDouble())) { "" }
                    check("g$k sold2", near(r(v2, 6), g["sold_acct2"]!!.toDouble())) { "" }
                    check("g$k cash2", near(r(cash2, 6), g["cash_after_acct2"]!!.toDouble())) { "" }
                }
            } else check("g$k incomplete", g["H_recovered"].isNullOrBlank()) { "golden says recovered ${g["H_recovered"]}" }
        }

        assertTrue("불일치 ${failures.size}건\n" + failures.take(20).joinToString("\n"), failures.isEmpty())
    }
}
