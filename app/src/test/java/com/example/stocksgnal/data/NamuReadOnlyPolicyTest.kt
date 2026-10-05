package com.example.stocksgnal.data

import org.junit.Assert.*
import org.junit.Test

class NamuReadOnlyPolicyTest {
    @Test fun orderOperationsCannotUseTheRetryingReadClient() {
        listOf("buy", "sell", "correct", "cancel", "reservation").forEach {
            assertFalse(isNamuReadOnlyPath("/gbstock/order/v1/$it"))
        }
        assertFalse(isNamuReadOnlyPath("/oauth2/token"))
        assertFalse(isNamuReadOnlyPath("/common/inquiry/v1/totalTransaction"))
    }
    @Test fun knownReadOperationsRemainAvailable() {
        listOf("/n2/acctinfo", "/gbstock/inquiry/v1/balance", "/krstock/inquiry/v1/assetStatus",
            "/gbstock/inquiry/v1/dailyTransaction", "/gbstock/inquiry/v1/unexecuted",
            "/gbstock/inquiry/v1/periodPnl", "/gbstock/inquiry/v1/periodPnlDetail",
            "/gbstock/quote/v1/current", "/gbstock/quote/v1/period",
            "/gbstock/quote/v1/symbolIndexFxPeriod", "/gbfuture/quote/v1/current").forEach {
            assertTrue(isNamuReadOnlyPath(it))
        }
    }
    @Test fun alternateHostsAndPathTraversalDoNotBypassThePolicy() {
        listOf("https://example.com/n2/acctinfo", "//example.com/n2/acctinfo",
            "/gbstock/quote/v1/../order/v1/buy", "/n2/acctinfo?redirect=1",
            "/gbstock/quote/v1/%2e%2e/order/v1/buy").forEach {
            assertFalse(isNamuReadOnlyPath(it))
        }
    }
}
