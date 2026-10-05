package com.example.stocksgnal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import com.example.stocksgnal.longterm.AccountSettings
import com.example.stocksgnal.longterm.AccountHolding
import com.example.stocksgnal.longterm.AccountView
import com.example.stocksgnal.longterm.EngineMode
import com.example.stocksgnal.longterm.FillKind
import com.example.stocksgnal.longterm.LedgerFill
import com.example.stocksgnal.longterm.LongStrategyPlanner
import com.example.stocksgnal.longterm.LongStrategyRepository
import com.example.stocksgnal.longterm.MarketView
import com.example.stocksgnal.longterm.OrderView
import com.example.stocksgnal.longterm.PlannerResult
import com.example.stocksgnal.longterm.PrefsStore
import com.example.stocksgnal.longterm.LongStrategyLedger
import com.example.stocksgnal.longterm.StrategyInstance
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/* =========================
   공통: 상태 보관
   ========================= */

private class LongStrategyUiState(val repository: LongStrategyRepository) {
    var results by mutableStateOf<List<Pair<StrategyInstance, PlannerResult?>>>(emptyList())
    var loading by mutableStateOf(true)
    var version by mutableIntStateOf(0)

    fun instances(): List<StrategyInstance> = results.map { it.first }.ifEmpty { listOf(StrategyInstance.NASDAQ) }
    fun resultFor(id: String): PlannerResult? = results.firstOrNull { it.first.id == id }?.second
}

@Composable
private fun rememberLongStrategyState(): LongStrategyUiState {
    val context = LocalContext.current
    val state = remember { LongStrategyUiState(LongStrategyRepository(context.applicationContext)) }
    LaunchedEffect(state.version) {
        state.loading = true
        state.results = withContext(Dispatchers.Default) { runCatching { state.repository.computeAll() }.getOrDefault(emptyList()) }
        state.loading = false
    }
    return state
}

/** 이 종목이 계곡다이빙의 매수 종목이면 true (티커 화면에 [실행] 탭을 붙일지 판단) */
fun isLongTradeTicker(context: android.content.Context, ticker: String): Boolean =
    LongStrategyLedger(PrefsStore(context.applicationContext)).instanceForTrade(ticker) != null

@Composable
private fun InstanceChips(instances: List<StrategyInstance>, selectedId: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        instances.forEach { inst ->
            FilterChip(
                selected = inst.id == selectedId,
                onClick = { onSelect(inst.id) },
                label = { Text("${inst.name} · ${inst.tradeTicker}" + if (inst.validated) "" else " (미검증)") }
            )
        }
    }
}

@Composable
private fun UnvalidatedNote(inst: StrategyInstance) {
    if (inst.validated) return
    SectionCard(CardTone.Warning) {
        Text("미검증 전략", fontWeight = FontWeight.Bold)
        Text(
            "규칙 수치(-5% 단위 캠페인, RSI 40, 매도 -3%, 252일 고점)는 QQQ·QLD로만 검증했습니다. ${inst.signalTicker}→${inst.tradeTicker}에는 백테스트 전까지 참고용으로만 쓰세요.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun won(v: Double) = "%,.0f원".format(v)
private fun pct(v: Double) = "%+.2f%%".format(v)

@Composable
private fun SectionCard(tone: Color = CardTone.Data, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StockDesign.CardRadius),
        colors = glassCardColors(),
        border = toneBorder(tone)
    ) {
        Column(modifier = Modifier.padding(StockDesign.CardPadding)) { content() }
    }
}

@Composable
private fun InfoRow(label: String, value: String, strong: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium, color = valueColor)
    }
}

/* =========================
   신호 탭 · 계곡다이빙
   ========================= */

@Composable
fun LongStrategySignalScreen() {
    val state = rememberLongStrategyState()
    var selectedId by remember { mutableStateOf(StrategyInstance.NASDAQ.id) }
    val inst = state.instances().firstOrNull { it.id == selectedId } ?: StrategyInstance.NASDAQ
    val result = state.resultFor(inst.id)
    var showDetails by rememberSaveable(inst.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Text("${inst.signalTicker}로 판단 · ${inst.tradeTicker} 매매", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("종가 기준 신호 · 다음 거래일 시가 체결", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result == null) {
            item {
                SectionCard(CardTone.Warning) {
                    Text("현재 단계: 매수 1-1 (임시 표시)", fontWeight = FontWeight.Bold)
                    Text("실제 데이터가 연결되면 현재 단계로 바뀝니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { InstanceChips(state.instances(), inst.id) { selectedId = it } }
        if (!inst.validated) item { UnvalidatedNote(inst) }
        item {
            Text(
                text = result?.market?.let { "${it.date} 종가 기준 · 데이터 갱신은 홈에서 합니다" } ?: "데이터 갱신은 홈에서 합니다",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (state.loading && result == null) {
            item { CircularProgressIndicator() }
        } else if (result == null) {
            item {
                SectionCard(CardTone.Data) {
                    Text("${inst.signalTicker} 데이터가 부족합니다.", fontWeight = FontWeight.Bold)
                    Text("홈에서 [새로고침]을 눌러 ${inst.signalTicker} 과거 데이터를 받아 주세요. (설정에서 API Key 필요)", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            val m = result.market
            item { TodoSummaryCard(m, result, inst) }
            item { MarketStatusCard(m, showDetails) }
            item {
                TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showDetails) "전략 지표·조건 접기 ∧" else "전략 지표·조건 펼치기 ∨")
                }
            }
            if (showDetails && m.mode == EngineMode.BUY) {
                item { CampaignLadderCard(m) }
                item { RecoveryCard(m, result.accounts[inst.accounts.first()], inst.accountName(inst.accounts.first())) }
            }
            if ((showDetails && m.mode == EngineMode.SELL_WAIT) || m.sellSignalToday) item { SellCard(m) }
            item { HistoryCard(result) }
        }
    }
}

@Composable
private fun MarketStatusCard(m: MarketView, showDetails: Boolean) {
    SectionCard(CardTone.Strategy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.stage.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${m.date} 종가 기준", style = MaterialTheme.typography.bodySmall)
        }
        if (m.stageDetail.isNotBlank()) {
            Text(m.stageDetail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        InfoRow("${m.signalTicker} 종가", "%.2f".format(m.qqqClose), strong = true)
        InfoRow("고점 대비", pct(m.drawdownPct), strong = true, valueColor = com.example.stocksgnal.ui.theme.changeColor(m.drawdownPct))
        if (showDetails) {
        val highLabel = when (m.mode) {
            EngineMode.WAIT -> "직전 252일 최고 종가 (H252)"
            EngineMode.BUY -> "기준 고점 H (고정)"
            EngineMode.SELL_WAIT -> "이전 구간 H"
        }
        InfoRow(highLabel, "%.2f".format(m.referenceHigh))
        InfoRow("RSI(14)", "%.1f".format(m.rsi14))
        InfoRow("60일 이동평균", "%.2f".format(m.ma60))
        }
        Spacer(Modifier.height(6.dp))
        val hint = when (m.mode) {
            EngineMode.WAIT -> "${m.signalTicker} 종가 %.2f 이하(-5%%)면 매수 1이 시작됩니다.".format(m.referenceHigh * 0.95)
            EngineMode.BUY -> "구간 시작 ${m.campaignStart} · 최대 낙폭 %.2f%% · ${m.signalTicker} 종가 %.2f 이상이면 구간 종료 후 매도 2 대기".format(m.maxDrawdownPct, m.referenceHigh)
            EngineMode.SELL_WAIT -> "H 회복 후 보유 중. 최고 종가 M 대비 -%.1f%%에서 매도 신호".format(m.sellDropPct)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CampaignLadderCard(m: MarketView) {
    SectionCard(CardTone.Ladder) {
        Text("캠페인 단계", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        m.campaignPrices.forEach { (n, price) ->
            val reached = n <= m.reachedCampaign
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (reached) "●" else "○", color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(LongStrategyPlanner.campaignName(n), modifier = Modifier.weight(1f), fontWeight = if (reached) FontWeight.Bold else FontWeight.Normal)
                Text("${m.signalTicker} %.2f".format(price), style = MaterialTheme.typography.bodyMedium)
            }
        }
        m.campaign1State?.let {
            Spacer(Modifier.height(6.dp))
            val wait = m.waitDays?.let { d -> " · 대기 ${d}거래일째 (RSI ≤ 40 / -10% / 5일 경과 중 먼저 오는 조건에서 시작)" } ?: ""
            Text("캠페인1 상태: ${it.label}$wait", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RecoveryCard(m: MarketView, account: AccountView?, accountName: String) {
    SectionCard(CardTone.Recovery) {
        Text("1-3 상승 확인 매수 조건", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        m.recoveryConditions.forEach { c ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(if (c.passed) "✓" else "·", color = if (c.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.label, fontWeight = if (c.passed) FontWeight.Bold else FontWeight.Normal)
                    Text(c.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        account?.let {
            Spacer(Modifier.height(6.dp))
            Text("$accountName 1-3 집행: ${it.rounds13}/5회 · ${won(it.spent13Krw)} / ${won(it.total13Krw)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SellCard(m: MarketView) {
    SectionCard(CardTone.Sell) {
        Text("매도 2", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        m.sellPeak?.let { InfoRow("H 회복 후 최고 종가 M", "%.2f".format(it)) }
        m.sellTrigger?.let {
            InfoRow("매도 기준가 (M × %.3f)".format(1 - m.sellDropPct / 100), "%.2f".format(it), strong = true)
            InfoRow("현재 종가 대비", pct((it / m.qqqClose - 1) * 100))
        }
        if (m.sellSignalToday) {
            Spacer(Modifier.height(6.dp))
            Text("오늘 매도 신호 발생 → 다음 거래일(${m.nextTradingDay}) 시가 매도", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TodoSummaryCard(m: MarketView, result: PlannerResult, inst: StrategyInstance) {
    SectionCard(CardTone.Action) {
        Text("다음 거래일 할 일 · ${m.nextTradingDay}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        result.accounts.toSortedMap().forEach { (acct, v) ->
            Text(inst.accountName(acct), fontWeight = FontWeight.Bold)
            if (v.orders.isEmpty()) Text("주문 없음", style = MaterialTheme.typography.bodySmall)
            v.orders.forEach { o -> Text("• ${o.title} · ${won(o.amountKrw)}${if (o.recorded) " (기록됨)" else ""}", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(6.dp))
        }
        Text("체결 기록은 ${inst.tradeTicker}를 눌러 [실행] 탭에서, 계좌 설정은 하단 [계좌] 메뉴에서 합니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryCard(result: PlannerResult) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(CardTone.History) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("지난 구간 (${result.history.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "보기") }
        }
        if (expanded) {
            result.history.reversed().take(15).forEach { h ->
                Text(
                    "${h.start} ~ ${h.end ?: "진행 중"} · 최대 -%.1f%% · 캠페인${h.reachedCampaign}${if (h.spent13Pp > 0) " · 1-3" else ""}${h.sellSignal?.let { " · 매도 $it" } ?: ""}".format(h.maxDrawdownPct),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Text("과거 구간은 규칙대로 체결했다고 가정한 계산입니다.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* =========================
   실행 탭 · 계좌별 주문 · 체결 기록
   ========================= */

private data class FillDraft(val kind: FillKind, val amountKrw: Double, val shares: Double?)

/** 예전 이름 호환 */
@Composable
fun QldExecutionPanel() = ExecutionPanel("QLD")

/** 매수 종목 티커 화면의 [실행] 탭. 스크롤 화면 안에 들어가므로 LazyColumn을 쓰지 않는다. */
@Composable
fun ExecutionPanel(ticker: String) {
    val state = rememberLongStrategyState()
    val inst = state.instances().firstOrNull { it.tradeTicker.equals(ticker, ignoreCase = true) } ?: StrategyInstance.NASDAQ
    val result = state.resultFor(inst.id)
    var account by remember(inst.id) { mutableIntStateOf(inst.accounts.first()) }
    var draft by remember { mutableStateOf<FillDraft?>(null) }
    val repo = state.repository

    Column(verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)) {
        Text("${inst.name} 계곡다이빙 · ${inst.signalTicker} 판정 → ${inst.tradeTicker} 매수", fontWeight = FontWeight.Bold)
        if (inst.accounts.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                inst.accounts.forEach { a ->
                    FilterChip(selected = account == a, onClick = { account = a }, label = { Text(inst.accountName(a)) })
                }
            }
        }
        UnvalidatedNote(inst)

        val view = result?.accounts?.get(account)
        if (result != null && view != null) {
            OrdersCard(result.market, view, onRecord = { draft = FillDraft(it.kind, it.amountKrw, it.approxShares) })
            if (view.notes.isNotEmpty()) NotesCard(view.notes)
            val campaignStart = view.campaignStart
            if (campaignStart != null) BudgetCard(view, onRescale = { repo.ledger.clearScale(account, campaignStart); state.version++ })
            if (view.schedule.isNotEmpty()) ScheduleCard(view)
        } else if (state.loading) {
            CircularProgressIndicator()
        } else {
            SectionCard(CardTone.Data) { Text("데이터가 없습니다. 홈에서 새로고침해 주세요.", fontWeight = FontWeight.Bold) }
        }

        OutlinedButton(onClick = { draft = FillDraft(FillKind.CAMPAIGN, 0.0, null) }, modifier = Modifier.fillMaxWidth()) {
            Text("체결 직접 기록")
        }

        // 전략 예산·예수금 (수동 입력). 실제 증권사 잔고는 하단 [계좌] 메뉴에서 확인
        AccountSettingsCard(repo, inst, account, state.version, onSaved = { state.version++ })
        val fills = remember(state.version, account) { repo.ledger.fills().filter { it.account == account } }
        FillHistoryCard(fills, onDelete = { repo.ledger.deleteFill(it); state.version++ })
    }

    draft?.let { d ->
        RecordFillDialog(
            draft = d,
            defaultDate = result?.market?.nextTradingDay ?: LocalDate.now(),
            onDismiss = { draft = null },
            onSave = { date, kind, amount, shares, price ->
                repo.ledger.addFill(account, date, kind, amount, shares, price)
                draft = null
                state.version++
            }
        )
    }
}

/* =========================
   계좌 메뉴: 계좌 요약 · 계좌 설정 · 체결 기록 · 공통 설정
   ========================= */

@Composable
fun AccountScreen(resetKey: Int = 0, dataRevision: Int = 0, onStockClick: (String) -> Unit = {}) {
    // 계좌 메뉴 = 나무증권 계좌 한 화면 (조회 전용). 전략 예산·체결 기록은 전략 › 종목 › [실행]으로 옮김.
    NamuAccountsScreen(onStockClick = onStockClick, dataRevision = dataRevision)
}

@Composable
private fun AccountDetailScreen(
    summary: AccountCardSummary,
    fills: List<LedgerFill>,
    onBack: () -> Unit,
    onDeleteFill: (Long) -> Unit,
    onAddHolding: (String, Double, Double) -> Unit,
    onAddToHolding: (Long, Double, Double) -> Unit,
    onReplaceHolding: (Long, Double, Double) -> Unit,
    onDeleteHolding: (Long) -> Unit,
    onStockClick: (String) -> Unit,
    symbolList: List<HoldingSymbol>,
    onSave: (String, Double, Double, Double, Double, String) -> Unit
) {
    var tab by remember(summary.settings.account) { mutableStateOf("자산") }
    var holdingsTab by remember(summary.settings.account) { mutableStateOf("자산") }
    var showEdit by remember { mutableStateOf(false) }
    var showAddHolding by remember { mutableStateOf(false) }
    var holdingEditMode by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("<") }
                Text(summary.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { showEdit = true }) { Text("편집") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("자산", "배당", "추이", "뉴스").forEach { name ->
                    Text(
                        text = name,
                        color = if (tab == name) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (tab == name) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { tab = name }
                    )
                }
            }
        }
        if (tab != "자산") {
            item {
                SectionCard(CardTone.Data) {
                    Text(tab, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("이 영역은 다음 단계에서 붙이면 됩니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            item { AccountDetailHeroCard(summary) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        listOf("자산", "매매일지").forEach { name ->
                            FilterChip(selected = holdingsTab == name, onClick = { holdingsTab = name }, label = { Text(name) })
                        }
                    }
                    if (holdingsTab == "자산") TextButton(onClick = { showAddHolding = true }) {
                        Text("+", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
                    }
                    if (holdingsTab == "자산") TextButton(onClick = { holdingEditMode = !holdingEditMode }) {
                        Text(if (holdingEditMode) "완료" else "편집")
                    }
                }
            }
            if (holdingsTab == "자산") {
                item {
                    HoldingListCard(
                        summary = summary,
                        editMode = holdingEditMode,
                        onStockClick = onStockClick,
                        onAddToHolding = onAddToHolding,
                        onReplaceHolding = onReplaceHolding,
                        onDeleteHolding = onDeleteHolding
                    )
                }
            } else {
                item { FillHistoryCard(fills, onDelete = onDeleteFill) }
            }
        }
    }

    if (showEdit) {
        AccountEditDialog(
            summary = summary,
            onDismiss = { showEdit = false },
            onSave = { label, base, cash, shares, avgPrice, logo ->
                onSave(label, base, cash, shares, avgPrice, logo)
                showEdit = false
            }
        )
    }

    if (showAddHolding) {
        AddHoldingDialog(
            symbolList = symbolList,
            onDismiss = { showAddHolding = false },
            onSave = { ticker, shares, avgPrice ->
                onAddHolding(ticker, shares, avgPrice)
                showAddHolding = false
            }
        )
    }
}

private data class AccountCardSummary(
    val inst: StrategyInstance,
    val settings: AccountSettings,
    val displayName: String,
    val logoUrl: String,
    val lastPriceUsd: Double?,
    val usdKrw: Double?,
    val holdings: List<AccountHolding>,
    val holdingPricesUsd: Map<String, Double?>
) {
    val stockCostKrw: Double
        get() = holdings.sumOf { it.shares * it.avgPriceUsd * (usdKrw ?: 0.0) }.takeIf { it > 0 }
            ?: if (settings.avgPriceUsd > 0 && usdKrw != null) settings.qldShares * settings.avgPriceUsd * usdKrw else 0.0
    val stockValueKrw: Double
        get() = holdings.sumOf { h -> h.shares * (holdingPricesUsd[h.ticker] ?: h.avgPriceUsd) * (usdKrw ?: 0.0) }.takeIf { it > 0 }
            ?: if (lastPriceUsd != null && usdKrw != null) settings.qldShares * lastPriceUsd * usdKrw else 0.0
    val holdingValueKrw: Double?
        get() = stockValueKrw.takeIf { it > 0 }
    val costKrw: Double?
        get() = stockCostKrw.takeIf { it > 0 }
    val totalAssetKrw: Double
        get() = settings.cashKrw + (holdingValueKrw ?: costKrw ?: 0.0)
    val profitPct: Double?
        get() {
            val cost = costKrw ?: return null
            val value = holdingValueKrw ?: return null
            return if (cost <= 0) null else (value / cost - 1) * 100
        }
}

private data class HoldingSymbol(val symbol: String, val name: String)

private fun defaultAccountSymbols(): List<HoldingSymbol> = listOf(
    HoldingSymbol("KRW", "원화 현금"),
    HoldingSymbol("USD_CASH", "USD($) 달러 현금"),
    HoldingSymbol("RP", "RP 현금성 자산"),
    HoldingSymbol("QQQ", "Invesco QQQ Trust"),
    HoldingSymbol("QLD", "ProShares Ultra QQQ"),
    HoldingSymbol("USD", "ProShares Ultra Semiconductors"),
    HoldingSymbol("SPY", "SPDR S&P 500 ETF"),
    HoldingSymbol("VOO", "Vanguard S&P 500 ETF"),
    HoldingSymbol("SCHD", "Schwab US Dividend Equity ETF"),
    HoldingSymbol("JEPI", "JPMorgan Equity Premium Income ETF"),
    HoldingSymbol("JEPQ", "JPMorgan Nasdaq Equity Premium Income ETF"),
    HoldingSymbol("NVDA", "NVIDIA"),
    HoldingSymbol("AAPL", "Apple"),
    HoldingSymbol("MSFT", "Microsoft"),
    HoldingSymbol("GOOGL", "Alphabet"),
    HoldingSymbol("TSLA", "Tesla")
)

private fun loadAccountSymbolList(context: android.content.Context): List<HoldingSymbol> {
    val cashSymbols = listOf(
        HoldingSymbol("KRW", "원화 현금"),
        HoldingSymbol("USD_CASH", "USD($) 달러 현금"),
        HoldingSymbol("RP", "RP 현금성 자산")
    )
    val marketSymbols = try {
        context.assets.open("us_symbols.csv").bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { parseAccountSymbolLine(it) }.toList()
        }
    } catch (_: Exception) {
        defaultAccountSymbols().drop(3)
    }
    return (cashSymbols + marketSymbols).distinctBy { it.symbol }
}

private fun parseAccountSymbolLine(line: String): HoldingSymbol? {
    val columns = mutableListOf<String>()
    val current = StringBuilder()
    var insideQuotes = false
    var i = 0
    while (i < line.length) {
        val char = line[i]
        when {
            char == '"' -> {
                if (insideQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    insideQuotes = !insideQuotes
                }
            }
            char == ',' && !insideQuotes -> {
                columns.add(current.toString())
                current.clear()
            }
            else -> current.append(char)
        }
        i++
    }
    columns.add(current.toString())
    if (columns.size < 2) return null
    val symbol = columns[0].trim()
    val name = columns[1].trim()
    return if (symbol.isBlank() || name.isBlank()) null else HoldingSymbol(symbol, name)
}

@Composable
private fun AccountHeroCard(summaries: List<AccountCardSummary>) {
    val totalAssets = summaries.sumOf { it.totalAssetKrw }
    val ammo = summaries.sumOf { it.settings.cashKrw }
    val stockValue = summaries.sumOf { it.holdingValueKrw ?: it.costKrw ?: 0.0 }
    val gaugeTotal = (ammo + stockValue).takeIf { it > 0 } ?: 1.0

    SectionCard(CardTone.Account) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("총자산", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(won(totalAssets), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Text("총계좌 ${summaries.size}개", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        CompositionBar(
            firstWeight = (stockValue / gaugeTotal).toFloat().coerceIn(0f, 1f),
            firstColor = Color(0xFF2D7DD2),
            secondColor = Color(0xFFF6B33D)
        )
        Spacer(Modifier.height(8.dp))
        val stockPct = stockValue / gaugeTotal * 100
        val ammoPct = ammo / gaugeTotal * 100
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "주식 ${won(stockValue)} · %.1f%%".format(stockPct),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            Text("탄약 ${won(ammo)} · %.1f%%".format(ammoPct), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AssetCompositionCard(summaries: List<AccountCardSummary>) {
    val cash = summaries.sumOf { it.settings.cashKrw }
    val holdings = summaries.sumOf { it.holdingValueKrw ?: it.costKrw ?: 0.0 }
    val total = (cash + holdings).takeIf { it > 0 } ?: 1.0
    SectionCard(CardTone.Data) {
        Text("자산 구성", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(86.dp).clip(CircleShape).background(Color(0xFF26324A)),
                contentAlignment = Alignment.Center
            ) {
                Text("구성", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                InfoRow("주식", "${won(holdings)} · %.1f%%".format(holdings / total * 100))
                InfoRow("탄약", "${won(cash)} · %.1f%%".format(cash / total * 100))
            }
        }
    }
}

@Composable
private fun CompositionBar(firstWeight: Float, firstColor: Color, secondColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
        Box(modifier = Modifier.weight(firstWeight.coerceAtLeast(0.001f)).fillMaxSize().background(firstColor))
        Box(modifier = Modifier.weight((1f - firstWeight).coerceAtLeast(0.001f)).fillMaxSize().background(secondColor))
    }
}

@Composable
private fun AccountListRow(summary: AccountCardSummary, onClick: () -> Unit) {
    SectionCard(CardTone.Account) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccountIcon(summary.settings.account)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(summary.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(won(summary.totalAssetKrw), style = MaterialTheme.typography.bodyLarge)
            }
            Text(">", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AccountIcon(account: Int) {
    val colors = listOf(Color(0xFF2D7DD2), Color(0xFFF6B33D), Color(0xFF0F9F9A), Color(0xFFC45B66), Color(0xFF6E7FDB))
    val color = colors[(account - 1).mod(colors.size)]
    Box(
        modifier = Modifier.size(46.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Text(account.toString(), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AccountDetailHeroCard(summary: AccountCardSummary) {
    val profitKrw = summary.holdingValueKrw?.let { value -> summary.costKrw?.let { value - it } }
    val profitPct = summary.profitPct
    SectionCard(CardTone.Account) {
        Text("총 자산", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(won(summary.totalAssetKrw), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = if (profitKrw != null && profitPct != null) "총 수익 ${won(profitKrw)} (${pct(profitPct)})" else "수익은 평단가와 현재가가 필요합니다.",
            color = com.example.stocksgnal.ui.theme.changeColor(profitKrw)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("예수금", style = MaterialTheme.typography.labelLarge)
                Text(won(summary.settings.cashKrw), fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("종목 평가", style = MaterialTheme.typography.labelLarge)
                Text(won(summary.stockValueKrw), fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("수익률", style = MaterialTheme.typography.labelLarge)
                Text(summary.profitPct?.let { pct(it) } ?: "-", fontWeight = FontWeight.Bold, color = com.example.stocksgnal.ui.theme.changeColor(summary.profitPct))
            }
        }
    }
}

@Composable
private fun SingleAccountCompositionCard(summary: AccountCardSummary) {
    val cash = summary.settings.cashKrw
    val holding = summary.holdingValueKrw ?: summary.costKrw ?: 0.0
    val total = (cash + holding).takeIf { it > 0 } ?: 1.0
    SectionCard(CardTone.Data) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자산 구성", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(summary.inst.tradeTicker, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        CompositionBar(
            firstWeight = (cash / total).toFloat().coerceIn(0f, 1f),
            firstColor = Color(0xFFF6B33D),
            secondColor = Color(0xFF0F9F9A)
        )
        Spacer(Modifier.height(8.dp))
        InfoRow("현금", "${won(cash)} · %.1f%%".format(cash / total * 100))
        InfoRow(summary.inst.tradeTicker, "${won(holding)} · %.1f%%".format(holding / total * 100))
    }
}

@Composable
private fun HoldingListCard(
    summary: AccountCardSummary,
    editMode: Boolean,
    onStockClick: (String) -> Unit,
    onAddToHolding: (Long, Double, Double) -> Unit,
    onReplaceHolding: (Long, Double, Double) -> Unit,
    onDeleteHolding: (Long) -> Unit
) {
    var editingHolding by remember { mutableStateOf<AccountHolding?>(null) }
    var holdingDialogMode by remember { mutableStateOf<HoldingEditMode?>(null) }

    SectionCard(CardTone.History) {
        Text("보유 종목", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        HoldingRow(
            logoText = "₩",
            name = "KRW",
            type = "현금",
            quantity = summary.settings.cashKrw,
            value = summary.settings.cashKrw,
            buyPrice = 0.0,
                currentPrice = null,
                profitPct = null,
                onClick = null,
                editActions = null
        )
        val rows = summary.holdings.ifEmpty {
            if (summary.settings.qldShares > 0) {
                listOf(AccountHolding(0L, summary.settings.account, summary.inst.tradeTicker, summary.settings.qldShares, summary.settings.avgPriceUsd))
            } else emptyList()
        }
        rows.forEach { holding ->
            val current = summary.holdingPricesUsd[holding.ticker] ?: if (holding.ticker == summary.inst.tradeTicker) summary.lastPriceUsd else null
            val value = holding.shares * (current ?: holding.avgPriceUsd) * (summary.usdKrw ?: 0.0)
            val profitPct = current?.let { if (holding.avgPriceUsd > 0) (it / holding.avgPriceUsd - 1) * 100 else null }
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            HoldingRow(
                logoText = holdingDisplayName(holding.ticker).take(3),
                name = holdingDisplayName(holding.ticker),
                type = holdingType(holding.ticker),
                quantity = holding.shares,
                value = value,
                buyPrice = holding.avgPriceUsd,
                currentPrice = current,
                profitPct = profitPct,
                onClick = if (holdingType(holding.ticker) == "종목") { { onStockClick(holding.ticker) } } else null,
                editActions = if (editMode && holding.id > 0) {
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { editingHolding = holding; holdingDialogMode = HoldingEditMode.ADD }) { Text("추가") }
                            TextButton(onClick = { editingHolding = holding; holdingDialogMode = HoldingEditMode.REPLACE }) { Text("신규") }
                            TextButton(onClick = { onDeleteHolding(holding.id) }) { Text("삭제") }
                        }
                    }
                } else null
            )
        }
    }

    editingHolding?.let { holding ->
        if (holdingDialogMode != null) {
            HoldingEditDialog(
                holding = holding,
                mode = holdingDialogMode!!,
                onDismiss = { holdingDialogMode = null; editingHolding = null },
                onSaveAdd = { shares, totalUsd ->
                    onAddToHolding(holding.id, shares, totalUsd)
                    holdingDialogMode = null
                    editingHolding = null
                },
                onSaveReplace = { shares, avgPrice ->
                    onReplaceHolding(holding.id, shares, avgPrice)
                    holdingDialogMode = null
                    editingHolding = null
                }
            )
        }
    }
}

private fun holdingDisplayName(ticker: String): String = when (ticker.uppercase()) {
    "USD_CASH" -> "USD($)"
    else -> ticker.uppercase()
}

private fun holdingType(ticker: String): String = when (ticker.uppercase()) {
    "USD_CASH" -> "달러 현금"
    "RP" -> "현금성 자산"
    else -> "종목"
}

private enum class HoldingEditMode { ADD, REPLACE }

@Composable
private fun HoldingEditDialog(
    holding: AccountHolding,
    mode: HoldingEditMode,
    onDismiss: () -> Unit,
    onSaveAdd: (Double, Double) -> Unit,
    onSaveReplace: (Double, Double) -> Unit
) {
    var shares by remember(holding.id, mode) { mutableStateOf(if (mode == HoldingEditMode.REPLACE) holding.shares.plain() else "") }
    var amountOrAvg by remember(holding.id, mode) { mutableStateOf(if (mode == HoldingEditMode.REPLACE) holding.avgPriceUsd.plain() else "") }
    val title = if (mode == HoldingEditMode.ADD) "${holding.ticker} 추가" else "${holding.ticker} 신규"
    val secondLabel = if (mode == HoldingEditMode.ADD) "추가 매수금액 (달러)" else "평단가 (달러)"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("수량", shares, { shares = it })
                NumberField(secondLabel, amountOrAvg, { amountOrAvg = it })
                Text(
                    if (mode == HoldingEditMode.ADD) "기존 보유분과 합쳐 평균단가를 다시 계산합니다." else "기존 값을 지우고 새 수량과 평단가로 저장합니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (shares.toDoubleOrNull() ?: 0.0) > 0,
                onClick = {
                    val s = shares.toDoubleOrNull() ?: 0.0
                    val v = amountOrAvg.toDoubleOrNull() ?: 0.0
                    if (mode == HoldingEditMode.ADD) onSaveAdd(s, v) else onSaveReplace(s, v)
                }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HoldingRow(
    logoText: String,
    name: String,
    type: String,
    quantity: Double,
    value: Double,
    buyPrice: Double,
    currentPrice: Double?,
    profitPct: Double?,
    onClick: (() -> Unit)?,
    editActions: (@Composable (() -> Unit))?
) {
    val badgeColors = listOf(
        Color(0xFF2D7DD2),
        Color(0xFFF6B33D),
        Color(0xFF0F9F9A),
        Color(0xFFC45B66),
        Color(0xFF6E7FDB),
        Color(0xFF6A994E),
        Color(0xFFE76F51),
        Color(0xFF6D597A),
        Color(0xFF118AB2),
        Color(0xFFB56576)
    )
    val badgeColor = if (name == "KRW") Color(0xFFF6B33D) else badgeColors[kotlin.math.abs(name.hashCode()) % badgeColors.size]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(badgeColor.copy(alpha = 0.24f)),
                contentAlignment = Alignment.Center
            ) { Text(logoText.uppercase(), color = badgeColor, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                Text(type, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("수량 %,.4f".format(quantity), style = MaterialTheme.typography.bodySmall)
            }
        }
        editActions?.let {
            Spacer(Modifier.height(4.dp))
            it()
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("자산가치 ${won(value)}", style = MaterialTheme.typography.bodySmall)
            Text("수익 ${profitPct?.let { pct(it) } ?: "-"}", style = MaterialTheme.typography.bodySmall, color = com.example.stocksgnal.ui.theme.changeColor(profitPct))
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
            Text("구매가 ${if (buyPrice > 0) "$%.2f".format(buyPrice) else "-"}", style = MaterialTheme.typography.bodySmall)
            Text("현재가 ${currentPrice?.let { "$%.2f".format(it) } ?: "-"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AccountTotalSummaryCard(summaries: List<AccountCardSummary>) {
    val totalAssets = summaries.sumOf { it.totalAssetKrw }
    val ammo = summaries.sumOf { it.settings.cashKrw }
    SectionCard(CardTone.Account) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("총자산", style = MaterialTheme.typography.labelLarge)
                Text(won(totalAssets), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("탄약", style = MaterialTheme.typography.labelLarge)
                Text(won(ammo), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        Text("탄약은 전략용 예수금 합산입니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccountAssetCard(
    summary: AccountCardSummary,
    selected: Boolean,
    onSelect: () -> Unit,
    onSave: (String, Double, Double, Double, Double, String) -> Unit
) {
    val account = summary.settings.account
    var label by remember(account, summary.displayName) { mutableStateOf(summary.displayName) }
    var base by remember(account, summary.settings.baseKrw) { mutableStateOf(summary.settings.baseKrw.plain()) }
    var cash by remember(account, summary.settings.cashKrw) { mutableStateOf(summary.settings.cashKrw.plain()) }
    var shares by remember(account, summary.settings.qldShares) { mutableStateOf(summary.settings.qldShares.plain()) }
    var avgPrice by remember(account, summary.settings.avgPriceUsd) { mutableStateOf(summary.settings.avgPriceUsd.plain()) }
    var logo by remember(account, summary.logoUrl) { mutableStateOf(summary.logoUrl) }

    SectionCard(if (selected) CardTone.Action else CardTone.Account) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TickerBadge(summary.inst.tradeTicker)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label.ifBlank { summary.inst.accountName(account) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${summary.inst.tradeTicker} 보유 · ${summary.inst.signalTicker} 판정", style = MaterialTheme.typography.bodySmall)
            }
            FilterChip(selected = selected, onClick = onSelect, label = { Text(if (selected) "선택됨" else "선택") })
        }
        Spacer(Modifier.height(10.dp))
        InfoRow("총자산", won(summary.totalAssetKrw), strong = true)
        InfoRow("예수금", won(summary.settings.cashKrw))
        InfoRow("보유 ${summary.inst.tradeTicker}", "%,.4f주".format(summary.settings.qldShares))
        InfoRow("평단가", if (summary.settings.avgPriceUsd > 0) "$%.2f".format(summary.settings.avgPriceUsd) else "미입력")
        InfoRow("수익률", summary.profitPct?.let { pct(it) } ?: "가격/평단 필요", strong = summary.profitPct != null, valueColor = com.example.stocksgnal.ui.theme.changeColor(summary.profitPct))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("계좌 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NumberField(if (account == 1) "총자산 기준금액 (원)" else "전략 예산금액 (원)", base, { base = it })
        NumberField("예수금 (원)", cash, { cash = it })
        NumberField("보유 ${summary.inst.tradeTicker} 수량", shares, { shares = it })
        NumberField("평단가 (달러)", avgPrice, { avgPrice = it })
        OutlinedTextField(value = logo, onValueChange = { logo = it.trim() }, label = { Text("티커 로고 URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                onSave(
                    label,
                    base.toDoubleOrNull() ?: 0.0,
                    cash.toDoubleOrNull() ?: 0.0,
                    shares.toDoubleOrNull() ?: 0.0,
                    avgPrice.toDoubleOrNull() ?: 0.0,
                    logo
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("저장") }
    }
}

@Composable
private fun AccountEditDialog(
    summary: AccountCardSummary,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, Double, Double, String) -> Unit
) {
    val account = summary.settings.account
    var label by remember(account, summary.displayName) { mutableStateOf(summary.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${summary.displayName} 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("계좌 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("보유종목은 자산 탭에서 추가하고, 종목을 오래 눌러 수정합니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label,
                        summary.settings.baseKrw,
                        summary.settings.cashKrw,
                        summary.settings.qldShares,
                        summary.settings.avgPriceUsd,
                        summary.logoUrl
                    )
                }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun AddHoldingDialog(symbolList: List<HoldingSymbol>, onDismiss: () -> Unit, onSave: (String, Double, Double) -> Unit) {
    var ticker by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf("") }
    var avgPrice by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf("") }
    val cleanQuery = ticker.trim().uppercase()
    val searchResults = remember(cleanQuery, selectedName, symbolList) {
        if (cleanQuery.length < 1 || selectedName.isNotBlank()) {
            emptyList()
        } else {
            val symbolMatches = symbolList.filter { it.symbol.startsWith(cleanQuery, ignoreCase = true) }
            val nameMatches = symbolList.filter {
                !it.symbol.startsWith(cleanQuery, ignoreCase = true) && it.name.contains(cleanQuery, ignoreCase = true)
            }
            (symbolMatches + nameMatches).distinctBy { it.symbol }.take(8)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("종목 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = ticker,
                    onValueChange = {
                        ticker = it.uppercase().trim()
                        selectedName = ""
                    },
                    label = { Text("티커") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                searchResults.forEach { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = glassCardColors(),
                        shape = RoundedCornerShape(StockDesign.CardRadius),
                        onClick = {
                            ticker = result.symbol.trim().uppercase()
                            selectedName = result.name
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TickerBadge(holdingDisplayName(result.symbol))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(holdingDisplayName(result.symbol), fontWeight = FontWeight.Bold)
                                Text(result.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (ticker.isNotBlank()) {
                    Text(
                        text = if (selectedName.isBlank()) "선택됨: ${ticker.uppercase()}" else "선택됨: ${ticker.uppercase()} · $selectedName",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                NumberField("수량", shares, { shares = it })
                NumberField("평단가 (달러)", avgPrice, { avgPrice = it })
                Text("현재가는 새로고침으로 받은 가격과 비교해 수익률을 계산합니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = ticker.isNotBlank() && (shares.toDoubleOrNull() ?: 0.0) > 0,
                onClick = { onSave(ticker, shares.toDoubleOrNull() ?: 0.0, avgPrice.toDoubleOrNull() ?: 0.0) }
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun TickerBadge(ticker: String) {
    val palette = listOf(
        Color(0xFF2D7DD2),
        Color(0xFFF6B33D),
        Color(0xFF0F9F9A),
        Color(0xFFC45B66),
        Color(0xFF6E7FDB),
        Color(0xFF6A994E),
        Color(0xFFE76F51),
        Color(0xFF6D597A),
        Color(0xFF118AB2),
        Color(0xFFB56576)
    )
    val color = palette[kotlin.math.abs(ticker.hashCode()) % palette.size]
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.24f)),
        contentAlignment = Alignment.Center
    ) {
        Text(ticker.take(3).uppercase(), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("계좌 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("계좌 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("계좌를 만든 뒤 상세 화면에서 예수금과 보유 종목을 채우면 됩니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, "CASH") }) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** 계곡다이빙 추가·수정·삭제 (나스닥은 고정) */
@Composable
fun InstanceManagerCard(repo: LongStrategyRepository, instances: List<StrategyInstance>, onChanged: () -> Unit) {
    var ticker by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }

    SectionCard(CardTone.Strategy) {
        Text("다이빙 종목", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("여기에 등록한 종목만 계곡다이빙이 추적합니다. 전략 메뉴의 관심목록은 일반 관심종목이며 매수·매도 신호와 연결하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        instances.forEach { inst ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${inst.name} · ${inst.signalTicker} → ${inst.tradeTicker}", fontWeight = FontWeight.Bold)
                    Text(if (inst.validated) "검증됨 · 총자산 기준 / 직접입력 예산" else "미검증 · ${inst.accountName(inst.accounts.first())}", style = MaterialTheme.typography.bodySmall)
                }
                if (!inst.validated) {
                    TextButton(onClick = {
                        editingId = inst.id; ticker = inst.tradeTicker
                    }) { Text("수정") }
                    TextButton(onClick = { repo.ledger.removeInstance(inst.id); onChanged() }) { Text("삭제") }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(if (editingId == null) "다이빙 종목 추가" else "다이빙 종목 수정", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = ticker,
            onValueChange = { ticker = it.uppercase().trim() },
            label = { Text("티커 검색 (예: QQQ, USD)") },
            supportingText = { Text("티커 하나만 입력하면 계곡다이빙 추적 종목으로 추가됩니다.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = ticker.isNotBlank(),
                onClick = {
                    val id = editingId
                    if (id == null) repo.ledger.addInstance(ticker, ticker, ticker) else repo.ledger.updateInstance(id, ticker, ticker, ticker)
                    ticker = ""; editingId = null
                    onChanged()
                }
            ) { Text(if (editingId == null) "추가" else "저장") }
            if (editingId != null) TextButton(onClick = { editingId = null; ticker = "" }) { Text("취소") }
        }
        Text("추가한 뒤 홈에서 새로고침하면 이 다이빙 종목만 데이터를 받습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OrdersCard(m: MarketView, v: AccountView, onRecord: (OrderView) -> Unit) {
    SectionCard(CardTone.Action) {
        Text("다음 거래일 할 일 · ${m.nextTradingDay}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${m.date} 종가 기준 · ${m.stage.label}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (v.orders.isEmpty()) {
            Text("주문 없음", fontWeight = FontWeight.Bold)
            Text(
                when (m.mode) {
                    EngineMode.WAIT -> "${m.signalTicker}가 252일 고점 대비 -5%에 닿으면 매수 1이 시작됩니다."
                    EngineMode.BUY -> "진행 중인 분할 일정이나 1-3 신호가 없습니다. 조건 변화는 [전략 › 계곡다이빙]에서 확인하세요."
                    EngineMode.SELL_WAIT -> "보유를 유지하고 매도 2 신호를 기다립니다."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        v.orders.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(o.title, fontWeight = FontWeight.Bold, color = if (o.kind == FillKind.SELL) MaterialTheme.colorScheme.error else Color.Unspecified)
                    Text(won(o.amountKrw) + (o.approxShares?.let { " · 약 %,.2f주".format(it) } ?: ""), style = MaterialTheme.typography.titleMedium)
                    Text(o.detail, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                if (o.recorded) Text("기록됨", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                else OutlinedButton(onClick = { onRecord(o) }) { Text("체결 기록") }
            }
        }
    }
}

@Composable
private fun NotesCard(notes: List<String>) {
    SectionCard(CardTone.Warning) {
        notes.forEach { Text("※ $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun BudgetCard(v: AccountView, onRescale: () -> Unit) {
    SectionCard(CardTone.Budget) {
        Text("이번 캠페인 예산 · ${v.campaignStart} 시작", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        InfoRow("예산 배율 f", v.scale?.let { "%.0f%%".format(it * 100) } ?: "-")
        InfoRow("1-1/1-2 매수", "${won(v.spent12Krw)} / 도달 목표 ${won(v.target12Krw)}", strong = true)
        LinearProgressIndicator(
            progress = { if (v.total12Krw > 0) (v.spent12Krw / v.total12Krw).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        Text("전체 1-1/1-2 예산 ${won(v.total12Krw)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        InfoRow("1-3 매수", "${won(v.spent13Krw)} / ${won(v.total13Krw)} · ${v.rounds13}/5회", strong = true)
        LinearProgressIndicator(
            progress = { if (v.total13Krw > 0) (v.spent13Krw / v.total13Krw).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRescale) { Text("예산 배율을 현재 예수금으로 다시 확정") }
        Text("캠페인 시작 뒤 입금·매도 체결을 반영할 때만 사용하세요.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScheduleCard(v: AccountView) {
    SectionCard(CardTone.Schedule) {
        Text("남은 분할 일정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        v.schedule.take(10).forEach { s -> InfoRow("${s.date} · ${s.title}", won(s.amountKrw)) }
        Text("반등 정지·다음 캠페인·1-3 발동 시 일정이 바뀝니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supporting?.let { s -> @Composable { Text(s) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

private fun Double.plain(): String = if (this == 0.0) "" else if (this % 1.0 == 0.0) "%.0f".format(this) else this.toString()

@Composable
private fun AccountSettingsCard(repo: LongStrategyRepository, inst: StrategyInstance, account: Int, version: Int, onSaved: () -> Unit) {
    val saved = remember(account, version) { repo.ledger.account(account) }
    var base by remember(account, version) { mutableStateOf(saved.baseKrw.plain()) }
    var cash by remember(account, version) { mutableStateOf(saved.cashKrw.plain()) }
    var shares by remember(account, version) { mutableStateOf(saved.qldShares.plain()) }
    var sellAll by remember(account, version) { mutableStateOf(saved.sellAll) }
    val fixedSellMode = inst.id == StrategyInstance.NASDAQ.id

    SectionCard(CardTone.Account) {
        Text("${inst.accountName(account)} 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (account == 1) {
            NumberField("총자산 기준금액 (원)", base, { base = it }, "매수 예산 = 25% · 리밸런싱 때만 변경")
        } else {
            NumberField("매수 1 예산 금액 (원)", base, { base = it }, "이 금액을 매수 1 예산 전체(25%)로 봅니다")
        }
        NumberField(if (account == 1) "매수용 예수금 (원, 고정 5% 제외)" else "예수금 (원)", cash, { cash = it }, "체결 기록 시 자동 반영 · 배당 등은 직접 수정")
        NumberField("보유 ${inst.tradeTicker} 수량 (주)", shares, { shares = it })
        if (!fixedSellMode) {
            Text("매도 2 방식", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !sellAll, onClick = { sellAll = false }, label = { Text("부족 예수금만큼") })
                FilterChip(selected = sellAll, onClick = { sellAll = true }, label = { Text("캠페인 매수분 전량") })
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                repo.ledger.saveAccount(
                    AccountSettings(account, base.toDoubleOrNull() ?: 0.0, cash.toDoubleOrNull() ?: 0.0, shares.toDoubleOrNull() ?: 0.0,
                        sellAll = if (fixedSellMode) account != 1 else sellAll)
                )
                onSaved()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("저장") }
    }
}

@Composable
fun CommonSettingsCard(repo: LongStrategyRepository, version: Int, onSaved: () -> Unit) {
    val snap = remember(version) { repo.ledger.snapshot() }
    var recordStart by remember(version) { mutableStateOf(snap.recordStart.toString()) }
    var drop by remember(version) { mutableStateOf(snap.sellDropPct.toString()) }
    var fx by remember(version) { mutableStateOf(repo.usdKrwManual()?.toString() ?: "") }
    val dateOk = runCatching { LocalDate.parse(recordStart) }.isSuccess

    SectionCard(CardTone.Settings) {
        Text("공통 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = recordStart,
            onValueChange = { recordStart = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("체결 기록 시작일 (yyyy-MM-dd)") },
            supportingText = { Text("이 날짜 이전 매수는 규칙대로 체결된 것으로 계산합니다") },
            isError = !dateOk,
            singleLine = true
        )
        NumberField("매도 2 기준 하락폭 (%)", drop, { drop = it }, "기본 3% (명세 기준값)")
        NumberField("환율 직접 입력 (원/달러)", fx, { fx = it }, "비우면 갱신 때 받은 환율 사용 · 현재 ${repo.usdKrw()?.let { "%,.1f".format(it) } ?: "없음"}")
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = dateOk,
            onClick = {
                repo.ledger.setRecordStart(LocalDate.parse(recordStart))
                drop.toDoubleOrNull()?.takeIf { it > 0 && it < 50 }?.let { repo.ledger.setSellDropPct(it) }
                repo.setUsdKrwManual(fx.toDoubleOrNull()?.takeIf { it > 0 })
                onSaved()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("저장") }
    }
}

@Composable
private fun FillHistoryCard(fills: List<LedgerFill>, onDelete: (Long) -> Unit) {
    SectionCard(CardTone.History) {
        Text("체결 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (fills.isEmpty()) Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodySmall)
        fills.reversed().take(30).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${f.date} · ${f.kind.label}", fontWeight = FontWeight.Bold)
                    Text(won(f.amountKrw) + (if (f.shares > 0) " · %,.2f주".format(f.shares) else "") + (if (f.price > 0) " · $%.2f".format(f.price) else ""), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onDelete(f.id) }) { Text("삭제") }
            }
        }
    }
}

@Composable
private fun RecordFillDialog(
    draft: FillDraft,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (LocalDate, FillKind, Double, Double, Double) -> Unit
) {
    var kind by remember { mutableStateOf(draft.kind) }
    var date by remember { mutableStateOf(defaultDate.toString()) }
    var amount by remember { mutableStateOf(if (draft.amountKrw > 0) "%.0f".format(draft.amountKrw) else "") }
    var shares by remember { mutableStateOf(draft.shares?.let { "%.2f".format(it) } ?: "") }
    var price by remember { mutableStateOf("") }
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val parsedAmount = amount.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("체결 기록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FillKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = {
                            Text(when (k) { FillKind.CAMPAIGN -> "1-1/1-2"; FillKind.RECOVERY -> "1-3"; FillKind.SELL -> "매도" })
                        })
                    }
                }
                OutlinedTextField(
                    value = date, onValueChange = { date = it.trim() },
                    label = { Text("체결일 (미국 거래일)") }, isError = parsedDate == null, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                NumberField("체결 금액 (원)", amount, { amount = it })
                NumberField("수량 (주, 선택)", shares, { shares = it })
                NumberField("체결가 (달러, 선택)", price, { price = it })
                Text("저장하면 예수금과 보유 수량이 자동으로 바뀝니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedDate != null && parsedAmount != null && parsedAmount > 0,
                onClick = { onSave(parsedDate!!, kind, parsedAmount!!, shares.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: 0.0) }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
