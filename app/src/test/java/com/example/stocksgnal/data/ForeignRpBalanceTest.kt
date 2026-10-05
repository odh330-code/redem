package com.example.stocksgnal.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Test

class ForeignRpBalanceTest {
    @Test fun receivesRpOnSecondPageAfter00218InsteadOfStoppingAtFirstPage() {
        val keys = mutableListOf<String?>()
        val rows = NamuAccountApi.readForeignRpPages(mutableListOf()) { cts ->
            keys.add(cts)
            if (cts == null) NamuHttp.Response(JSONObject("""{"rsp_cd":"00218","rsp_msg":"계속조회시 다음(연속조회)버튼을 누르시기바랍니다","Output_1":[{"iem_mlf_nm":"외화주식","iem_nm":"애플","cur_cd":"USD","eal_amt":1000}]}"""), "next-page", true)
            else NamuHttp.Response(JSONObject("""{"rsp_cd":"00166","Output_1":[{"iem_mlf_nm":"외화RP","iem_nm":"USD RP","cur_cd":"USD","itg_bnc_qty":"125.50","eal_amt":"180000"}]}"""), null, false)
        }
        assertEquals(listOf(null, "next-page"), keys)
        assertEquals(1, rows.size)
        assertEquals(180000.0, rows.single().evalKrw, 0.0)
        assertEquals(125.5, rows.single().quantity, 0.0)
    }

    @Test fun laterPageFailureNeverReturnsPartialRpTotal() {
        val result = runCatching {
            NamuAccountApi.readForeignRpPages(mutableListOf()) { cts ->
                if (cts == null) NamuHttp.Response(JSONObject("""{"rsp_cd":"00218","Output_1":[{"iem_mlf_nm":"외화RP","iem_nm":"USD RP","cur_cd":"USD","eal_amt":1000}]}"""), "next", true)
                else NamuHttp.Response(JSONObject("""{"rsp_cd":"99999","rsp_msg":"조회 실패"}"""), null, false)
            }
        }
        assertTrue(result.isFailure)
    }

    @Test fun findsForeignRpWithoutTreatingCashOrSharesAsRp() {
        assertTrue(isForeignRpAsset("외화RP", "USD RP", "현금", "USD"))
        assertTrue(isForeignRpAsset("RP", "USD 자동매수", "현금", "USD"))
        assertTrue(isForeignRpAsset("외화RP", "", "현금", ""))
        assertTrue(isForeignRpAsset("외화RP", "외화 수시 RP", "현금", "KRW"))
        assertTrue(isForeignRpAsset("금융상품", "USDRP", "현금", "USD"))
        assertTrue(isForeignRpAsset("외화ＲＰ", "", "현금", "USD"))
        assertTrue(isForeignRpAsset("환매조건부채권", "달러", "현금", "USD"))
        assertTrue(isForeignRpAsset("금융상품", "RP", "현금", ""))
        assertFalse(isForeignRpAsset("RP", "원화 RP", "현금", "KRW"))
        assertFalse(isForeignRpAsset("외화주식", "CORPORATE BOND", "현금", "USD"))
        assertFalse(isForeignRpAsset("외화주식", "AAPL", "현금", "USD"))
        assertFalse(isForeignRpAsset("주식", "CORPORATION", "현금", "USD"))
        assertFalse(isForeignRpAsset("RP", "일반 RP", "현금", "KRW"))
    }
}
