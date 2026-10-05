package com.example.stocksgnal.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FmpIndexApiTest {
    private val from = LocalDate.parse("2024-01-01")
    private val to = LocalDate.parse("2024-01-31")
    private val sample = """[
        {"symbol":"^DJUSSC","date":"2024-01-03","open":110,"high":115,"low":105,"close":112,"volume":100},
        {"symbol":"^DJUSSC","date":"2024-01-02","open":100,"high":110,"low":95,"close":108,"volume":90}
    ]"""

    @Test fun readsFmpSchemaChronologicallyWithoutReplacingCloseWithAdjustedValue() {
        val rows = FmpIndexApi.parse(sample, from, to)
        assertEquals(listOf("2024-01-02", "2024-01-03"), rows.map { it.date })
        assertEquals(108.0, rows.first().close, 0.0)
    }

    @Test fun rejectsWrongIndexAndErrorPayload() {
        assertTrue(runCatching { FmpIndexApi.parse(sample.replace("^DJUSSC", "^DJI"), from, to) }.isFailure)
        assertTrue(runCatching { FmpIndexApi.parse("""{"Error Message":"Invalid API KEY"}""", from, to) }.isFailure)
        assertTrue(runCatching { FmpIndexApi.parse(sample.replace("\"close\":112", "\"close\":0"), from, to) }.isFailure)
    }

    @Test fun splitsLongHistoryIntoNonOverlappingWindowsBelowRecordLimit() {
        val windows = FmpIndexApi.windows(LocalDate.parse("2000-01-01"), LocalDate.parse("2026-10-03"))
        assertEquals(3, windows.size)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.second.plusDays(1), b.first) }
        assertTrue(windows.all { java.time.temporal.ChronoUnit.DAYS.between(it.first, it.second) < 5000 })
    }

    @Test fun permissionFailureIsNotMisreportedAsUnsupportedTicker() {
        assertTrue(FmpIndexApi.statusMessage(403).contains("권한"))
        assertTrue(FmpIndexApi.statusMessage(401).contains("인증"))
        assertTrue(FmpIndexApi.statusMessage(429).contains("한도"))
    }
}
