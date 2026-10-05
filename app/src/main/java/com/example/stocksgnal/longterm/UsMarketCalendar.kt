package com.example.stocksgnal.longterm

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** 미국 증시(NYSE) 정규 휴장일 — 다음 거래일 추정용 (임시 휴장은 반영하지 못함) */
object UsMarketCalendar {

    fun isTradingDay(d: LocalDate): Boolean =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(d)

    fun nextTradingDay(d: LocalDate): LocalDate {
        var x = d.plusDays(1)
        while (!isTradingDay(x)) x = x.plusDays(1)
        return x
    }

    fun addTradingDays(d: LocalDate, n: Int): LocalDate {
        var x = d
        repeat(n) { x = nextTradingDay(x) }
        return x
    }

    /** d 이상인 첫 거래일 */
    fun onOrAfter(d: LocalDate): LocalDate = if (isTradingDay(d)) d else nextTradingDay(d)

    fun isHoliday(d: LocalDate): Boolean {
        val y = d.year
        val fixed = listOf(
            observed(LocalDate.of(y, 1, 1)),
            observed(LocalDate.of(y, 7, 4)),
            observed(LocalDate.of(y, 12, 25))
        ) + (if (y >= 2022) listOf(observed(LocalDate.of(y, 6, 19))) else emptyList())
        if (d in fixed) return true
        return d == nth(y, Month.JANUARY, DayOfWeek.MONDAY, 3) ||
            d == nth(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3) ||
            d == easter(y).minusDays(2) ||
            d == LocalDate.of(y, Month.MAY, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)) ||
            d == nth(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1) ||
            d == nth(y, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)
    }

    private fun observed(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> if (d.month == Month.JANUARY && d.dayOfMonth == 1) d else d.minusDays(1)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    private fun nth(y: Int, m: Month, dow: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(y, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))

    /** 부활절 (그레고리력, Anonymous 알고리즘) */
    private fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(y, month, day)
    }
}
