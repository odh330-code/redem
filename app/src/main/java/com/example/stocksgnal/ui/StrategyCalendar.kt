package com.example.stocksgnal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.longterm.FillKind
import com.example.stocksgnal.longterm.UsMarketCalendar
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import java.time.LocalDate
import java.time.YearMonth

/*
 * 캘린더: 장기·단기 전략에서 언제 무엇을 사고파는지 한눈에.
 * 알림은 이 일정 데이터를 그대로 쓰도록 다음 단계에서 연결한다.
 */

enum class CalendarEventKind(val label: String) {
    BUY("매수 예정"),
    SELL("매도 예정"),
    SHORT("단기 후보"),
    DONE("체결 기록"),
    HOLIDAY("휴장")
}

data class CalendarEvent(
    val date: LocalDate,
    val kind: CalendarEventKind,
    val title: String,
    val amountKrw: Double? = null
)

/** 전략 데이터 → 날짜별 일정 */
fun buildCalendarEvents(data: StrategyData, month: YearMonth): List<CalendarEvent> {
    val events = mutableListOf<CalendarEvent>()
    val m = data.primaryMarket

    data.plans.forEach { (inst, plan) ->
        plan?.accounts?.toSortedMap()?.forEach { (acct, v) ->
            val who = "${inst.accountName(acct)}(${inst.tradeTicker})"
            // 분할 일정 (다음 거래일 포함)
            v.schedule.forEach { s ->
                events.add(CalendarEvent(s.date, CalendarEventKind.BUY, "$who · ${s.title}", s.amountKrw))
            }
            // 1-3 · 매도 주문 (다음 거래일)
            v.orders.filter { it.kind != FillKind.CAMPAIGN }.forEach { o ->
                val kind = if (o.kind == FillKind.SELL) CalendarEventKind.SELL else CalendarEventKind.BUY
                events.add(CalendarEvent(plan.market.nextTradingDay, kind, "$who · ${o.title}", o.amountKrw))
            }
        }
    }

    // 단기전략 진입 후보 (다음 거래일)
    if (m != null) {
        data.short.entries().forEach { (row, s) ->
            events.add(CalendarEvent(m.nextTradingDay, CalendarEventKind.SHORT, "${row.ticker} · ${s.type.label} ${s.stage.label}"))
        }
    }

    // 체결 기록
    data.fills.forEach { f ->
        val inst = data.plans.map { it.first }.firstOrNull { f.account in it.accounts }
        val who = inst?.let { "${it.accountName(f.account)}(${it.tradeTicker})" } ?: "${f.account}번 계좌"
        events.add(CalendarEvent(f.date, CalendarEventKind.DONE, "$who · ${f.kind.label}", f.amountKrw))
    }

    // 미국 휴장일
    var d = month.atDay(1)
    while (!d.isAfter(month.atEndOfMonth())) {
        if (d.dayOfWeek.value <= 5 && UsMarketCalendar.isHoliday(d)) {
            events.add(CalendarEvent(d, CalendarEventKind.HOLIDAY, "미국 증시 휴장"))
        }
        d = d.plusDays(1)
    }
    return events
}

@Composable
private fun kindColor(kind: CalendarEventKind): Color = when (kind) {
    CalendarEventKind.BUY -> MaterialTheme.colorScheme.primary
    CalendarEventKind.SELL -> MaterialTheme.colorScheme.error
    CalendarEventKind.SHORT -> MaterialTheme.colorScheme.tertiary
    CalendarEventKind.DONE -> MaterialTheme.colorScheme.secondary
    CalendarEventKind.HOLIDAY -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun StrategyCalendarTab(data: StrategyData?) {
    if (data == null) {
        Column(modifier = Modifier.padding(StockDesign.ScreenPadding)) { CircularProgressIndicator() }
        return
    }
    val start = data.primaryMarket?.nextTradingDay ?: LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(start)) }
    var selected by remember { mutableStateOf(start) }

    val events = remember(data, month) { buildCalendarEvents(data, month) }
    val byDate = events.groupBy { it.date }
    val dayEvents = byDate[selected].orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Text("캘린더", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("전략별 매수·매도 예정일과 체결 기록", style = MaterialTheme.typography.bodyMedium)
        }

        item {
            CalendarCard(CardTone.Schedule) {
                // 월 이동
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹ 이전", fontWeight = FontWeight.Bold) }
                    Text(
                        text = "${month.year}년 ${month.monthValue}월",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("다음 ›", fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(8.dp))

                // 요일
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, name ->
                        Text(
                            text = name,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (i == 0 || i == 6) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                // 날짜 격자 (일요일 시작)
                val first = month.atDay(1)
                val offset = first.dayOfWeek.value % 7
                val cells = offset + month.lengthOfMonth()
                val weeks = (cells + 6) / 7
                for (w in 0 until weeks) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (dow in 0 until 7) {
                            val dayNum = w * 7 + dow - offset + 1
                            Box(modifier = Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) {
                                if (dayNum in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(dayNum)
                                    DayCell(
                                        date = date,
                                        isSelected = date == selected,
                                        isToday = date == LocalDate.now(),
                                        kinds = byDate[date].orEmpty().map { it.kind }.distinct(),
                                        onClick = { selected = date }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 범례
                CalendarEventKind.entries.chunked(3).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                        line.forEach { k ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(7.dp).background(kindColor(k), CircleShape))
                                Spacer(Modifier.width(4.dp))
                                Text(k.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        item {
            CalendarCard(CardTone.Action) {
                Text(
                    text = "${selected.monthValue}월 ${selected.dayOfMonth}일 일정",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                if (dayEvents.isEmpty()) {
                    Text("일정이 없습니다.", style = MaterialTheme.typography.bodySmall)
                }
                dayEvents.forEach { e ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(9.dp).background(kindColor(e.kind), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(e.title, fontWeight = FontWeight.Bold)
                            Text(e.kind.label, style = MaterialTheme.typography.bodySmall)
                        }
                        e.amountKrw?.let { Text("%,.0f원".format(it), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        item {
            Text(
                text = "예정 일정은 오늘 종가 기준 계산입니다. 반등 정지·다음 캠페인·1-3 발동 시 바뀔 수 있습니다. 알림 연결은 다음 단계에서 추가합니다.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    kinds: List<CalendarEventKind>,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .size(46.dp)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val weekend = date.dayOfWeek.value >= 6
        Text(
            text = "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isToday -> MaterialTheme.colorScheme.primary
                weekend -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            kinds.take(4).forEach { k ->
                Box(modifier = Modifier.size(5.dp).background(kindColor(k), CircleShape))
            }
        }
    }
}

@Composable
private fun CalendarCard(tone: Color, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StockDesign.CardRadius),
        colors = glassCardColors(),
        border = toneBorder(tone)
    ) {
        Column(modifier = Modifier.padding(StockDesign.CardPadding)) { content() }
    }
}
