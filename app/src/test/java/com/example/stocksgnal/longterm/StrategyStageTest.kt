package com.example.stocksgnal.longterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 화면용 "현재 단계"가 엔진 상태와 맞게 나오는지 확인 (계산 자체는 Golden 테스트가 담당) */
class StrategyStageTest {

    private val ledger = LedgerSnapshot(
        accounts = mapOf(1 to AccountSettings(account = 1, baseKrw = 0.0, cashKrw = 0.0, qldShares = 0.0)),
        fills = emptyList(),
        scales = emptyMap(),
        recordStart = LocalDate.of(2100, 1, 1),
        sellDropPct = 3.0
    )

    /** 꾸준히 오르는 330일 + 마지막 날 직전 종가 대비 lastDropPct% 하락 */
    private fun bars(lastDropPct: Double): List<QqqBar> {
        val start = LocalDate.of(2024, 1, 1)
        val list = (0 until 329).map { QqqBar(start.plusDays(it.toLong()), 100.0 + it * 0.1) }
        val prev = list.last().close
        return list + QqqBar(start.plusDays(329), prev * (1 - lastDropPct / 100))
    }

    private fun stageFor(lastDropPct: Double): MarketView =
        LongStrategyPlanner.compute(bars(lastDropPct), null, null, ledger)!!.market

    @Test
    fun risingMarketIsWait() {
        val m = stageFor(0.0)
        assertEquals(StrategyStage.WAIT, m.stage)
    }

    @Test
    fun firstCampaignIs11() {
        val m = stageFor(6.0)           // -5% 구간 → 캠페인 1
        assertEquals(StrategyStage.BUY_1_1, m.stage)
        assertTrue(m.stageDetail.contains("캠페인1"))
    }

    @Test
    fun secondCampaignIsStill11() {
        val m = stageFor(12.0)          // -10% 구간 → 캠페인 2
        assertEquals(StrategyStage.BUY_1_1, m.stage)
    }

    @Test
    fun thirdCampaignIs12() {
        val m = stageFor(17.0)          // -15% 구간 → 캠페인 3
        assertEquals(StrategyStage.BUY_1_2, m.stage)
        assertTrue(m.stageDetail.contains("캠페인3"))
    }

    @Test
    fun campaignToStageMapping() {
        assertEquals(StrategyStage.BUY_1_1, StrategyStage.forCampaign(1))
        assertEquals(StrategyStage.BUY_1_1, StrategyStage.forCampaign(StrategyStage.LAST_CAMPAIGN_OF_1_1))
        assertEquals(StrategyStage.BUY_1_2, StrategyStage.forCampaign(StrategyStage.LAST_CAMPAIGN_OF_1_1 + 1))
        assertEquals(StrategyStage.BUY_1_2, StrategyStage.forCampaign(6))
    }
}
