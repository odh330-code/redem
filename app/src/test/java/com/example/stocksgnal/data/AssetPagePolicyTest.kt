package com.example.stocksgnal.data

import org.junit.Assert.*
import org.junit.Test

class AssetPagePolicyTest {
    @Test fun userReported00218AdvancesUntilFinalPage() {
        val policy = AssetPagePolicy()
        assertEquals("page-2", policy.accept("00218", "계속조회시 다음(연속조회)버튼을 누르시기바랍니다", true, "page-2"))
        assertEquals("page-3", policy.accept("00218", "계속조회", true, "page-3"))
        assertNull(policy.accept("00166", "조회가 완료되었습니다", false, null))
    }

    @Test fun ordinarySinglePageStillCompletes() {
        assertNull(AssetPagePolicy().accept("00166", "조회완료", false, null))
    }

    @Test fun continuationCodeWorksWithMissingFlagButRequiresKey() {
        assertEquals("next", AssetPagePolicy().accept("00218", "계속조회", false, "next"))
        assertTrue(runCatching { AssetPagePolicy().accept("00218", "계속조회", false, null) }.isFailure)
    }

    @Test fun repeatedKeyOrErrorCannotBeReportedAsCompleteBalance() {
        val policy = AssetPagePolicy()
        policy.accept("00218", "계속조회", true, "same")
        assertTrue(runCatching { policy.accept("00218", "계속조회", true, "same") }.isFailure)
        assertTrue(runCatching { AssetPagePolicy().accept("99999", "계좌 오류", true, "next") }.isFailure)
        assertTrue(runCatching { AssetPagePolicy(1).accept("00218", "계속조회", true, "next") }.isFailure)
    }
}
