package com.example.stocksgnal.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MarketOverviewItem(
    val label: String,
    val value: Double?,
    val changePct: Double?,
    val source: String,
    /** 관심 › 지수 항목의 종목코드 */
    val symbol: String? = null
)

object MarketOverviewStore {
    private const val PREFS = "market_overview"

    fun save(context: Context, items: List<MarketOverviewItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("label", item.label)
                    .put("value", item.value ?: JSONObject.NULL)
                    .put("changePct", item.changePct ?: JSONObject.NULL)
                    .put("source", item.source)
                    .put("symbol", item.symbol ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("items", array.toString())
            .apply()
    }

    fun load(context: Context): List<MarketOverviewItem> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("items", null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(saved)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        MarketOverviewItem(
                            label = item.getString("label"),
                            value = item.optDoubleOrNull("value"),
                            changePct = item.optDoubleOrNull("changePct"),
                            source = item.optString("source"),
                            symbol = if (item.has("symbol") && !item.isNull("symbol")) item.optString("symbol") else null
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

object MarketOverviewApi {
    /**
     * 관심 › 지수 탭에서 켜 둔 지수·선물·금리·환율을 나무증권에서 받는다.
     * 두 번째 값(실패 목록)은 홈 새로고침 카드에 작은 글씨로 보여줄 짧은 이름이다.
     */
    fun refresh(context: Context): Pair<List<MarketOverviewItem>, List<String>> {
        val failures = mutableListOf<String>()
        val items = mutableListOf<MarketOverviewItem>()
        var namuDown: String? = null

        for (index in loadWatchIndices(context).filter { it.enabled && it.hasSource }) {
            if (index.source == IndexSource.US_TREASURY) {
                // 미국 국채금리: 재무부 공식 자료 (나무증권 연결과 무관)
                TreasuryYieldApi.latest(index.symbol)
                    .onSuccess { y ->
                        val date = "%02d-%02d".format(y.date.monthValue, y.date.dayOfMonth)
                        items.add(MarketOverviewItem(index.name, y.value, y.changePoint, "미 재무부 · $date 종가", index.name))
                    }
                    .onFailure { e -> failures.add("${index.name}(재무부: ${e.message?.take(24).orEmpty()})") }
                continue
            }
            if (namuDown != null) {
                failures.add("${index.name}(나무증권 연결 안 됨)")
                continue
            }
            val result = when (index.source) {
                IndexSource.CME_FUTURE -> NamuMarketApi.futureQuote(context, index.symbol)
                IndexSource.INDEX_FX -> NamuMarketApi.indexQuote(context, index.name, index.codes)
                IndexSource.US_TREASURY -> continue
                IndexSource.USD_KRW -> NamuMarketApi.indexQuote(context, index.name, index.codes).recoverCatching { e ->
                    if (e is NamuHttp.Fatal) throw e
                    // 환율 코드를 못 찾으면 해외주식 현재가에 딸려 오는 환율을 쓴다 (등락률은 없음)
                    NamuMarketApi.Quote(NamuMarketApi.usdKrw(context).getOrThrow(), null, "현재가 환율")
                }
            }
            result.onSuccess { quote ->
                items.add(MarketOverviewItem(index.name, quote.price, quote.changePct, "나무증권 · ${quote.code}", index.name))
            }.onFailure { e ->
                // 실패 이유를 짧게 남긴다 (예: "미국 10년물 금리(나무증권: 조회 내역이 없습니다)")
                val reason = e.message?.trim()?.take(24).orEmpty()
                failures.add(if (reason.isBlank()) "${index.name}(나무증권)" else "${index.name}(나무증권: $reason)")
                if (e is NamuHttp.Fatal) namuDown = e.message
            }
        }

        if (items.isNotEmpty()) {
            // 이번에 못 받은 항목은 지난번 값을 그대로 둔다
            val kept = MarketOverviewStore.load(context).filter { old ->
                (old.source.startsWith("나무증권") || old.source.startsWith("미 재무부")) && items.none { it.symbol == old.symbol }
            }
            MarketOverviewStore.save(context, items + kept)
        }
        return items to failures
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
