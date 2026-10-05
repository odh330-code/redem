package com.example.stocksgnal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.ui.theme.DownBlue
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.UpRed
import com.example.stocksgnal.ui.theme.changeColor
import com.example.stocksgnal.ui.theme.glassCardColors
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/*
 * 하단 [손익] 메뉴 — "확정된 돈"만 정산한다 (평가손익은 계좌 탭).
 *   실현 시점 = 매도 시점(그날 환율로 원화 확정).  매입원가 = 이동평균(증권사 기간손익 기준).
 *   하위 탭: 요약 · 매매 · 배당 · 세금 · 전략
 *
 * ⚠ 2026-10-05 화면 확인용 시안: 금액은 전부 가상 데이터(ProfitSample)다.
 *   실제 연결 시 매매 = NamuPnlApi(기간손익·상세), 배당 = NamuDividendApi 로 바꾸고 ProfitSample 은 지운다.
 */

/* ---------- 가상 데이터 (확인 후 삭제) ---------- */

private data class SaleRecord(
    val date: LocalDate,
    val ticker: String,
    val qty: Double,
    val avgBuyUsd: Double,     // 이동평균 매입단가
    val buyFx: Double,         // 매입 평균 환율
    val sellUsd: Double,
    val sellFx: Double,        // 매도일 환율
    val strategy: String? = null
) {
    val sellKrw get() = qty * sellUsd * sellFx
    val costKrw get() = qty * avgBuyUsd * buyFx
    val feeKrw get() = sellKrw * 0.0025                     // 수수료·제비용 (가상 0.25%)
    val stockPartKrw get() = qty * (sellUsd - avgBuyUsd) * sellFx   // 주가 몫
    val fxPartKrw get() = qty * avgBuyUsd * (sellFx - buyFx)        // 환율 몫
    val realizedKrw get() = stockPartKrw + fxPartKrw - feeKrw
    val realizedPct get() = if (costKrw <= 0) 0.0 else realizedKrw / costKrw * 100
    val realizedUsd get() = qty * (sellUsd - avgBuyUsd) - feeKrw / sellFx
}

private data class DividendRecord(
    val date: LocalDate,
    val ticker: String,
    val grossUsd: Double,
    val fx: Double
) {
    val taxUsd get() = grossUsd * 0.15
    val netUsd get() = grossUsd - taxUsd
    val netKrw get() = netUsd * fx
    val grossKrw get() = grossUsd * fx
    val taxKrw get() = taxUsd * fx
}

private data class LossHolding(val ticker: String, val unrealizedKrw: Double)

private object ProfitSample {
    private fun d(s: String) = LocalDate.parse(s)
    val sales = listOf(
        SaleRecord(d("2025-04-08"), "QLD", 50.0, 88.0, 1420.0, 64.0, 1475.0, "나스닥 계곡다이빙"),
        SaleRecord(d("2025-07-22"), "AAPL", 15.0, 190.0, 1380.0, 212.0, 1385.0),
        SaleRecord(d("2025-11-12"), "QLD", 60.0, 70.0, 1470.0, 104.0, 1455.0, "나스닥 계곡다이빙"),
        SaleRecord(d("2026-02-11"), "QLD", 40.0, 92.4, 1435.0, 118.6, 1442.0, "나스닥 계곡다이빙"),
        SaleRecord(d("2026-03-27"), "TSLA", 10.0, 312.0, 1452.0, 268.5, 1468.0),
        SaleRecord(d("2026-05-14"), "USD", 25.0, 61.2, 1440.0, 78.9, 1390.0, "반도체 계곡다이빙"),
        SaleRecord(d("2026-06-30"), "NVDA", 8.0, 140.0, 1380.0, 176.4, 1365.0),
        SaleRecord(d("2026-08-19"), "QLD", 30.0, 101.7, 1395.0, 96.2, 1388.0, "나스닥 계곡다이빙"),
        SaleRecord(d("2026-09-24"), "SCHD", 50.0, 27.1, 1350.0, 28.0, 1392.0)
    )
    val dividends = listOf(
        DividendRecord(d("2025-03-31"), "SCHD", 26.4, 1468.0),
        DividendRecord(d("2025-06-30"), "SCHD", 27.1, 1360.0),
        DividendRecord(d("2025-09-29"), "SCHD", 27.9, 1395.0),
        DividendRecord(d("2025-12-29"), "SCHD", 31.2, 1445.0),
        DividendRecord(d("2026-03-27"), "QLD", 3.1, 1468.0),
        DividendRecord(d("2026-03-30"), "SCHD", 28.6, 1470.0),
        DividendRecord(d("2026-06-29"), "SCHD", 29.3, 1368.0),
        DividendRecord(d("2026-09-28"), "SCHD", 18.4, 1392.0),
        DividendRecord(d("2026-09-28"), "NVDA", 0.4, 1392.0)
    )
    val lossHoldings = listOf(
        LossHolding("SOXL", -2_400_000.0),
        LossHolding("TSLA", -1_850_000.0),
        LossHolding("INTC", -620_000.0)
    )
    const val UNREALIZED_KRW = 3_870_000.0   // 계좌 탭 평가손익 (참고 표시용)
}

/* ---------- 기간 ---------- */

private enum class ProfitPeriod(val label: String) { THIS_YEAR("올해"), LAST_YEAR("작년"), ALL("전체") }

private fun ProfitPeriod.contains(date: LocalDate, today: LocalDate): Boolean = when (this) {
    ProfitPeriod.THIS_YEAR -> date.year == today.year
    ProfitPeriod.LAST_YEAR -> date.year == today.year - 1
    ProfitPeriod.ALL -> true
}

/* ---------- 표시 형식 ---------- */

private fun won(v: Double) = "₩%,.0f".format(v)
private fun signedWon(v: Double) = (if (v > 0) "+" else if (v < 0) "-" else "") + "₩%,.0f".format(abs(v))
private fun signedPct(v: Double) = "%+.2f%%".format(v)
private fun manWon(v: Double): String {
    val sign = if (v < 0) "-" else ""
    val a = abs(v)
    return when {
        a >= 100_000_000 -> sign + "%.2f억".format(a / 100_000_000)
        a >= 10_000 -> sign + "%,.0f만".format(a / 10_000)
        else -> sign + "%,.0f".format(a)
    }
}

/* ---------- 화면 ---------- */

@Composable
fun ProfitScreen(onStockClick: (String) -> Unit = {}) {
    val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    var period by remember { mutableStateOf(ProfitPeriod.THIS_YEAR) }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("요약", "매매", "배당", "세금", "전략")

    val sales = ProfitSample.sales.filter { period.contains(it.date, today) }.sortedByDescending { it.date }
    val dividends = ProfitSample.dividends.filter { period.contains(it.date, today) }.sortedByDescending { it.date }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Row(modifier = Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("손익", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "가상 데이터",
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(CardTone.Warning.copy(alpha = 0.22f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = CardTone.Warning, fontWeight = FontWeight.Bold
                )
            }
            Text("매도로 확정된 손익과 받은 배당만 정산합니다. 평가손익은 계좌 탭에서 봅니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfitPeriod.entries.forEach { p ->
                    FilterChip(selected = period == p, onClick = { period = p },
                        label = { Text(if (p == ProfitPeriod.ALL) p.label else "${p.label}(${if (p == ProfitPeriod.THIS_YEAR) today.year else today.year - 1})") })
                }
            }
        }
        item {
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
        }
        when (tab) {
            0 -> {
                item { ProfitSummaryCard(sales, dividends, period) }
                item { MonthlyProfitCard(sales, dividends, period, today) }
                item { UnrealizedNoteCard() }
            }
            1 -> {
                if (sales.isEmpty()) item { EmptyCard("이 기간에 매도한 기록이 없습니다.") }
                else {
                    item { TickerTotalsCard(sales, onStockClick) }
                    item { ProfitSectionTitle("매도 기록", "${sales.size}건 · 누르면 상세") }
                    sales.forEach { s -> item(key = "sale:${s.date}:${s.ticker}:${s.qty}") { SaleRow(s) } }
                }
            }
            2 -> {
                if (dividends.isEmpty()) item { EmptyCard("이 기간에 받은 배당이 없습니다.") }
                else {
                    item { DividendSummaryCard(dividends) }
                    item { ProfitSectionTitle("받은 내역", "${dividends.size}건") }
                    dividends.forEach { dv -> item(key = "div:${dv.date}:${dv.ticker}") { DividendRecordRow(dv) } }
                }
            }
            3 -> {
                // 세금은 연 단위: 전체를 골라도 올해 기준으로 계산
                val taxYear = if (period == ProfitPeriod.LAST_YEAR) today.year - 1 else today.year
                item { TaxCard(ProfitSample.sales.filter { it.date.year == taxYear }, taxYear, taxYear == today.year) }
            }
            else -> {
                item { StrategyProfitCard(sales) }
            }
        }
    }
}

/* ---------- 공통 카드 ---------- */

@Composable
private fun ProfitCard(tone: Color = CardTone.Account, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = glassCardColors(),
        border = toneBorder(tone)
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun ProfitSectionTitle(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ProfitRow(label: String, value: String, color: Color = Color.Unspecified, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium, color = color)
    }
}

@Composable
private fun EmptyCard(text: String) {
    ProfitCard(CardTone.Data) { Text(text, style = MaterialTheme.typography.bodyMedium) }
}

/* ---------- 요약 ---------- */

@Composable
private fun ProfitSummaryCard(sales: List<SaleRecord>, dividends: List<DividendRecord>, period: ProfitPeriod) {
    val realized = sales.sumOf { it.realizedKrw }
    val divNet = dividends.sumOf { it.netKrw }
    val total = realized + divNet
    val fees = sales.sumOf { it.feeKrw }
    val gains = sales.filter { it.realizedKrw > 0 }
    ProfitCard {
        Text("${period.label} 확정 수익", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(signedWon(total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = changeColor(total))
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        ProfitRow("매도 실현손익", signedWon(realized), changeColor(realized))
        ProfitRow("받은 배당 (세후)", signedWon(divNet), changeColor(divNet))
        ProfitRow("이미 뺀 수수료·제비용", won(fees))
        ProfitRow("매도 ${sales.size}건 중 이익", "${gains.size}건 / 손실 ${sales.size - gains.size}건")
    }
}

/** 월별 확정 수익 막대: 0 기준선 위(빨강)·아래(파랑) */
@Composable
private fun MonthlyProfitCard(sales: List<SaleRecord>, dividends: List<DividendRecord>, period: ProfitPeriod, today: LocalDate) {
    val year = if (period == ProfitPeriod.LAST_YEAR) today.year - 1 else today.year
    val monthly = (1..12).map { m ->
        sales.filter { it.date.year == year && it.date.monthValue == m }.sumOf { it.realizedKrw } +
            dividends.filter { it.date.year == year && it.date.monthValue == m }.sumOf { it.netKrw }
    }
    val max = monthly.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    var selected by remember(year) { mutableIntStateOf(-1) }
    ProfitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("월별 확정 수익", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${year}년 · 매도+배당", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(120.dp)) {
            monthly.forEachIndexed { index, v ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { selected = index }.padding(horizontal = 2.dp)
                ) {
                    val h = (56 * abs(v) / max).coerceAtLeast(if (v != 0.0) 3.0 else 0.0)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (v > 0) Box(Modifier.fillMaxWidth().height(h.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (selected == index) UpRed else UpRed.copy(alpha = 0.75f)))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        if (v < 0) Box(Modifier.fillMaxWidth().height(h.dp).clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                            .background(if (selected == index) DownBlue else DownBlue.copy(alpha = 0.75f)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            (1..12).forEach { m ->
                Text("$m", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        if (selected in 0..11) {
            val v = monthly[selected]
            Spacer(Modifier.height(6.dp))
            Text("${selected + 1}월 확정 수익 ${signedWon(v)}", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold, color = changeColor(v))
        }
    }
}

@Composable
private fun UnrealizedNoteCard() {
    ProfitCard(CardTone.Data) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("참고 · 아직 팔지 않은 평가손익", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(signedWon(ProfitSample.UNREALIZED_KRW), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold, color = changeColor(ProfitSample.UNREALIZED_KRW))
        }
        Text("확정 수익에는 들어가지 않습니다 (계좌 탭)", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------- 매매 ---------- */

@Composable
private fun TickerTotalsCard(sales: List<SaleRecord>, onStockClick: (String) -> Unit) {
    val byTicker = sales.groupBy { it.ticker }
        .map { (t, list) -> Triple(t, list.size, list.sumOf { it.realizedKrw }) }
        .sortedByDescending { it.third }
    val max = byTicker.maxOfOrNull { abs(it.third) }?.takeIf { it > 0 } ?: 1.0
    ProfitCard {
        Text("종목별 실현손익", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        byTicker.forEach { (ticker, count, sum) ->
            Row(Modifier.fillMaxWidth().clickable { onStockClick(ticker) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(ticker, modifier = Modifier.width(56.dp), fontWeight = FontWeight.Bold)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth((abs(sum) / max).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                        .background(if (sum >= 0) UpRed else DownBlue))
                }
                Text(manWon(sum), modifier = Modifier.width(76.dp), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = changeColor(sum))
                Text("${count}건", modifier = Modifier.width(32.dp), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SaleRow(s: SaleRecord) {
    var open by remember { mutableStateOf(false) }
    ProfitCard(if (s.realizedKrw >= 0) CardTone.Sell else CardTone.Action) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.ticker} · ${"%,.0f".format(s.qty)}주 매도", fontWeight = FontWeight.Bold)
                Text("${s.date} · 매도 ${won(s.sellKrw)}" + (s.strategy?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(signedWon(s.realizedKrw), fontWeight = FontWeight.Bold, color = changeColor(s.realizedKrw))
                Text(signedPct(s.realizedPct), style = MaterialTheme.typography.labelSmall, color = changeColor(s.realizedPct))
            }
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            ProfitRow("매입 (이동평균)", "$%,.2f × ₩%,.1f".format(s.avgBuyUsd, s.buyFx))
            ProfitRow("매도", "$%,.2f × ₩%,.1f".format(s.sellUsd, s.sellFx))
            ProfitRow("매입원가", won(s.costKrw))
            ProfitRow("주가 몫", signedWon(s.stockPartKrw), changeColor(s.stockPartKrw))
            ProfitRow("환율 몫", signedWon(s.fxPartKrw), changeColor(s.fxPartKrw))
            ProfitRow("수수료·제비용", "-" + won(s.feeKrw))
            ProfitRow("실현손익", signedWon(s.realizedKrw), changeColor(s.realizedKrw), strong = true)
            Text("달러 기준 ${"%+,.2f".format(s.realizedUsd)} USD", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ---------- 배당 ---------- */

@Composable
private fun DividendSummaryCard(rows: List<DividendRecord>) {
    val net = rows.sumOf { it.netKrw }
    val gross = rows.sumOf { it.grossKrw }
    val tax = rows.sumOf { it.taxKrw }
    ProfitCard(CardTone.Budget) {
        Text("받은 배당 (세후)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(won(net), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = changeColor(net))
        Spacer(Modifier.height(6.dp))
        ProfitRow("세전", won(gross))
        ProfitRow("떼인 세금 (원천징수)", "-" + won(tax))
        val byTicker = rows.groupBy { it.ticker }.mapValues { (_, l) -> l.sumOf { it.netKrw } }.toList().sortedByDescending { it.second }
        Spacer(Modifier.height(6.dp))
        Text(byTicker.joinToString("  ·  ") { "${it.first} ${manWon(it.second)}" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DividendRecordRow(dv: DividendRecord) {
    ProfitCard(CardTone.Budget) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dv.ticker, fontWeight = FontWeight.Bold)
                Text("${dv.date} · 세전 $%,.2f · 세금 $%,.2f".format(dv.grossUsd, dv.taxUsd),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(won(dv.netKrw), fontWeight = FontWeight.Bold, color = changeColor(dv.netKrw))
        }
    }
}

/* ---------- 세금 ---------- */

private const val TAX_DEDUCTION_KRW = 2_500_000.0
private const val TAX_RATE = 0.22

private fun estimatedTax(netGainKrw: Double) = ((netGainKrw - TAX_DEDUCTION_KRW).coerceAtLeast(0.0) * TAX_RATE)

@Composable
private fun TaxCard(sales: List<SaleRecord>, year: Int, simulate: Boolean) {
    val gain = sales.filter { it.realizedKrw > 0 }.sumOf { it.realizedKrw }
    val loss = sales.filter { it.realizedKrw < 0 }.sumOf { it.realizedKrw }
    val net = gain + loss
    val tax = estimatedTax(net)
    val picked = remember(year) { mutableStateOf(setOf<String>()) }
    // LazyColumn 한 항목 안에 카드 여러 개 → Column으로 감싸야 겹치지 않는다
    Column(verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)) {
    ProfitCard(CardTone.Warning) {
        Text("${year}년 해외주식 양도세 (예상)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        ProfitRow("이익 합계", signedWon(gain), changeColor(gain))
        ProfitRow("손실 합계", signedWon(loss), changeColor(loss))
        ProfitRow("양도차익 (손익 통산)", signedWon(net), changeColor(net))
        ProfitRow("기본공제", "-" + won(TAX_DEDUCTION_KRW))
        ProfitRow("과세표준", won((net - TAX_DEDUCTION_KRW).coerceAtLeast(0.0)))
        ProfitRow("예상 세액 (22%)", won(tax), strong = true)
        val left = TAX_DEDUCTION_KRW - net
        Text(
            if (left > 0) "공제까지 ${won(left)} 남았습니다." else "공제 한도를 ${won(-left)} 넘었습니다.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (simulate) {
        val extraLoss = ProfitSample.lossHoldings.filter { it.ticker in picked.value }.sumOf { it.unrealizedKrw }
        val newTax = estimatedTax(net + extraLoss)
        ProfitCard(CardTone.Strategy) {
            Text("손실 종목을 올해 판다면", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("평가손실 종목을 골라 보세요. 연말까지 팔면 올해 이익과 상계됩니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfitSample.lossHoldings.forEach { h ->
                    val on = h.ticker in picked.value
                    FilterChip(selected = on, onClick = {
                        picked.value = if (on) picked.value - h.ticker else picked.value + h.ticker
                    }, label = { Text("${h.ticker} ${manWon(h.unrealizedKrw)}") })
                }
            }
            Spacer(Modifier.height(6.dp))
            ProfitRow("상계할 손실", signedWon(extraLoss), changeColor(extraLoss))
            ProfitRow("바뀐 예상 세액", won(newTax), strong = true)
            ProfitRow("줄어드는 세금", won(tax - newTax), if (tax - newTax > 0) UpRed else Color.Unspecified)
        }
    }
    Text(
        "참고용 계산입니다. 매입원가는 이동평균, 원화 환산은 매도일 환율 기준이며 수수료를 뺀 금액입니다. " +
            "실제 신고는 증권사 양도소득 자료로 확인하세요.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    }
}

/* ---------- 전략 ---------- */

@Composable
private fun StrategyProfitCard(sales: List<SaleRecord>) {
    val groups = sales.groupBy { it.strategy ?: "전략 외 매매" }
        .toList().sortedBy { (if (it.first == "전략 외 매매") "1" else "0") + it.first }
    if (groups.isEmpty()) {
        EmptyCard("이 기간에 매도한 기록이 없습니다.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)) {
        groups.forEach { (name, list) ->
            val realized = list.sumOf { it.realizedKrw }
            val cost = list.sumOf { it.costKrw }
            val wins = list.count { it.realizedKrw > 0 }
            ProfitCard(if (name == "전략 외 매매") CardTone.Data else CardTone.Strategy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(list.map { it.ticker }.distinct().joinToString(", "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                ProfitRow("확정 손익", signedWon(realized), changeColor(realized), strong = true)
                ProfitRow("투입 원가 (매도분)", won(cost))
                ProfitRow("수익률", signedPct(if (cost > 0) realized / cost * 100 else 0.0), changeColor(realized))
                ProfitRow("매도 횟수", "${list.size}회 · 이익 ${wins}회 / 손실 ${list.size - wins}회")
            }
        }
        Text("전략 구분은 매도 종목으로 나눈 것입니다. 매수 1·매도 2 사이클 단위 정산은 전략 체결 기록과 연결한 뒤 붙입니다.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
