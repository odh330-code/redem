package com.example.stocksgnal.data

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class ManualBuyTest {
    private fun draft(price: String = "12.34", qty: Long = 2) =
        ManualBuyDraft("12345678901", "TEST", qty, BigDecimal(price), BuySession.REGULAR, "1")

    @Test fun limitBuyRequestMatchesDocumentedFieldsAndDecimalTotal() {
        val d = draft()
        assertEquals(BigDecimal("24.68"), d.totalUsd)
        assertEquals("""{"Input_0":{"act_no":"12345678901","fc_sec_trd_nat_cd":"200","iem_cd":"TEST","orr_qty":2,"fc_orr_uit_pr":12.34,"ahi_nmn_pr_tp_cd":"00","wtm_cur_knd_cd":"1"}}""", d.requestJson())
    }
    @Test(expected = IllegalArgumentException::class) fun zeroQuantityRejectedBeforeTransport() { draft(qty = 0).validate() }
    @Test(expected = IllegalArgumentException::class) fun fractionalCentRejected() { draft("12.345").validate() }
    @Test(expected = IllegalArgumentException::class) fun missingCurrencyRejected() { draft().copy(currencyCode = "").validate() }
    @Test fun sessionCodesAreExplicit() {
        assertEquals(listOf("00", "61", "62", "63"), BuySession.entries.map { it.code })
    }
    @Test fun trailingZerosAreNormalizedToDocumentedPricePrecision() {
        assertEquals(draft("12.34").requestJson(), draft("12.3400").requestJson())
    }
    @Test fun actualTransportBodyAlwaysContainsAnUnquotedPlainDecimalPrice() {
        listOf("1", "0.01", "12.10", "1E+2", "999999999.99").forEach { input ->
            val json = draft(input).requestJson()
            assertTrue(json.contains("\"fc_orr_uit_pr\":" + BigDecimal(input).setScale(2).toPlainString() + ","))
            assertFalse(json.contains("\"fc_orr_uit_pr\":\""))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun serializationRejectsInjectedTickerBeforeTransport() {
        draft().copy(ticker = "TEST\",\"orr_qty\":99").requestJson()
    }
    @Test fun maximumQuantityKeepsItsExactIntegerValueInTransportBody() {
        assertTrue(draft(qty = 999999999999999999L).requestJson().contains("\"orr_qty\":999999999999999999,"))
    }
    @Test fun receiptRequiresDocumentedSuccessAndAnOrderNumber() {
        assertEquals(ManualBuyState.ACCEPTED, classifyBuyResponse(200, "00171", 42))
        assertEquals(ManualBuyState.UNKNOWN, classifyBuyResponse(200, "00171", 0))
        assertEquals(ManualBuyState.UNKNOWN, classifyBuyResponse(500, "", 0))
        assertEquals(ManualBuyState.UNKNOWN, classifyBuyResponse(200, "unknown", 42))
        assertEquals(ManualBuyState.REJECTED, classifyBuyResponse(401, "", 0))
    }
    @Test fun acceptedOrPartiallyFilledIsNotFullExecution() {
        val accepted = ManualBuyExecution(42, "20261002", "TEST", 2.0, 0.0, 2.0, 0.0, "확인", "")
        assertFalse(accepted.fullyFilled)
        assertEquals("부분 체결", accepted.copy(filled = 1.0, remaining = 1.0).summary)
        assertEquals("체결 완료", accepted.copy(filled = 2.0, remaining = 0.0).summary)
    }
    @Test fun aKnownAcceptedOrderCanBeArchivedForAnIntentionalSeparateOrder() {
        val r = ManualBuyReceipt(draft(), "20261003", ManualBuyState.ACCEPTED, 42)
        assertTrue(r.canStartNextBuy)
        assertFalse(r.copy(orderNo = 0).canStartNextBuy)
    }
    @Test fun uncertainOrRejectedOrdersCannotUseTheAcceptedOrderArchiveFlow() {
        listOf(ManualBuyState.PENDING, ManualBuyState.UNKNOWN, ManualBuyState.REJECTED).forEach { state ->
            assertFalse(ManualBuyReceipt(draft(), "20261003", state, 42).canStartNextBuy)
        }
    }
    @Test fun persistedDecimalFormattingMatchesButAnotherOrderDoesNot() {
        val original = ManualBuyReceipt(draft("1E+2"), "20261003", ManualBuyState.ACCEPTED, 42)
        assertTrue(original.sameStoredOrder(original.copy(draft = draft("100"))))
        assertFalse(original.sameStoredOrder(original.copy(orderNo = 43)))
        assertFalse(original.sameStoredOrder(original.copy(draft = draft("100", qty = 3))))
        assertFalse(original.sameStoredOrder(original.copy(state = ManualBuyState.UNKNOWN)))
    }
}
