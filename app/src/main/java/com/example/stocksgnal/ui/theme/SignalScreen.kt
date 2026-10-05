package com.example.stocksgnal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.longterm.FillKind
import com.example.stocksgnal.longterm.LongStrategyRepository
import com.example.stocksgnal.strategy.SignalStage
import com.example.stocksgnal.strategy.StrategyType
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 전략 메뉴: 신호 · 계곡다이빙 · 단기전략
 */

@Composable
fun SignalScreen(onStockClick: (String) -> Unit = {}, onOpenWatchlist: () -> Unit = {}) {

    val context = LocalContext.current
    val repository = remember { LongStrategyRepository(context.applicationContext) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<StrategyData?>(null) }
    val tabs = listOf("한눈에", "계곡다이빙", "단기전략")

    // 탭을 옮길 때마다 저장된 데이터로 다시 계산 (계곡다이빙 탭에서 갱신한 결과 반영)
    LaunchedEffect(selectedTab) {
        data = withContext(Dispatchers.Default) { loadStrategyData(context, repository) }
    }

    Column(modifier = Modifier.fillMaxSize().swipeTabs(selectedTab, tabs.size) { selectedTab = it }) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> SignalOverviewTab(data, onStockClick, onOpenWatchlist) { selectedTab = 1 }
            1 -> LongStrategySignalScreen()
            2 -> ShortStrategyTab(data, onStockClick)
        }
    }
}

@Composable
private fun StrategyCard(tone: Color, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StockDesign.CardRadius),
        colors = glassCardColors(),
        border = toneBorder(tone)
    ) {
        Column(modifier = Modifier.padding(StockDesign.CardPadding)) { content() }
    }
}

/* =========================
   신호: 오늘 조건에 들어온 티커 모아보기
   ========================= */

private data class SignalRow(
    val ticker: String,
    val tag: String,
    val title: String,
    val detail: String,
    val alert: Boolean = false
)

@Composable
private fun SignalOverviewTab(
    data: StrategyData?,
    onStockClick: (String) -> Unit,
    onOpenWatchlist: () -> Unit,
    onOpenLong: () -> Unit
) {
    if (data == null) {
        Column(modifier = Modifier.padding(StockDesign.ScreenPadding)) { CircularProgressIndicator() }
        return
    }
    val m = data.primaryMarket

    // 매수·매도 카드는 계곡다이빙(장기) 주문만 사용한다. 단기전략은 여기에 연결하지 않는다.
    val longRows = buildList {
        data.plans.forEach { (inst, plan) -> plan?.accounts?.toSortedMap()?.forEach { (acct, v) ->
            v.orders.forEach { o ->
                add(
                    SignalRow(
                        ticker = inst.tradeTicker,
                        tag = inst.name,
                        title = "${inst.accountName(acct)} · ${o.title}",
                        detail = "%,.0f원".format(o.amountKrw) + (if (o.recorded) " · 기록됨" else ""),
                        alert = o.kind == FillKind.SELL
                    ) to (o.kind == FillKind.SELL)
                )
            }
        } }
    }
    val buyRows = longRows.filter { !it.second }.map { it.first }
    val sellRows = longRows.filter { it.second }.map { it.first }
    // 단기전략은 아직 다른 신호와 연계하지 않는다. 단기전략 탭에서만 독립적으로 표시한다.
    val ready = emptyList<SignalRow>()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("한눈에", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = m?.let { "${it.date} 종가 기준 · 다음 거래일 ${it.nextTradingDay}" } ?: "데이터를 먼저 갱신해 주세요.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text("관심종목(개발중)", modifier = Modifier.clickable { onOpenWatchlist() }, style = MaterialTheme.typography.labelLarge)
            }
        }

        item {
            StrategyCard(CardTone.Action) {
                Text("매수 신호 · ${buyRows.size}건", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                if (buyRows.isEmpty()) Text("현재 매수 조건에 해당하는 신호가 없습니다.", style = MaterialTheme.typography.bodySmall)
                buyRows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider()
                    SignalRowView(row, onStockClick)
                }
            }
        }

        item {
            StrategyCard(CardTone.Sell) {
                Text("매도 신호 · ${sellRows.size}건", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                if (sellRows.isEmpty()) Text("현재 매도 조건에 해당하는 신호가 없습니다.", style = MaterialTheme.typography.bodySmall)
                sellRows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider()
                    SignalRowView(row, onStockClick)
                }
            }
        }

        if (ready.isNotEmpty()) {
            item {
                StrategyCard(CardTone.Recovery) {
                    Text("준비 단계 (단기)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    ready.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider()
                        SignalRowView(row, onStockClick)
                    }
                }
            }
        }

        if (data.plans.isNotEmpty()) {
            item {
                StrategyCard(CardTone.Strategy) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("계곡다이빙", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("상세 보기 ›", modifier = Modifier.clickable { onOpenLong() }, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    data.plans.forEach { (inst, plan) ->
                        val pm = plan?.market
                        Text(
                            text = if (pm == null) "${inst.name}(${inst.tradeTicker}) · 데이터 없음"
                            else "${inst.name}(${inst.tradeTicker}) · ${pm.signalTicker} 고점 대비 %+.2f%% ${pm.stage.label}".format(pm.drawdownPct),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    Text("자세한 조건은 [계곡다이빙] 탭에서 확인합니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Text(
                text = "티커를 누르면 차트로 이동합니다. 단기전략은 위 매수·매도 신호와 별개이며 참고용입니다.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SignalRowView(row: SignalRow, onStockClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onStockClick(row.ticker) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.width(76.dp)) {
            Text(row.ticker, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(row.tag, style = MaterialTheme.typography.labelSmall)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                fontWeight = FontWeight.Bold,
                color = if (row.alert) MaterialTheme.colorScheme.error else Color.Unspecified
            )
            Text(row.detail, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

/* =========================
   단기전략: 관심종목 실제 데이터로 판정
   ========================= */

@Composable
private fun ShortStrategyTab(data: StrategyData?, onStockClick: (String) -> Unit) {
    var selectedFilter by remember { mutableStateOf<StrategyType?>(null) }

    if (data == null) {
        Column(modifier = Modifier.padding(StockDesign.ScreenPadding)) { CircularProgressIndicator() }
        return
    }

    val items = data.short.rows
        .flatMap { row -> row.signals.map { row to it } }
        .filter { selectedFilter == null || it.second.type == selectedFilter }
        .sortedWith(compareByDescending<Pair<ShortStrategyRow, com.example.stocksgnal.strategy.StrategySignal>> { it.second.stage.rank }.thenBy { it.first.ticker })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Text("단기전략", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("관심종목의 단기 매매 조건을 검사합니다 (참고용)", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedFilter == null, onClick = { selectedFilter = null }, label = { Text("전체") })
                StrategyType.entries.forEach { type ->
                    FilterChip(selected = selectedFilter == type, onClick = { selectedFilter = type }, label = { Text(type.label) })
                }
            }
        }
        if (items.isEmpty()) {
            item {
                StrategyCard(CardTone.Data) {
                    Text("표시할 종목이 없습니다.", fontWeight = FontWeight.Bold)
                    Text("관심 탭에서 관심종목을 ON으로 두고, 홈에서 새로고침해 주세요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(items) { (row, s) ->
            StrategyCard(CardTone.Short) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onStockClick(row.ticker) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.ticker, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(row.name, style = MaterialTheme.typography.bodySmall)
                    }
                    AssistChip(onClick = { onStockClick(row.ticker) }, label = { Text(s.type.label) })
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    text = s.stage.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (s.stage == SignalStage.ENTRY) MaterialTheme.colorScheme.primary else Color.Unspecified
                )
                s.checks.forEach { c ->
                    Text((if (c.passed) "✓ " else "· ") + c.label, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${row.date} 기준 · RSI(14)", style = MaterialTheme.typography.bodySmall)
                    Text(s.rsi14?.let { "%.1f".format(it) } ?: "-", fontWeight = FontWeight.Bold)
                }
            }
        }
        if (data.short.skipped.isNotEmpty()) {
            item {
                Text(
                    text = "데이터 부족(201일 미만)으로 제외: ${data.short.skipped.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
