package com.example.stocksgnal.data

import java.time.LocalDate
import java.time.YearMonth

enum class DividendCalendarKind { RECEIVED, ANNOUNCED_EX_DATE, ANNOUNCED_PAYMENT, ESTIMATED_PAYMENT }

data class DividendCalendarEvent(
    val date: LocalDate,
    val ticker: String,
    val kind: DividendCalendarKind,
    val amountUsd: Double,
    val amountKrw: Double? = null
)

/** Saved broker deposits and held-stock schedules only. Estimated dates are explicitly marked. */
fun dividendCalendarEvents(
    month: YearMonth,
    received: List<DividendRow>,
    forecasts: List<DividendForecast>
): List<DividendCalendarEvent> {
    val start = month.atDay(1)
    val end = month.atEndOfMonth()
    val events = mutableListOf<DividendCalendarEvent>()
    received.forEach { row ->
        val date = runCatching { LocalDate.parse(row.date) }.getOrNull()
        if (date != null && date in start..end) {
            events += DividendCalendarEvent(date, row.ticker, DividendCalendarKind.RECEIVED, row.netUsd, row.netKrw)
        }
    }
    forecasts.forEach { forecast ->
        if (forecast.declared) {
            val exDate = runCatching { LocalDate.parse(forecast.exDate) }.getOrNull()
            if (exDate != null && exDate in start..end) {
                events += DividendCalendarEvent(exDate, forecast.ticker, DividendCalendarKind.ANNOUNCED_EX_DATE, forecast.nextNetUsd)
            }
            if (forecast.nextDate in start..end) {
                events += DividendCalendarEvent(forecast.nextDate, forecast.ticker, DividendCalendarKind.ANNOUNCED_PAYMENT, forecast.nextNetUsd)
            }
        } else if (forecast.paymentsPerYear > 0) {
            var date = forecast.nextDate
            var occurrences = 0
            while (date <= end && occurrences < 400) {
                if (date >= start) {
                    events += DividendCalendarEvent(date, forecast.ticker, DividendCalendarKind.ESTIMATED_PAYMENT, forecast.nextNetUsd)
                }
                date = advanceDividendDate(date, forecast.paymentsPerYear)
                occurrences++
            }
        }
    }
    return events.sortedWith(compareBy(DividendCalendarEvent::date, DividendCalendarEvent::ticker, DividendCalendarEvent::kind))
}

private fun advanceDividendDate(date: LocalDate, paymentsPerYear: Int): LocalDate = when (paymentsPerYear) {
    1, 2, 3, 4, 12 -> date.plusMonths(12L / paymentsPerYear)
    52, 104 -> date.plusDays(364L / paymentsPerYear)
    else -> date.plusDays((365L / paymentsPerYear).coerceAtLeast(1))
}
