package com.example.stocksgnal.data

import android.content.Context
import com.example.stocksgnal.strategy.DailyCandle
import org.json.JSONArray
import org.json.JSONObject

object CandleStorage {

    private const val PREFS_NAME = "stock_candle_data"

    fun saveCandles(
        context: Context,
        ticker: String,
        candles: List<DailyCandle>
    ) {

        val array = JSONArray()

        candles.forEach { candle ->

            val item = JSONObject()

            item.put("date", candle.date)
            item.put("open", candle.open)
            item.put("high", candle.high)
            item.put("low", candle.low)
            item.put("close", candle.close)
            item.put("volume", candle.volume)

            array.put(item)
        }

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                ticker.uppercase(),
                array.toString()
            )
            .apply()
    }

    fun loadCandles(
        context: Context,
        ticker: String
    ): List<DailyCandle> {

        val saved =
            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .getString(
                    ticker.uppercase(),
                    null
                )

        if (saved.isNullOrBlank()) {
            return emptyList()
        }

        return try {

            val array = JSONArray(saved)

            buildList {

                for (i in 0 until array.length()) {

                    val item =
                        array.getJSONObject(i)

                    add(
                        DailyCandle(
                            date = item.getString("date"),
                            open = item.getDouble("open"),
                            high = item.getDouble("high"),
                            low = item.getDouble("low"),
                            close = item.getDouble("close"),
                            volume = item.optDouble("volume", 0.0)
                        )
                    )
                }
            }

        } catch (_: Exception) {

            emptyList()
        }
    }

    fun mergeAndSave(
        context: Context,
        ticker: String,
        newCandles: List<DailyCandle>
    ): Int {

        val oldCandles =
            loadCandles(
                context = context,
                ticker = ticker
            )

        val merged =
            (oldCandles + newCandles)
                .associateBy {
                    it.date
                }
                .values
                .sortedBy {
                    it.date
                }

        saveCandles(
            context = context,
            ticker = ticker,
            candles = merged
        )

        return merged.size
    }

    fun getLastDate(
        context: Context,
        ticker: String
    ): String? {

        return loadCandles(
            context = context,
            ticker = ticker
        )
            .maxByOrNull {
                it.date
            }
            ?.date
    }

    fun deleteCandles(
        context: Context,
        ticker: String
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(
                ticker.uppercase()
            )
            .apply()
    }
}