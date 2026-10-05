package com.example.stocksgnal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DividendCalendarTest {
    private fun row(date: String) = DividendRow("account", date, 1, "ABC", "ABC", "배당", 10.0,
        0.2, 2.0, 0.3, 1.7, 1300.0)

    @Test fun receivedAndAnnouncedExDateAndPayDateKeepTheirMeanings() {
        val forecast = DividendForecast("ABC", 10.0, 0.2, 4,
            LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-16"), 0.15,
            declared = true, exDate = "2026-10-08")
        val events = dividendCalendarEvents(YearMonth.of(2026, 10), listOf(row("2026-10-02")), listOf(forecast))
        assertEquals(listOf(2, 8, 16), events.map { it.date.dayOfMonth })
        assertEquals(listOf(DividendCalendarKind.RECEIVED, DividendCalendarKind.ANNOUNCED_EX_DATE,
            DividendCalendarKind.ANNOUNCED_PAYMENT), events.map { it.kind })
        assertEquals(1.7, events.first().amountUsd, 0.0)
        assertEquals(2210.0, events.first().amountKrw!!, 0.0)
    }

    @Test fun weeklyProjectionShowsDistinctEstimatesAndNoInventedSpecialSchedule() {
        val weekly = DividendForecast("WEEK", 2.0, 0.5, 52,
            LocalDate.parse("2026-09-26"), LocalDate.parse("2026-10-03"), 0.15)
        val special = weekly.copy(ticker = "SPECIAL", paymentsPerYear = 0)
        val events = dividendCalendarEvents(YearMonth.of(2026, 10), emptyList(), listOf(weekly, special))
        assertEquals(listOf(3, 10, 17, 24, 31), events.map { it.date.dayOfMonth })
        assertTrue(events.all { it.kind == DividendCalendarKind.ESTIMATED_PAYMENT && it.ticker == "WEEK" })
        assertFalse(events.any { it.ticker == "SPECIAL" })
    }

}
