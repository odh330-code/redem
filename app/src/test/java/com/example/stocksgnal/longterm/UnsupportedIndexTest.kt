package com.example.stocksgnal.longterm

import org.junit.Assert.*
import org.junit.Test

class UnsupportedIndexTest {
    private class Store : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
    }

    @Test fun newInstallIncludesFmpSemiconductorStrategy() {
        assertEquals(listOf("QQQ", "DJUSSC"), LongStrategyLedger(Store()).instances().map { it.signalTicker })
    }

    @Test fun restoresStoredStrategyWithoutDeletingOrReusingItsAccount() {
        val store = Store()
        val original = "c10|반도체|DJUSSC|USD|10\nc11|개별주|AAPL|AAPL|11"
        store.putString("instances", original)
        val ledger = LongStrategyLedger(store)
        assertEquals(listOf(StrategyInstance.NASDAQ.id, "c10", "c11"), ledger.instances().map { it.id })
        assertEquals(original, store.getString("instances"))
        val added = ledger.addInstance("새 전략", "MSFT", "MSFT")
        assertEquals(listOf(12), added.accounts)
        assertTrue(store.getString("instances")!!.contains("c10|반도체|DJUSSC|USD|10"))
    }
}
