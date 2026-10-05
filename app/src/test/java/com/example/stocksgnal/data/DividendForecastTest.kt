package com.example.stocksgnal.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DividendForecastTest {
    private val today = LocalDate.of(2026, 10, 2)
    private fun dividend(
        amount: Double = 0.5,
        frequency: Int = 4,
        ex: String = "2026-09-15",
        pay: String = "2026-09-30",
        type: String = "recurring",
        adjusted: Double? = null
    ) = ExtDividend("TEST", ex, pay, "2026-09-01", amount, frequency, type, adjusted)

    private fun forecast(vararg events: ExtDividend) = NamuDividendApi.forecast(
        emptyList(), mapOf("TEST" to 10.0), mapOf("TEST" to events.toList()), today
    )

    @Test fun unqueriedHoldingsRemainPendingAfterFirstBatch() {
        val holdings = listOf("A", "B", "C", "D", "E", "F")
        // A completed lookup counts even when that ticker returned no dividend rows.
        val queried = holdings.take(4).associateWith { "2026-10-02" }
        assertEquals(listOf("E", "F"), MassiveDividendApi.pendingTickers(holdings, queried))
        assertTrue(MassiveDividendApi.pendingTickers(holdings, holdings.associateWith { "2026-10-02" }).isEmpty())
    }

    @Test fun weeklyDividendIsNotAnnualizedAsQuarterly() {
        val f = forecast(dividend(frequency = 52, pay = "2026-10-09", ex = "2026-10-05")).single()
        assertEquals(52, f.paymentsPerYear)
        assertEquals(260.0, f.annualGrossUsd, 0.00001)
        assertEquals(LocalDate.of(2026, 10, 9), f.nextDate)
    }

    @Test fun exDateWithoutPayDateIsNotPresentedAsDeclaredPayment() {
        assertTrue(forecast(dividend(ex = "2026-10-05", pay = "")).isEmpty())
    }

    @Test fun splitAdjustedHistoricalDividendIsUsedForCurrentShares() {
        val f = forecast(dividend(amount = 4.0, adjusted = 1.0)).single()
        assertFalse(f.declared)
        assertEquals(1.0, f.perShareUsd, 0.00001)
        assertEquals(40.0, f.annualGrossUsd, 0.00001)
    }

    @Test fun specialPaymentIsShownOnceAndNotMultipliedByFour() {
        val f = forecast(dividend(frequency = 0, type = "special", pay = "2026-10-15")).single()
        assertTrue(f.declared)
        assertEquals(5.0, f.nextGrossUsd, 0.00001)
        assertEquals(0.0, f.annualGrossUsd, 0.00001)
    }

    @Test fun historicalSpecialPaymentDoesNotCreateRecurringForecast() {
        assertTrue(forecast(dividend(frequency = 0, type = "special")).isEmpty())
    }

    @Test fun monthlyForecastAdvancesByCalendarMonth() {
        val f = forecast(dividend(frequency = 12)).single()
        assertEquals(LocalDate.of(2026, 10, 30), f.nextDate)
    }
}
