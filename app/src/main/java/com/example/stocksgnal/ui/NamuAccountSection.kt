package com.example.stocksgnal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import com.example.stocksgnal.data.AssetSnapshot
import com.example.stocksgnal.data.LinkedNamuAccount
import com.example.stocksgnal.data.NamuAccountApi
import com.example.stocksgnal.data.NamuAccountInfo
import com.example.stocksgnal.data.NamuBalance
import com.example.stocksgnal.data.NamuHolding
import com.example.stocksgnal.data.ForeignRpHolding
import com.example.stocksgnal.data.NamuDividendApi
import com.example.stocksgnal.data.DividendRow
import com.example.stocksgnal.data.DividendForecast
import com.example.stocksgnal.data.DividendCalendarEvent
import com.example.stocksgnal.data.DividendCalendarKind
import com.example.stocksgnal.data.dividendCalendarEvents
import com.example.stocksgnal.data.MassiveDividendApi
import com.example.stocksgnal.data.maskAccountNo
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.changeColor
import com.example.stocksgnal.ui.theme.glassCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 하단 [계좌] 메뉴 — 더리치(therich.io) 포트폴리오 화면 구성을 따른다. 나무증권 계좌 조회 전용.
 *   [전체 | 계좌1 | 계좌2 …] 선택
 *   전체:   ① 총자산 요약(원/달러 전환)  ② 수익 추이(자산·원금, 원화 기준)  ③ 자산 구성(도넛)  ④ 계좌 목록
 *   계좌별: ① 총자산 요약  ② 수익 추이  ③ 자산 구성  ④ 보유 종목
 *   원/달러 전환은 화면 전체 금액에 적용(수익 추이 그래프는 기록이 원화라 원화 그대로).
 * 잔고·등락률·업종은 홈 새로고침 때 나무증권에서 받는다. 수익 추이는 새로고침할 때마다 그날 값을 기록해 쌓는다.
 * 전체는 받은 배당 그래프만, 계좌 상세는 자산·배당·추이 탭으로 나눈다.
 */

private val SliceColors = listOf(
    Color(0xFFE5484D), Color(0xFFF5A524), Color(0xFF3E7BFA), Color(0xFF2B4C9B),
    Color(0xFF12A594), Color(0xFF8E4EC6), Color(0xFFD6409F), Color(0xFF46A758)
)
private val EtcColor = Color(0xFF8A94A6)
private val CashColor = Color(0xFFB8C0CC)

private fun krw(v: Double) = "₩%,.0f".format(v)
private fun pctText(v: Double) = "%+.2f%%".format(v)

/** 나무 총자산에 외화 RP 평가금액을 포함한 화면용 총자산. */
private fun totalAssetWithRp(balance: NamuBalance): Double =
    balance.totalAssetKrw + balance.foreignRp.orEmpty().sumOf { it.evalKrw }

/**
 * 화면 금액 표시 통화. usd=true면 원화 금액을 [fx](원/달러)로 나눠 달러로 보여준다.
 * fx는 나무증권 잔고의 원화평가금액 ÷ 외화평가금액(나무가 쓴 환율). 보유 종목이 없으면 저장된 환율.
 */
private class Money(val usd: Boolean, val fx: Double?) {
    private val on get() = usd && fx != null && fx > 0
    /** 원화 금액 → 표시 문자열 */
    fun of(krwValue: Double): String = if (on) "$%,.2f".format(krwValue / fx!!) else krw(krwValue)
    /** 이미 달러인 값(달러 모드) 또는 원화 값(원화 모드) */
    fun pick(krwValue: Double, usdValue: Double): String = if (on) "$%,.2f".format(usdValue) else krw(krwValue)
    fun signed(text: String, v: Double) = (if (v >= 0) "+" else "-") + text.removePrefix("-")
    fun signedOf(krwValue: Double): String = signed(of(kotlin.math.abs(krwValue)), krwValue)
    fun signedPick(krwValue: Double, usdValue: Double): String {
        val v = if (on) usdValue else krwValue
        return signed(pick(kotlin.math.abs(krwValue), kotlin.math.abs(usdValue)), v)
    }
    fun short(krwValue: Double): String = if (on) {
        val u = krwValue / fx!!
        when { u >= 1e6 -> "$%.2fM".format(u / 1e6); u >= 1e3 -> "$%,.1fK".format(u / 1e3); else -> "$%,.0f".format(u) }
    } else shortKrw(krwValue)
    val isUsd get() = on
}

/** 원/달러: 잔고의 원화평가 ÷ 외화평가, 없으면 전략 화면에 저장된 환율(수동 우선) */
private fun accountFx(context: android.content.Context, balances: Collection<NamuBalance>): Double? {
    val evk = balances.sumOf { b -> b.holdings.sumOf { it.evalKrw } }
    val evu = balances.sumOf { b -> b.holdings.sumOf { it.evalUsd } }
    if (evk > 0 && evu > 0) return (evk / evu).takeIf { it in 500.0..5000.0 }
    val p = context.getSharedPreferences("long_strategy", android.content.Context.MODE_PRIVATE)
    return (p.getString("usdkrw_manual", null)?.toDoubleOrNull()?.takeIf { it > 0 }
        ?: p.getString("usdkrw_auto", null)?.toDoubleOrNull()?.takeIf { it > 0 })
}

/** 1.3억, 4,532만 처럼 짧게 */
private fun shortKrw(v: Double): String = when {
    v >= 1e8 -> "%.1f억".format(v / 1e8)
    v >= 1e4 -> "%,.0f만".format(v / 1e4)
    else -> "%,.0f".format(v)
}

/** 화면 계산용: 선택 범위(전체 또는 계좌 하나)의 종목 합산 */
private data class MergedHolding(
    val ticker: String, val name: String, val industry: String,
    val quantity: Double, val costUsd: Double, val closeUsd: Double,
    val evalKrw: Double, val pnlKrw: Double, val dayChangePct: Double?,
    val evalUsd: Double = 0.0
) {
    /** 달러 기준 손익(가격 변동만, 환차손익 제외) */
    val pnlUsd get() = evalUsd - costUsd
    val returnPctUsd get() = if (costUsd > 0) pnlUsd / costUsd * 100 else 0.0
    val dayPnlUsd get() = dayChangePct?.let { evalUsd - evalUsd / (1 + it / 100) } ?: 0.0
    val avgUsd get() = if (quantity > 0) costUsd / quantity else 0.0
    val costKrw get() = evalKrw - pnlKrw
    val returnPct get() = if (costKrw > 0) pnlKrw / costKrw * 100 else 0.0
    /** 오늘 등락으로 생긴 원화 손익 */
    val dayPnlKrw get() = dayChangePct?.let { evalKrw - evalKrw / (1 + it / 100) } ?: 0.0
}

private fun merge(balances: List<NamuBalance>): List<MergedHolding> =
    balances.flatMap { it.holdings }.groupBy { it.ticker }.map { (ticker, list) ->
        MergedHolding(
            ticker = ticker,
            name = list.first().name,
            industry = list.firstOrNull { it.industry.isNotBlank() }?.industry.orEmpty(),
            quantity = list.sumOf { it.quantity },
            costUsd = list.sumOf { it.quantity * it.avgPriceUsd },
            closeUsd = list.first().closeUsd,
            evalKrw = list.sumOf { it.evalKrw },
            pnlKrw = list.sumOf { it.pnlKrw },
            dayChangePct = list.first().dayChangePct,
            evalUsd = list.sumOf { it.evalUsd }
        )
    }.sortedByDescending { it.evalKrw }

/** API 연결 전 화면 확인용. SharedPreferences에는 저장하지 않는다. */
private fun accountPreviewBalance(acctNo: String) = NamuBalance(
    acctNo = acctNo,
    fetchedAt = "미리보기",
    usdCash = 1_240.50,
    krwCash = 2_850_000.0,
    evalKrw = 12_420_000.0,
    totalAssetKrw = 17_350_000.0,
    principalKrw = 10_840_000.0,
    holdings = listOf(
        NamuHolding("QQQ", "Invesco QQQ Trust", 8.0, 495.0, 525.0, 4_200.0, 5_880_000.0, 340_000.0, 6.1, 0.42, "ETF"),
        NamuHolding("NVDA", "NVIDIA", 12.0, 168.0, 181.0, 2_172.0, 3_040_800.0, 220_800.0, 7.8, 0.63, "반도체"),
        NamuHolding("SCHD", "Schwab U.S. Dividend Equity ETF", 20.0, 27.0, 28.4, 568.0, 795_200.0, 39_200.0, 5.2, -0.18, "ETF")
    ),
    foreignRp = listOf(ForeignRpHolding("USD 외화 RP", "USD", 1_100.0, 1_540_000.0))
)

@Composable
fun NamuAccountsScreen(onStockClick: (String) -> Unit, dataRevision: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val dataVersion = version + dataRevision
    val storedLinked = remember(dataVersion) { NamuAccountApi.linkedAccounts(context) }
    val storedBalances = remember(dataVersion) { NamuAccountApi.loadBalances(context).associateBy { it.acctNo } }
    // API 키/잔고를 아직 넣지 않은 상태에서도 계좌 화면의 구성과 통화 전환을 확인할 수 있게 한다.
    // 실제 잔고가 하나라도 있으면 이 미리보기는 즉시 사라지며 저장하지 않는다.
    val previewAccount = storedLinked.firstOrNull() ?: LinkedNamuAccount("__preview__", "미리보기 계좌")
    val linked = if (storedLinked.isEmpty()) listOf(previewAccount) else storedLinked
    val balances = if (storedBalances.isEmpty()) {
        mapOf(previewAccount.acctNo to accountPreviewBalance(previewAccount.acctNo))
    } else storedBalances
    val snapshots = remember(dataVersion) { NamuAccountApi.loadSnapshots(context) }
    var selected by remember { mutableStateOf<String?>(null) }   // null = 전체
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LinkedNamuAccount?>(null) }
    var loadingMessage by remember { mutableStateOf<String?>(null) }
    val viewPrefs = remember { context.getSharedPreferences("stock_signal_settings", android.content.Context.MODE_PRIVATE) }
    var usd by remember { mutableStateOf(viewPrefs.getBoolean("account_usd", false)) }
    val listState = rememberLazyListState()

    val scopeAccounts = linked.filter { selected == null || it.acctNo == selected }
    val scopeBalances = scopeAccounts.mapNotNull { balances[it.acctNo] }
    val merged = merge(scopeBalances)
    val fx = remember(dataVersion) { accountFx(context, balances.values) }
    val money = Money(usd, fx)
    var detailTab by remember(selected) { mutableIntStateOf(0) }
    var holdingFilter by remember(selected) { mutableIntStateOf(0) } // 전체·주식·예수금·외화 RP
    val accountDividends = remember(dataVersion, selected) {
        if (selected == null) emptyList() else NamuDividendApi.load(context).filter { it.acctNo == selected }
    }
    val externalDividends = remember(dataVersion, selected, detailTab) {
        if (selected == null) emptyMap() else MassiveDividendApi.load(context)
    }
    val forecastDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
    val holdingForecasts = remember(accountDividends, merged, externalDividends, forecastDate) {
        NamuDividendApi.forecast(accountDividends, merged.associate { it.ticker.uppercase() to it.quantity }, externalDividends, forecastDate)
            .associateBy { it.ticker.uppercase() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().swipeTabs(detailTab, if (selected != null) 3 else 0) {
            detailTab = it
            scope.launch { listState.scrollToItem(0) }
        },
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Row(modifier = Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(linked.firstOrNull { it.acctNo == selected }?.label ?: "내 자산", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { showAdd = true }) { Text("+ 계좌 추가") }
            }
        }
        loadingMessage?.let { msg -> item { Text(msg, style = MaterialTheme.typography.bodySmall) } }

        if (linked.isEmpty()) {
            item {
                PortfolioCard {
                    Text("가져온 계좌가 없습니다.", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "+ 계좌 추가를 누르면 나무증권 API로 볼 수 있는 계좌 목록이 나옵니다. " +
                            "설정 › 데이터관리에서 AppKey·AppSecret을 먼저 저장해 주세요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            return@LazyColumn
        }

        // 계좌 선택
        item {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("전체") })
                linked.forEach { a ->
                    FilterChip(selected = selected == a.acctNo, onClick = { selected = a.acctNo }, label = { Text(a.label) })
                }
            }
        }

        if (selected != null) {
            item {
                PrimaryTabRow(selectedTabIndex = detailTab) {
                    listOf("자산", "배당", "추이").forEachIndexed { index, title ->
                        Tab(selected = detailTab == index, onClick = {
                            detailTab = index
                            scope.launch { listState.scrollToItem(0) }
                        }, text = { Text(title) })
                    }
                }
            }
        }

        if (scopeBalances.isEmpty()) {
            item {
                PortfolioCard {
                    Text("아직 잔고를 받지 않았습니다.", fontWeight = FontWeight.Bold)
                    Text("홈에서 새로고침하면 표시됩니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            if (selected == null || detailTab == 0) {
                item {
                    SummaryCard(scopeBalances, merged, money, canUsd = fx != null, showAssetGroups = selected == null) {
                        usd = !usd
                        viewPrefs.edit().putBoolean("account_usd", usd).apply()
                    }
                }
            }
            if (selected == null || detailTab == 2) {
                item { TrendCard(snapshots.filter { s -> scopeAccounts.any { it.acctNo == s.acctNo } }, money.isUsd) }
            }
            if (selected == null) {
                item { CompositionCard(scopeBalances, merged, money) }
                item { DividendCard(scopeAccounts.map { it.acctNo }, merged, money, dataVersion, graphOnly = true) }
            } else if (detailTab == 0) {
                item {
                    CompositionCard(scopeBalances, merged, money) {
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(14.dp))
                        ReceivedDividendGraphContent(scopeAccounts.map { it.acctNo }, money, dataVersion)
                    }
                }
            } else if (detailTab == 1) {
                item { DividendCard(scopeAccounts.map { it.acctNo }, merged, money, dataVersion, onStockClick = onStockClick) }
            }
            // 보유 종목은 계좌별 보기에서만 (전체 보기에서는 뺌)
            if (selected != null && detailTab == 0) {
                item {
                    CardTitle("보유 자산", "주식 ${merged.size}종목")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("전체", "주식", "예수금", "외화 RP").forEachIndexed { index, title ->
                            FilterChip(selected = holdingFilter == index, onClick = { holdingFilter = index }, label = { Text(title) })
                        }
                    }
                }
                if (merged.isEmpty() && holdingFilter == 1) {
                    item { Text("보유 종목 없음", style = MaterialTheme.typography.bodySmall) }
                }
                if (holdingFilter == 0 || holdingFilter == 1) merged.forEach { holding ->
                    item(key = "holding:$selected:${holding.ticker}") {
                        HoldingCard(holding, money, holdingForecasts[holding.ticker.uppercase()], onStockClick)
                    }
                }
                if (holdingFilter == 0 || holdingFilter == 2) {
                    item(key = "cash:$selected") { CashHoldingCard(scopeBalances, money) }
                }
                if (holdingFilter == 0 || holdingFilter == 3) {
                    item(key = "foreign-rp:$selected") { ForeignRpHoldingCard(scopeBalances, money) }
                }
            }
        }

        if (selected == null) {
            item { CardTitle("계좌별 요약", "${linked.size}개") }
            val accountsTotal = linked.mapNotNull { balances[it.acctNo] }.sumOf(::totalAssetWithRp)
            linked.forEachIndexed { index, account ->
                item(key = "account:${account.acctNo}") {
                    AccountSummaryCard(account, balances[account.acctNo], index, accountsTotal, money) {
                        selected = account.acctNo
                        scope.launch { listState.scrollToItem(0) }
                    }
                }
            }
        } else {
            linked.firstOrNull { it.acctNo == selected }?.let { acct ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${acct.label} · ${maskAccountNo(acct.acctNo)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { renaming = acct }) { Text("이름 바꾸기·빼기") }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddNamuAccountDialog(
                alreadyLinked = storedLinked.map { it.acctNo }.toSet(),
            onDismiss = { showAdd = false },
            onAdd = { added ->
                showAdd = false
                if (added.isEmpty()) return@AddNamuAccountDialog
                NamuAccountApi.saveLinkedAccounts(context, storedLinked + added)
                version++
                loadingMessage = "잔고 받는 중…"
                scope.launch {
                    val failures = withContext(Dispatchers.IO) { NamuAccountApi.refreshBalances(context) }
                    loadingMessage = if (failures.isEmpty()) null else "잔고 조회 실패: ${failures.joinToString(", ")}"
                    version++
                }
            }
        )
    }

    renaming?.let { acct ->
        RenameNamuAccountDialog(
            account = acct,
            onDismiss = { renaming = null },
            onSave = { label ->
                NamuAccountApi.saveLinkedAccounts(context, linked.map { if (it.acctNo == acct.acctNo) it.copy(label = label) else it })
                renaming = null
                version++
            },
            onRemove = {
                NamuAccountApi.saveLinkedAccounts(context, linked.filterNot { it.acctNo == acct.acctNo })
                renaming = null
                if (selected == acct.acctNo) selected = null
                version++
            }
        )
    }
}

@Composable
private fun PortfolioCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = glassCardColors(),
        border = toneBorder(CardTone.Account)
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun CardTitle(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Spacer(Modifier.height(10.dp))
}

/* ① 총자산 요약 */
@Composable
private fun SummaryCard(
    balances: List<NamuBalance>,
    merged: List<MergedHolding>,
    money: Money,
    canUsd: Boolean,
    showAssetGroups: Boolean,
    onToggleUsd: () -> Unit
) {
    val total = balances.sumOf(::totalAssetWithRp)
    val latest = balances.maxOfOrNull { it.fetchedAt }.orEmpty()
    // 원화: 나무 원화평가손익(환차 포함) / 달러: 외화평가 - 외화매입(가격 변동만)
    val pnlKrw = merged.sumOf { it.pnlKrw }
    val costKrw = merged.sumOf { it.costKrw }
    val pnlUsd = merged.sumOf { it.pnlUsd }
    val costUsd = merged.sumOf { it.costUsd }
    val pnl = if (money.isUsd) pnlUsd else pnlKrw
    val cost = if (money.isUsd) costUsd else costKrw
    val dayPnl = if (money.isUsd) merged.sumOf { it.dayPnlUsd } else merged.sumOf { it.dayPnlKrw }
    val dayBase = (if (money.isUsd) money.fx?.let { total / it } ?: 0.0 else total) - dayPnl

    PortfolioCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("총자산 요약", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("조회 $latest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(
                selected = money.isUsd,
                enabled = canUsd,
                onClick = onToggleUsd,
                label = { Text(if (money.isUsd) "$ 달러" else "₩ 원화") }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(money.of(total), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        if (merged.any { it.dayChangePct != null }) {
            Text(
                "일간 ${money.signedPick(merged.sumOf { it.dayPnlKrw }, merged.sumOf { it.dayPnlUsd })} " +
                    "(${pctText(if (dayBase > 0) dayPnl / dayBase * 100 else 0.0)})",
                color = changeColor(dayPnl), fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(10.dp))
        if (showAssetGroups) {
            AssetGroupGauge(balances, merged, money.fx, total)
            Spacer(Modifier.height(8.dp))
        }
        SummaryRow("투자 원금", money.pick(costKrw, costUsd))
        SummaryRow(
            "평가손익",
            "${money.signedPick(pnlKrw, pnlUsd)} (${pctText(if (cost > 0) pnl / cost * 100 else 0.0)})",
            changeColor(pnl)
        )
        SummaryRow("달러 예수금", money.pick(balances.sumOf { it.usdCash } * (money.fx ?: 0.0), balances.sumOf { it.usdCash }))
        SummaryRow("외화 RP 평가", foreignRpSummary(balances, money))
        if (money.isUsd) {
            Text(
                "환율 %,.1f원 기준 · 달러 손익은 환차손익 제외".format(money.fx),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 총자산 카드 안의 자산군 비중 게이지. */
@Composable
private fun AssetGroupGauge(
    balances: List<NamuBalance>,
    holdings: List<MergedHolding>,
    fx: Double?,
    totalKrw: Double
) {
    val stockKrw = holdings.sumOf { it.evalKrw }
    val cashKrw = balances.sumOf { it.krwCash } + balances.sumOf { it.usdCash } * (fx ?: 0.0)
    val rpKrw = balances.sumOf { it.foreignRp.orEmpty().sumOf { rp -> rp.evalKrw } }
    fun percent(value: Double) = if (totalKrw > 0) value / totalKrw * 100 else 0.0
    val groups = listOf(
        Triple("주식", stockKrw, Color(0xFF3E7BFA)),
        Triple("예수금", cashKrw, Color(0xFFF5A524)),
        Triple("외화 RP", rpKrw, Color(0xFF8E4EC6))
    ).filter { it.second > 0 }
    Text("자산군", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(7.dp))
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(99.dp))) {
        groups.forEach { (_, value, color) ->
            Box(Modifier.weight(value.toFloat().coerceAtLeast(0.01f)).fillMaxSize().background(color))
        }
    }
    Spacer(Modifier.height(7.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { (label, value, color) -> AssetGroupItem(label, percent(value), color) }
    }
}

@Composable
private fun RowScope.AssetGroupItem(label: String, percent: Double, color: Color) {
    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text("$label %.1f%%".format(percent), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun SummaryRow(label: String, value: String, color: Color? = null, small: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.weight(1f),
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (small) FontWeight.Normal else FontWeight.Bold,
            color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

/* ② 수익 추이: 날짜별 자산과 원금(현금 포함) */
@Composable
private fun TrendCard(snapshots: List<AssetSnapshot>, usdMode: Boolean = false) {
    var range by remember { mutableStateOf("올해") }
    val byDate = snapshots.groupBy { it.date }.toSortedMap()
        .map { (d, list) -> Triple(d, list.sumOf { it.totalKrw }, list.sumOf { it.principalKrw }) }
    val year = java.time.LocalDate.now().year.toString()
    val points = when (range) {
        "1개월" -> byDate.takeLast(22)
        "올해" -> byDate.filter { it.first.startsWith(year) }
        else -> byDate
    }

    PortfolioCard {
        CardTitle("수익 추이", if (usdMode) "원화 기준" else null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1개월", "올해", "전체").forEach { r ->
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r) })
            }
        }
        Spacer(Modifier.height(10.dp))
        if (points.size < 2) {
            Text("새로고침할 때마다 그날 자산이 기록됩니다. 이틀 이상 쌓이면 그래프가 나옵니다.", style = MaterialTheme.typography.bodySmall)
            return@PortfolioCard
        }
        val assetColor = Color(0xFF3E7BFA)
        val principalColor = Color(0xFF8A94A6)
        val all = points.flatMap { listOf(it.second, it.third) }
        val min = all.min()
        val max = all.max().let { if (it == min) min + 1 else it }
        Canvas(modifier = Modifier.fillMaxWidth().height(92.dp)) {
            fun xy(i: Int, v: Double) = Offset(
                x = size.width * i / (points.size - 1),
                y = (size.height * (1 - (v - min) / (max - min))).toFloat()
            )
            val principal = Path().apply { points.forEachIndexed { i, p -> val o = xy(i, p.third); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            val asset = Path().apply { points.forEachIndexed { i, p -> val o = xy(i, p.second); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(principal, principalColor, style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
            drawPath(asset, assetColor, style = Stroke(width = 6f))
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(points.first().first.drop(5), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(points.last().first.drop(5), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(assetColor, "자산 ${shortKrw(points.last().second)}")
            LegendDot(principalColor, "원금 ${shortKrw(points.last().third)}")
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* ③ 자산 구성: 종목별 비중 히트맵 */
@Composable
private fun CompositionCard(
    balances: List<NamuBalance>, merged: List<MergedHolding>, money: Money,
    extraContent: @Composable () -> Unit = {}
) {
    val total = balances.sumOf(::totalAssetWithRp).takeIf { it > 0 } ?: return
    val top = merged.take(5)
    val etc = merged.drop(5).sumOf { it.evalKrw }
    val cash = balances.sumOf { it.krwCash } + balances.sumOf { it.usdCash } * (money.fx ?: 0.0)
    val rp = balances.sumOf { it.foreignRp.orEmpty().sumOf { holding -> holding.evalKrw } }
    val cells = buildList {
        top.forEachIndexed { i, h -> add(Triple(h.ticker, h.evalKrw, SliceColors[i % SliceColors.size])) }
        if (etc > 0) add(Triple("기타 주식", etc, EtcColor))
        if (cash > 0) add(Triple("예수금", cash, Color(0xFFF5A524)))
        if (rp > 0) add(Triple("외화 RP", rp, Color(0xFF8E4EC6)))
    }

    PortfolioCard {
        CardTitle("자산 구성", "히트맵 · ${money.short(total)}")
        cells.chunked(3).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { (name, value, color) ->
                    Box(
                        Modifier.weight(value.toFloat().coerceAtLeast(0.01f)).fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.72f)).padding(8.dp)
                    ) {
                        Column {
                            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.weight(1f))
                            Text("%.1f%%".format(value / total * 100), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (rowIndex < cells.chunked(3).lastIndex) Spacer(Modifier.height(5.dp))
        }
        extraContent()
    }
}

/*
 * 배당: 받은 배당(해외주식 일별거래내역의 배당 입금, 세후) + 앞으로 받을 배당 추정.
 * 추정 = 가장 최근 주당배당금 × 지금 보유 수량 × 연 지급 횟수(최근 배당 간격으로 추정). 나무 API에 배당 일정이 없어서 과거 지급으로만 계산한다.
 */
@Composable
private fun DividendCard(
    acctNos: List<String>, merged: List<MergedHolding>, money: Money, version: Int,
    graphOnly: Boolean = false, onStockClick: (String) -> Unit = {}
) {
    if (graphOnly) {
        ReceivedDividendGraph(acctNos, money, version)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dividendRevision by remember { mutableIntStateOf(0) }
    var loadingDividends by remember { mutableStateOf(false) }
    var dividendStatus by remember { mutableStateOf("") }
    val rows = remember(version, acctNos) { NamuDividendApi.load(context).filter { it.acctNo in acctNos } }
    val shares = merged.associate { it.ticker.uppercase() to it.quantity }
    val external = remember(version, dividendRevision) { com.example.stocksgnal.data.MassiveDividendApi.load(context) }
    val hasDividendKey = remember(version, dividendRevision) { com.example.stocksgnal.data.MassiveDividendApi.hasKey(context) }
    val pending = remember(version, dividendRevision, shares) {
        com.example.stocksgnal.data.MassiveDividendApi.pendingTickers(context, shares.keys)
    }
    val forecastDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
    val forecasts = remember(rows, shares, external, forecastDate) { NamuDividendApi.forecast(rows, shares, external, forecastDate) }
    var showRows by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    if (showCalendar) {
        DividendCalendarView(rows, forecasts, money, onStockClick) { showCalendar = false }
        return
    }
    val fx = money.fx
    // 받은 배당은 받은 날 환율, 추정은 지금 환율로 원화 환산
    fun usdOrKrw(usd: Double, krwValue: Double? = null): String =
        if (money.isUsd || fx == null) "$%,.2f".format(usd) else krw(krwValue ?: (usd * fx))
    fun receivedKrw(list: List<DividendRow>) = list.sumOf { it.netKrw ?: (it.netUsd * (fx ?: 0.0)) }

    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
    val thisYear = rows.filter { it.date.startsWith("${today.year}-") }
    // 올해 1월부터 12월까지 고정 (세후 달러)
    val months = (1..12).map { java.time.YearMonth.of(today.year, it) }
    val monthly = months.map { m ->
        val key = "%04d-%02d".format(m.year, m.monthValue)
        m.monthValue to rows.filter { it.date.startsWith(key) }.sumOf { it.netUsd }
    }
    val monthMax = monthly.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    val barColor = Color(0xFFF5A524)

    PortfolioCard {
        CardTitle("배당", "받은 금액 · 예상 금액 구분")
        TextButton(onClick = { showCalendar = true }) { Text("배당 캘린더 보기 ›") }
        if (pending.isNotEmpty()) {
            Text(
                if (!hasDividendKey) "배당 일정 키 미설정 · 받은 배당 기록으로만 추정합니다. 설정 › 데이터관리에서 Massive 키를 입력할 수 있습니다."
                else "배당 일정 미조회 ${pending.size}종목: ${pending.joinToString(", ")} · 미조회 종목은 배당 없음으로 판단하지 않습니다.",
                style = MaterialTheme.typography.bodySmall
            )
            if (hasDividendKey) {
                TextButton(enabled = !loadingDividends, onClick = {
                    loadingDividends = true
                    scope.launch {
                        try {
                            val failures = withContext(Dispatchers.IO) {
                                com.example.stocksgnal.data.MassiveDividendApi.refresh(context, pending)
                            }
                            dividendStatus = failures.joinToString(" · ").ifBlank { "남은 종목의 배당 일정을 조회했습니다." }
                            dividendRevision++
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            dividendStatus = "배당 일정 조회 실패: ${e.message.orEmpty()}"
                        } finally {
                            loadingDividends = false
                        }
                    }
                }) { Text(if (loadingDividends) "배당 일정 조회 중…" else "남은 배당 일정 조회") }
            }
        }
        if (dividendStatus.isNotBlank()) Text(dividendStatus, style = MaterialTheme.typography.bodySmall)
        if (thisYear.isEmpty() && forecasts.isEmpty()) {
            Text("올해 받은 배당 기록이 없습니다.", fontWeight = FontWeight.Bold)
            Text(
                "홈에서 새로고침하면 나무증권 해외주식 거래내역에서 올해 배당 입금을 찾아옵니다. " +
                    "설정 › 데이터관리에 Massive 키를 넣으면 배당 일정도 받아 앞으로 받을 배당을 보여줍니다.",
                style = MaterialTheme.typography.bodySmall
            )
            return@PortfolioCard
        }
        SummaryRow("올해 받은 배당", usdOrKrw(thisYear.sumOf { it.netUsd }, receivedKrw(thisYear)))
        if (thisYear.isEmpty()) Text("올해 받은 배당 기록이 없습니다.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        // 월별 막대
        Row(modifier = Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.Bottom) {
            monthly.forEach { (_, v) ->
                Box(Modifier.weight(1f).padding(horizontal = 2.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height((66 * (v / monthMax)).coerceAtLeast(if (v > 0) 3.0 else 1.0).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (v > 0) barColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            monthly.forEach { (m, _) ->
                Text("$m", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text("앞으로 받을 배당", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (forecasts.isEmpty()) {
            Text(
                if (external.isEmpty()) "배당 기록이 있는 보유 종목이 없습니다. (설정에 Massive 키를 넣으면 배당 일정으로 계산합니다)"
                else "보유 종목의 예상 배당을 계산할 자료가 부족합니다. 미조회 종목과 새로고침 오류를 확인해 주세요.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            val annualNet = forecasts.sumOf { it.annualNetUsd }
            SummaryRow("연간 예상 (정기 배당)", usdOrKrw(annualNet))
            SummaryRow("  월 평균", usdOrKrw(annualNet / 12), small = true)
            Spacer(Modifier.height(6.dp))
            forecasts.forEach { f ->
                val cycle = dividendCycle(f.paymentsPerYear)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp), colors = glassCardColors()
                ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.ticker, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            (if (f.declared) "발표 ${f.nextDate.toString().drop(5)} " else "예상 ${f.nextDate.monthValue}월 ") + usdOrKrw(f.nextNetUsd),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "%s · 주당 $%.4f × %,.2f주 · 세율 %.0f%%%s".format(
                            cycle, f.perShareUsd, f.sharesNow, f.taxRate * 100,
                            if (f.declared && f.exDate.isNotBlank()) " · 배당락 ${f.exDate.drop(5)}" else " · 마지막 ${f.lastDate.toString().drop(5)}"
                        ),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                }
            }
            Text(
                "발표 = Massive의 지급 일정. 금액은 현재 수량·추정 세율로 계산한 세후 예상이며, 실제 받을 금액이 확정된 것은 아닙니다. 연간 예상에는 비정기 배당을 포함하지 않습니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { showRows = true }) { Text("받은 배당 내역 보기 (${thisYear.size}건)") }
    }
    if (showRows) DividendRowsDialog(thisYear) { showRows = false }
}

/** 계좌 보유 종목의 받은 배당과 발표·추정 일정을 구분해서 보여준다. */
@Composable
private fun DividendCalendarView(
    received: List<DividendRow>, forecasts: List<DividendForecast>, money: Money,
    onStockClick: (String) -> Unit, onBack: () -> Unit
) {
    val currentYear = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul")).year
    var month by remember(currentYear) { mutableStateOf(java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul"))) }
    var selectedDay by remember(month) { mutableStateOf<Int?>(null) }
    var filter by remember { mutableIntStateOf(0) }
    val events = remember(month, received, forecasts) { dividendCalendarEvents(month, received, forecasts) }
    val filtered = events.filter { event ->
        (selectedDay == null || event.date.dayOfMonth == selectedDay) && when (filter) {
            1 -> event.kind == DividendCalendarKind.RECEIVED
            2 -> event.kind == DividendCalendarKind.ANNOUNCED_EX_DATE || event.kind == DividendCalendarKind.ANNOUNCED_PAYMENT
            3 -> event.kind == DividendCalendarKind.ESTIMATED_PAYMENT
            else -> true
        }
    }
    val firstOffset = month.atDay(1).dayOfWeek.value % 7
    val cells = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7

    PortfolioCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 배당") }
            Text("배당 캘린더", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = month.monthValue > 1, onClick = { month = month.minusMonths(1) }) { Text("‹") }
            Text("${month.year}년 ${month.monthValue}월", modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold)
            TextButton(enabled = month.monthValue < 12, onClick = { month = month.plusMonths(1) }) { Text("›") }
        }
        Row {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { weekday ->
                Text(weekday, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        repeat(cells / 7) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { weekday ->
                    val day = week * 7 + weekday - firstOffset + 1
                    if (day !in 1..month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(56.dp))
                    } else {
                        val mine = events.filter { it.date.dayOfMonth == day }
                        val dotColor = when {
                            mine.any { it.kind == DividendCalendarKind.RECEIVED } -> Color(0xFF12A594)
                            mine.any { it.kind == DividendCalendarKind.ANNOUNCED_EX_DATE || it.kind == DividendCalendarKind.ANNOUNCED_PAYMENT } -> Color(0xFFF5A524)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(
                            modifier = Modifier.weight(1f).height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedDay == day) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable { selectedDay = if (selectedDay == day) null else day },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(day.toString(), style = MaterialTheme.typography.bodySmall)
                            if (mine.isNotEmpty()) Box(Modifier.size(5.dp).clip(CircleShape).background(dotColor))
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("입금" to Color(0xFF12A594), "발표" to Color(0xFFF5A524),
                "추정" to MaterialTheme.colorScheme.onSurfaceVariant).forEach { (label, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("날짜를 누르면 그날 일정만 표시합니다. 추정 날짜와 금액은 확정되지 않았습니다.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("전체", "받음", "발표", "추정").forEachIndexed { index, title ->
            FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(title) })
        }
    }
    Spacer(Modifier.height(8.dp))
    if (filtered.isEmpty()) {
        PortfolioCard { Text("선택한 기간에 표시할 배당 기록·일정이 없습니다.", style = MaterialTheme.typography.bodySmall) }
    }
    filtered.forEach { event ->
        DividendCalendarEventCard(event, money, onStockClick)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DividendCalendarEventCard(event: DividendCalendarEvent, money: Money, onStockClick: (String) -> Unit) {
    val kind = when (event.kind) {
        DividendCalendarKind.RECEIVED -> "받은 배당 · 세후"
        DividendCalendarKind.ANNOUNCED_EX_DATE -> "발표된 배당락일 · 예상 세후"
        DividendCalendarKind.ANNOUNCED_PAYMENT -> "발표된 지급일 · 예상 세후"
        DividendCalendarKind.ESTIMATED_PAYMENT -> "추정 지급일 · 예상 세후"
    }
    Card(onClick = { onStockClick(event.ticker) }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp), colors = glassCardColors(), border = toneBorder(CardTone.Account)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${event.date.monthValue}/${event.date.dayOfMonth} · ${event.ticker}", fontWeight = FontWeight.Bold)
                Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (money.isUsd || money.fx == null) "$%,.2f".format(event.amountUsd)
                else krw(event.amountKrw ?: (event.amountUsd * money.fx)), fontWeight = FontWeight.Bold)
        }
    }
}

/** 받은 배당 원본 (금액 계산 확인용) */
@Composable
private fun DividendRowsDialog(rows: List<DividendRow>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("받은 배당 내역") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "나무증권 해외주식 거래내역에서 적요에 '배당·분배'가 들어간 입금만 모았습니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                rows.sortedByDescending { it.date }.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text("${r.date} · ${r.ticker}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("$%,.2f".format(r.netUsd), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "%s · 수량 %s · 단가 %s · 세전 $%,.2f · 세금 $%,.2f%s".format(
                                r.memo.ifBlank { "-" },
                                r.qty?.let { "%,.2f".format(it) } ?: "-",
                                r.unitPrice?.let { "%.4f".format(it) } ?: "-",
                                r.grossUsd, r.taxUsd,
                                r.netKrw?.let { " · ₩%,.0f".format(it) } ?: ""
                            ),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/* ⑤ 보유 종목 */
@Composable
private fun HoldingCard(h: MergedHolding, money: Money, forecast: DividendForecast?, onStockClick: (String) -> Unit) {
    Card(
        onClick = { onStockClick(h.ticker) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = glassCardColors(),
        border = toneBorder(CardTone.Account)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text(h.ticker.take(3), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(h.name.ifBlank { h.ticker }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(h.ticker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("%,.2f주".format(h.quantity), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldingMetric("평가금액", money.pick(h.evalKrw, h.evalUsd), Modifier.weight(1f))
                HoldingMetric("평균 매수가", "$%,.2f".format(h.avgUsd), Modifier.weight(1f), end = true)
            }
            val pnl = if (money.isUsd) h.pnlUsd else h.pnlKrw
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldingMetric("평가손익", money.signedPick(h.pnlKrw, h.pnlUsd), Modifier.weight(1f),
                    color = changeColor(pnl), note = pctText(if (money.isUsd) h.returnPctUsd else h.returnPct))
                HoldingMetric("현재가", "$%,.2f".format(h.closeUsd), Modifier.weight(1f), end = true,
                    note = h.dayChangePct?.let { "일간 ${pctText(it)}" }, noteColor = h.dayChangePct?.let { changeColor(it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldingMetric("배당 주기", forecast?.let { dividendCycle(it.paymentsPerYear) } ?: "미확인", Modifier.weight(1f))
                val yield = forecast?.takeIf { it.paymentsPerYear > 0 && h.costUsd > 0 }?.let { it.annualGrossUsd / h.costUsd * 100 }
                HoldingMetric("투자 배당률 · 세전 추정", yield?.let { "%.2f%%".format(it) } ?: "미확인", Modifier.weight(1f), end = true,
                    note = forecast?.takeIf { it.paymentsPerYear > 0 }?.let {
                        "연 " + if (money.isUsd || money.fx == null) "$%,.2f".format(it.annualGrossUsd) else money.of(it.annualGrossUsd * money.fx)
                    })
            }
        }
    }
}

@Composable
private fun HoldingMetric(
    label: String, value: String, modifier: Modifier,
    end: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface,
    note: String? = null, noteColor: Color? = null
) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = noteColor ?: color) }
    }
}

private fun dividendCycle(paymentsPerYear: Int): String = when (paymentsPerYear) {
    0 -> "비정기"; 52 -> "주배당"; 24 -> "연 24회"; 12 -> "월배당"; 4 -> "분기배당"
    3 -> "연 3회"; 2 -> "반기배당"; 1 -> "연배당"; else -> "연 ${paymentsPerYear}회"
}

@Composable
private fun CashHoldingCard(balances: List<NamuBalance>, money: Money) {
    PortfolioCard {
        CardTitle("예수금", "나무증권 잔고 기준")
        val krwCash = balances.sumOf { it.krwCash }
        val usdCash = balances.sumOf { it.usdCash }
        SummaryRow("원화 예수금", money.of(krwCash))
        SummaryRow("달러 예수금", money.pick(usdCash * (money.fx ?: 0.0), usdCash))
    }
}

private fun foreignRpSummary(balances: List<NamuBalance>, money: Money): String = when {
    balances.isEmpty() || balances.all { it.foreignRp == null } -> "조회 전·실패"
    balances.any { it.foreignRp == null } -> "${money.of(balances.sumOf { b -> b.foreignRp.orEmpty().sumOf { it.evalKrw } })} · 일부 미조회"
    balances.all { it.foreignRp.isNullOrEmpty() } -> "API 응답에 항목 없음"
    else -> money.of(balances.sumOf { balance -> balance.foreignRp.orEmpty().sumOf { it.evalKrw } })
}

@Composable
private fun ForeignRpHoldingCard(balances: List<NamuBalance>, money: Money) {
    val context = LocalContext.current
    var showDiagnostic by remember { mutableStateOf(false) }
    PortfolioCard {
        CardTitle("외화 RP", "나무 자산현황의 현재 보유분")
        val rows = balances.flatMap { it.foreignRp.orEmpty() }
        when {
            rows.isEmpty() && balances.any { it.foreignRp == null } ->
                Text("외화 RP 잔고 조회 전 또는 조회 실패", style = MaterialTheme.typography.bodySmall)
            rows.isEmpty() ->
                Text("자산현황 API 응답에 외화 RP 항목이 없습니다.", style = MaterialTheme.typography.bodySmall)
            else -> {
                SummaryRow("외화 RP 평가 합계", money.of(rows.sumOf { it.evalKrw }))
                rows.forEach { holding ->
                    SummaryRow(holding.name, money.of(holding.evalKrw), small = true)
                    if (holding.quantity != 0.0) {
                        SummaryRow("잔고 수량", "%,.2f %s".format(holding.quantity,
                            holding.currency.ifBlank { "단위 미제공" }), small = true)
                    }
                }
            }
        }
        if (rows.isNotEmpty() && balances.any { it.foreignRp == null }) {
            Text("일부 계좌 조회 실패 · 조회된 계좌의 금액만 표시합니다.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showDiagnostic = !showDiagnostic }) { Text(if (showDiagnostic) "조회 내역 접기" else "RP 조회 내역 확인") }
        if (showDiagnostic) {
            val labels = NamuAccountApi.linkedAccounts(context).associate { it.acctNo to it.label }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column {
                    balances.forEach { balance ->
                        Text(labels[balance.acctNo].orEmpty(), fontWeight = FontWeight.Bold)
                        Text(NamuAccountApi.foreignRpDiagnostic(context, balance.acctNo), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** 첫 화면은 받은 배당의 월별 그래프만 표시한다. 예상 배당·일정 조회는 상세 화면에서만 한다. */
@Composable
private fun ReceivedDividendGraph(acctNos: List<String>, money: Money, version: Int) {
    PortfolioCard { ReceivedDividendGraphContent(acctNos, money, version) }
}

@Composable
private fun ReceivedDividendGraphContent(acctNos: List<String>, money: Money, version: Int) {
    val context = LocalContext.current
    val year = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).year
    val rows = remember(version, acctNos, year) {
        NamuDividendApi.load(context).filter { it.acctNo in acctNos && it.date.startsWith("$year-") }
    }
    val dollars = money.isUsd || money.fx == null
    var selectedMonth by remember(year, version, acctNos) { mutableIntStateOf(0) }
    val monthly = (1..12).map { month ->
        val key = "%04d-%02d".format(year, month)
        month to rows.filter { it.date.startsWith(key) }.sumOf {
            if (dollars) it.netUsd else it.netKrw ?: (it.netUsd * money.fx!!)
        }
    }
    val max = monthly.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column {
        CardTitle("받은 배당", "${year}년 1~12월 · 세후 ${if (dollars) "USD" else "KRW"}")
        Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.Bottom) {
            monthly.forEachIndexed { index, (_, amount) ->
                Box(
                    Modifier.weight(1f).fillMaxSize().padding(horizontal = 2.dp)
                        .clickable { selectedMonth = index },
                    contentAlignment = Alignment.BottomCenter
                ) {
                Box(Modifier.fillMaxWidth()
                        .height((64 * amount / max).coerceAtLeast(if (amount > 0) 3.0 else 1.0).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (amount > 0) Color(0xFFF5A524) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)))
                }
            }
        }
        val selected = monthly.getOrNull(selectedMonth)
        if (selected != null) {
            Text("${selected.first}월 총 배당: ${if (dollars) "$%,.2f".format(selected.second) else krw(selected.second)}",
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth()) {
            monthly.forEach { (month, _) ->
                Text("$month", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        if (rows.isEmpty()) Text("받은 배당 기록이 없습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

/* 계좌별 독립 카드. 누르면 해당 계좌 탭을 선택하고 맨 위 상세로 이동한다. */
@Composable
private fun AccountSummaryCard(
    account: LinkedNamuAccount, balance: NamuBalance?, index: Int,
    accountsTotal: Double, money: Money, onSelect: () -> Unit
) {
    val color = SliceColors[index % SliceColors.size]
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = glassCardColors(), border = toneBorder(CardTone.Account)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Text((index + 1).toString(), color = color, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.label, fontWeight = FontWeight.Bold)
                    Text(maskAccountNo(account.acctNo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text(balance?.let { money.of(totalAssetWithRp(it)) } ?: "조회 전", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (balance != null) {
                Text("비중 %.1f%%".format(if (accountsTotal > 0) totalAssetWithRp(balance) / accountsTotal * 100 else 0.0),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SummaryRow("주식 평가", money.of(balance.evalKrw), small = true)
                SummaryRow("달러 예수금", money.pick(balance.usdCash * (money.fx ?: 0.0), balance.usdCash), small = true)
                SummaryRow("외화 RP 평가", foreignRpSummary(listOf(balance), money), small = true)
            }
        }
    }
}

@Composable
private fun AddNamuAccountDialog(
    alreadyLinked: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (List<LinkedNamuAccount>) -> Unit
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<NamuAccountInfo>>(emptyList()) }
    val selected = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { NamuAccountApi.listAccounts(context) }
        result.onSuccess { accounts = it }.onFailure { error = it.message ?: "계좌목록을 받지 못했습니다." }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("나무증권 계좌 추가") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text("계좌목록 받는 중…")
                    }
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodySmall)
                    accounts.isEmpty() -> Text("API로 볼 수 있는 계좌가 없습니다.", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text("가져올 계좌를 고르세요.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        accounts.forEach { a ->
                            val linked = a.acctNo in alreadyLinked
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !linked) {
                                    if (a.acctNo in selected) selected.remove(a.acctNo) else selected.add(a.acctNo)
                                }
                            ) {
                                Checkbox(
                                    checked = linked || a.acctNo in selected,
                                    onCheckedChange = null,
                                    enabled = !linked
                                )
                                Column {
                                    Text(maskAccountNo(a.acctNo), fontWeight = FontWeight.Bold)
                                    Text(
                                        a.typeName + if (linked) " · 이미 가져옴" else "",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    onAdd(selected.map { no -> LinkedNamuAccount(no, "나무 " + no.takeLast(4)) })
                }
            ) { Text("가져오기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun RenameNamuAccountDialog(
    account: LinkedNamuAccount,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit
) {
    var label by remember { mutableStateOf(account.label) }
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(maskAccountNo(account.acctNo)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("계좌 이름 (예: 주계좌, ISA)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { if (confirmRemove) onRemove() else confirmRemove = true }) {
                    Text(if (confirmRemove) "한 번 더 누르면 앱에서 뺍니다 (증권사 계좌는 그대로)" else "이 계좌 빼기")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = { onSave(label.trim()) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
