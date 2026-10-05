package com.example.stocksgnal.data

import android.content.Context
import com.example.stocksgnal.model.WatchStock
import org.json.JSONArray

fun loadConfiguredWatchStocks(context: Context): List<WatchStock> {
    val saved = context.getSharedPreferences("stock_signal_settings", Context.MODE_PRIVATE)
        .getString("watch_stocks", null)

    if (saved.isNullOrBlank()) {
        return listOf(
            WatchStock("QQQ", "Invesco QQQ Trust"),
            WatchStock("QLD", "ProShares Ultra QQQ"),
            WatchStock("USD", "ProShares Ultra Semiconductors"),
            WatchStock("GOOGL", "Alphabet")
        )
    }

    return try {
        val array = JSONArray(saved)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    WatchStock(
                        ticker = item.optString("ticker").uppercase(),
                        name = item.optString("name", "종목 정보 연결 전"),
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
