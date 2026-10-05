package com.example.stocksgnal.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.data.CandleSource
import com.example.stocksgnal.data.CandleStorage
import com.example.stocksgnal.data.FearGreed
import com.example.stocksgnal.data.FearGreedApi
import com.example.stocksgnal.data.FearGreedStore
import com.example.stocksgnal.data.MarketOverviewApi
import com.example.stocksgnal.data.NamuAccountApi
import com.example.stocksgnal.data.MarketOverviewItem
import com.example.stocksgnal.data.MarketOverviewStore
import com.example.stocksgnal.data.NamuAuthApi
import com.example.stocksgnal.data.loadConfiguredWatchStocks
import com.example.stocksgnal.data.loadWatchIndices
import com.example.stocksgnal.longterm.EngineMode
import com.example.stocksgnal.longterm.FillKind
import com.example.stocksgnal.longterm.LongStrategyRepository
import com.example.stocksgnal.longterm.PlannerResult
import com.example.stocksgnal.longterm.StrategyInstance
import com.example.stocksgnal.longterm.UsMarketCalendar
import com.example.stocksgnal.strategy.SignalStage
import com.example.stocksgnal.strategy.TradingStrategyEngine
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

private data class ShortHit(val ticker: String, val name: String, val strategy: String, val summary: String, val date: String)

private data class HomeData(
    val plans: List<Pair<StrategyInstance, PlannerResult?>>,
    val shortHits: List<ShortHit>,
    val shortChecked: Int,
    val dataStatus: List<Triple<String, Int, String?>>,
    val fearGreed: FearGreed?,
    val marketOverview: List<MarketOverviewItem>,
    val strategyData: StrategyData?
)

@Composable
fun HomeScreen(dataRevision: Int = 0, onStockClick: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("stock_signal_settings", Context.MODE_PRIVATE) }
    val repository = remember { LongStrategyRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var version by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<HomeData?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var selectedHomeTab by remember { mutableIntStateOf(0) }
    var lastRefreshText by remember {
        mutableStateOf(preferences.getString("last_refresh_text", "아직 데이터 갱신 전") ?: "아직 데이터 갱신 전")
    }
    LaunchedEffect(version, dataRevision) {
        lastRefreshText = preferences.getString("last_refresh_text", "아직 데이터 갱신 전").orEmpty()
        data = withContext(Dispatchers.Default) { loadHomeData(context, repository) }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val result = RefreshCoordinator.refresh(context, repository)
            if (result == null) {
                refreshing = false
                return@launch
            }
            // 상세 결과는 설정의 새로고침 상태에서 표시한다.
            lastRefreshText = result.first
            refreshing = false
            version++
        }
    }

    val primary = data?.plans?.firstNotNullOfOrNull { it.second }
    LazyColumn(
        modifier = Modifier.fillMaxSize().swipeTabs(selectedHomeTab, 3) { selectedHomeTab = it },
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Column(modifier = Modifier.heightIn(min = 52.dp)) {
                Text("홈", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("hope is goodthing", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            HomeRefreshCard(refreshing, lastRefreshText, data?.fearGreed) {
                refresh()
            }
        }
        val current = data
        if (current == null) {
            item { CircularProgressIndicator() }
        } else {
            item { HomeTabs(selectedHomeTab) { selectedHomeTab = it } }
            when (selectedHomeTab) {
                0 -> item { MarketOverviewCard(current.marketOverview) }
                1 -> {
                    item { TodayCard(current, onStockClick) }
                    current.plans.forEach { (inst, plan) ->
                        val market = plan?.market
                        if (market != null && (market.mode == EngineMode.BUY || market.mode == EngineMode.SELL_WAIT)) {
                            item { StrategySummaryCard(inst, plan) }
                        }
                    }
                    if (current.plans.any { it.second != null }) item { WeekScheduleCard(current.plans) }
                }
                2 -> item { HomeCalendarCard(current.strategyData) }
            }
        }
    }
}

private fun loadHomeData(context: Context, repository: LongStrategyRepository): HomeData {
    val plans = runCatching { repository.computeAll() }.getOrDefault(emptyList())
    val cutoff = repository.completedCutoff().toString()
    val stocks = loadConfiguredWatchStocks(context).filter { it.enabled }
    var checked = 0
    val hits = stocks.flatMap { stock ->
        val candles = CandleStorage.loadCandles(context, stock.ticker).filter { it.date.take(10) <= cutoff }
        if (candles.size < 201) return@flatMap emptyList<ShortHit>()
        checked++
        val snapshot = runCatching { TradingStrategyEngine.evaluate(stock.ticker, candles) }.getOrNull()
            ?: return@flatMap emptyList<ShortHit>()
        snapshot.signals.filter { it.stage == SignalStage.ENTRY }.map {
            ShortHit(stock.ticker, stock.name, it.type.label, it.summary, snapshot.date.take(10))
        }
    }
    val status = repository.instances().flatMap { listOf(it.signalTicker, it.tradeTicker) }.distinct().map { ticker ->
        val candles = CandleStorage.loadCandles(context, ticker)
        Triple(ticker, candles.size, candles.maxOfOrNull { it.date }?.take(10))
    }
    return HomeData(
        plans = plans,
        shortHits = hits,
        shortChecked = checked,
        dataStatus = status,
        fearGreed = FearGreedStore.load(context),
        marketOverview = homeMarketOverview(context),
        strategyData = runCatching { loadStrategyData(context, repository) }.getOrNull()
    )
}

/**
 * 홈 › 주요지수에 보여줄 목록.
 * 관심 › 지수 탭에서 켜 둔 지수만, 등록한 순서대로. 아직 값을 못 받은 지수는 "-"로 표시.
 */
private fun homeMarketOverview(context: Context): List<MarketOverviewItem> {
    val stored = MarketOverviewStore.load(context)
    val indexItems = loadWatchIndices(context).filter { it.enabled }.map { index ->
        stored.firstOrNull { it.symbol == index.name && (it.source.startsWith("나무증권") || it.source.startsWith("미 재무부")) }
            ?: MarketOverviewItem(index.name, null, null, "새로고침 후 표시", index.name)
    }
    return indexItems
}

internal fun refreshAll(context: Context, repository: LongStrategyRepository): Pair<String, List<String>> {
    val settings = context.getSharedPreferences("stock_signal_settings", Context.MODE_PRIVATE)
    settings.edit().remove("last_account_refresh_durations").apply()
    val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
    val stamp = "%04d-%02d-%02d %02d:%02d".format(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
    val failures = mutableListOf<String>()
    val fearGreed = FearGreedApi.fetch(stamp)
    if (fearGreed.data != null) FearGreedStore.save(context, fearGreed.data) else failures.add("공포탐욕지수(CNN)")

    // DJUSSC는 FMP, 주식·계좌·나머지 지수는 나무증권.
    val namuConnection = NamuAuthApi.verifyAccountConnection(context)
    CandleSource.beginRefresh(namuConnection.success, namuConnection.message)
    val longText = runCatching { repository.refresh() }.getOrElse { "계곡다이빙 갱신 실패: ${it.message}" }
    longText.split(" · ").filter { it.contains("실패") }.forEach { failures.add(it) }
    if (!namuConnection.success) {
        failures.add("나무증권")
    } else {
        val latestTradingDay = repository.latestCompletedTradingDay()
        val longSymbols = repository.allSymbols()

        loadConfiguredWatchStocks(context)
            .filter { it.enabled && it.ticker !in longSymbols }
            .forEach { stock ->
                // 나무증권 기준이 아닌 예전 일봉은 전체를 다시 받아 통째로 바꾼다
                val namuBased = CandleSource.isCurrentSource(context, stock.ticker)
                val saved = if (namuBased) CandleStorage.loadCandles(context, stock.ticker) else emptyList()
                val lastDate = saved.maxOfOrNull { it.date }
                val lastTradingDate = lastDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (lastTradingDate != null && !lastTradingDate.isBefore(latestTradingDay)) return@forEach
                val result = if (lastDate == null) {
                    CandleSource.getDailyCandles(context, stock.ticker, outputSize = 500)
                } else {
                    CandleSource.getDailyCandles(context, stock.ticker, outputSize = 500, startDate = lastDate.take(10), endDate = latestTradingDay.toString())
                }
                when {
                    !result.success -> failures.add(stock.ticker)
                    lastDate == null -> CandleStorage.saveCandles(context, stock.ticker, result.candles.sortedBy { it.date })
                    else -> CandleStorage.mergeAndSave(context, stock.ticker, result.candles)
                }
                if (result.success) CandleSource.markCurrentSource(context, stock.ticker)
            }
    }

    settings.edit().putString("last_data_source", CandleSource.summary()).apply()

    // 주요지수: 나무증권 항목은 연결이 안 되면 실패로 표시되고, 국채금리(재무부)는 따로 받는다
    val overview = MarketOverviewApi.refresh(context)
    failures.addAll(overview.second.filter { namuConnection.success || !it.contains("나무증권") })
    if (namuConnection.success) {
        // 계좌의 나무증권 잔고 (조회 전용)
        failures.addAll(NamuAccountApi.refreshBalances(context))
    }
    return stamp to failures
}

@Composable
private fun HomeTabs(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf("주요지수", "오늘의 판단", "캘린더")
    TabRow(selectedTabIndex = selectedTab) {
        tabs.forEachIndexed { index, title ->
            Tab(selected = selectedTab == index, onClick = { onTabSelected(index) }, text = { Text(title) })
        }
    }
}

@Composable
private fun HomeRefreshCard(
    refreshing: Boolean,
    lastRefreshText: String,
    fearGreed: FearGreed?,
    onRefresh: () -> Unit
) {
    HomeCard(CardTone.Data) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("마지막 갱신 $lastRefreshText", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "받는 중" else "새로고침")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("공포·탐욕지수", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(fearGreed?.let { "%.0f".format(it.score) } ?: "—",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(fearGreed?.label ?: "수신 전", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MarketOverviewCard(items: List<MarketOverviewItem>) {
    HomeCard(CardTone.Data) {
        Text("주요지수", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text("표시할 지수가 없습니다. 관심 › 지수 탭에서 켜 주세요.", style = MaterialTheme.typography.bodySmall)
            return@HomeCard
        }
        items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.label, fontWeight = FontWeight.Bold)
                    Text(item.source, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMarketValue(item), fontWeight = FontWeight.Bold)
                    item.changePct?.let {
                        // 금리는 전일 대비 %p, 나머지는 등락률 %
                        Text(if (item.label.contains("금리")) "%+.2f%%p".format(it) else "%+.2f%%".format(it), style = MaterialTheme.typography.bodySmall, color = com.example.stocksgnal.ui.theme.changeColor(it))
                    }
                }
            }
        }
    }
}

private fun formatMarketValue(item: MarketOverviewItem): String {
    val value = item.value ?: return "-"
    return if (item.label.contains("금리")) "%.2f%%".format(value) else "%,.2f".format(value)
}

@Composable
private fun TodayCard(data: HomeData, onStockClick: (String) -> Unit) {
    HomeCard(CardTone.Action) {
        Text("오늘의 판단", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        data.plans.forEachIndexed { index, (inst, plan) ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            val market = plan?.market
            Row(modifier = Modifier.fillMaxWidth().clickable { onStockClick(inst.tradeTicker) }, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${inst.tradeTicker} - ${inst.name}", fontWeight = FontWeight.Bold)
                    Text(market?.mode?.label ?: "데이터 부족", style = MaterialTheme.typography.bodySmall)
                }
                val orders = plan?.accounts?.values?.sumOf { it.orders.size } ?: 0
                Text(if (orders == 0) "주문 없음" else "주문 ${orders}건", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StrategySummaryCard(inst: StrategyInstance, plan: PlannerResult) {
    val market = plan.market
    HomeCard(CardTone.Strategy) {
        Text("장기전략 · ${inst.signalTicker} 신호 → ${inst.tradeTicker} 매수", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${market.stage.label} / 기준일 ${market.date}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("현재 ${market.signalTicker} %.2f / 기준 H %.2f / 등락 %+.2f%%".format(market.qqqClose, market.referenceHigh, market.drawdownPct))
    }
}

@Composable
private fun HomeCalendarCard(data: StrategyData?) {
    HomeCard(CardTone.Schedule) {
        var month by remember { mutableStateOf(YearMonth.now()) }
        val events = remember(data, month) {
            data?.let { buildCalendarEvents(it, month).sortedWith(compareBy({ event -> event.date }, { event -> event.title })) }
                ?: emptyList()
        }
        val firstDayOffset = month.atDay(1).dayOfWeek.value % 7
        val days = List<LocalDate?>(firstDayOffset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        val today = LocalDate.now()

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹ 이전") }
            Text(
                "${month.year}년 ${month.monthValue}월",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("다음 ›") }
        }
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text("이번 달 일정이 없습니다.", style = MaterialTheme.typography.bodySmall)
        } else {
            events.forEach { event ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${event.date.monthValue}/${event.date.dayOfMonth}", fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                    Text(event.title, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { index, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (index) {
                        0 -> MaterialTheme.colorScheme.error
                        6 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEachIndexed { index, date ->
                    Column(
                        modifier = Modifier.weight(1f).height(44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (date != null) {
                            val marketHoliday = date.dayOfWeek.value <= 5 && UsMarketCalendar.isHoliday(date)
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    date == today -> MaterialTheme.colorScheme.primary
                                    index == 0 -> MaterialTheme.colorScheme.error
                                    index == 6 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (marketHoliday) {
                                Text("×", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            } else if (events.any { it.date == date }) {
                                Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

private data class WeekItem(val date: LocalDate, val text: String, val alert: Boolean = false)

@Composable
private fun WeekScheduleCard(plans: List<Pair<StrategyInstance, PlannerResult?>>) {
    val firstMarket = plans.firstNotNullOfOrNull { it.second }?.market ?: return
    val start = firstMarket.nextTradingDay
    val end = firstMarket.date.plusDays(7)
    val items = buildList {
        plans.forEach { (inst, plan) ->
            plan?.accounts?.toSortedMap()?.forEach { (accountId, accountPlan) ->
                accountPlan.orders.filter { it.kind != FillKind.CAMPAIGN }.forEach {
                    add(WeekItem(start, "${inst.accountName(accountId)} - ${it.title}", it.kind == FillKind.SELL))
                }
                accountPlan.schedule.filter { !it.date.isAfter(end) }.forEach {
                    add(WeekItem(it.date, "${inst.accountName(accountId)} - ${it.title} - %,.0f원".format(it.amountKrw)))
                }
            }
        }
    }.sortedBy { it.date }

    HomeCard(CardTone.Schedule) {
        Text("이번 주 일정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) {
            Text("예정된 분할 매수가 없습니다.", style = MaterialTheme.typography.bodySmall)
        } else {
            items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${item.date.monthValue}/${item.date.dayOfMonth}", fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                    Text(item.text, color = if (item.alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun HomeCard(tone: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StockDesign.CardRadius),
        colors = glassCardColors(),
        border = toneBorder(tone)
    ) {
        Column(modifier = Modifier.padding(StockDesign.CardPadding)) { content() }
    }
}
