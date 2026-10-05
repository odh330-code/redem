package com.example.stocksgnal.ui

import android.content.Context
import com.example.stocksgnal.longterm.LongStrategyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime

/** Manual and foreground automatic refresh share one run and one completion timestamp. */
object RefreshCoordinator {
    private val lock = Mutex()
    const val INTERVAL_MS = 60L * 60 * 1000

    suspend fun refresh(context: Context, repository: LongStrategyRepository): Pair<String, List<String>>? {
        if (!lock.tryLock()) return null
        try {
            val result = withContext(Dispatchers.IO) {
                try { refreshAll(context, repository) } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
                    "%04d-%02d-%02d %02d:%02d".format(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute) to
                        listOf("갱신 중 오류")
                }
            }
            val settings = context.getSharedPreferences("stock_signal_settings", Context.MODE_PRIVATE)
            settings.edit()
                .putBoolean("namu_refresh_succeeded", result.second.isEmpty())
                .putString("last_refresh_failures", result.second.distinct().joinToString(", "))
                .putString("last_refresh_text", result.first)
                .putLong("last_refresh_epoch_ms", System.currentTimeMillis())
                .apply()
            return result
        } finally {
            lock.unlock()
        }
    }
}
