package com.example.stocksgnal.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.data.FearGreedStore
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors

/** 홈에서는 숨긴 갱신 상세 결과. 저장값만 읽으며 API를 호출하지 않는다. */
@Composable
fun RefreshStatusCard() {
    val context = LocalContext.current
    val stores = remember(context) {
        listOf("stock_signal_settings", "stock_candle_data", "fear_greed", "long_strategy")
            .map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
    }
    val settings = stores.first()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(stores) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        onDispose { stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }
    val timestamp = remember(revision) { settings.getString("last_refresh_text", "아직 데이터 갱신 전").orEmpty() }
    val failures = remember(revision) { settings.getString("last_refresh_failures", "").orEmpty() }
    val source = remember(revision) { settings.getString("last_data_source", "").orEmpty() }
    val timing = remember(revision) { settings.getString("last_account_refresh_durations", "").orEmpty() }
    val fgTime = remember(revision) { FearGreedStore.load(context)?.fetchedAt }
    val attempted = remember(revision) { settings.contains("last_refresh_text") }
    val successful = remember(revision) { settings.getBoolean("namu_refresh_succeeded", false) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(StockDesign.CardRadius), colors = glassCardColors()) {
        Column(Modifier.padding(StockDesign.CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("새로고침 상태", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("마지막 갱신 $timestamp", style = MaterialTheme.typography.bodySmall)
            Text(when {
                !attempted -> "아직 갱신하지 않았습니다."
                failures.isNotBlank() -> "일부 데이터 갱신 실패"
                successful -> "갱신 완료"
                else -> "갱신 결과 확인 필요"
            }, style = MaterialTheme.typography.bodyMedium)
            if (failures.isNotBlank()) SelectionContainer {
                Text("받지 못함: $failures", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (source.isNotBlank()) Text(source, style = MaterialTheme.typography.bodySmall)
            if (timing.isNotBlank()) Text(timing, style = MaterialTheme.typography.bodySmall)
            Text("공포·탐욕지수 수신 · ${fgTime ?: "아직 없음"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
