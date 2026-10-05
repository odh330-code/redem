package com.example.stocksgnal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun FmpKeyCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (NamuSecretStore.loadFmpKey(context) == null) "FMP 키를 입력하세요." else "FMP 키가 저장되어 있습니다.") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("다우존스 반도체 · FMP", style = MaterialTheme.typography.titleMedium)
            Text("DJUSSC 과거 일봉과 갱신 데이터를 받습니다. 조회 결과에서 키·요금제 접근 여부와 수신 기간을 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = key, onValueChange = { key = it.trim() }, label = { Text("FMP API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Row {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            status = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (key.isNotBlank()) NamuSecretStore.saveFmpKey(context, key)
                                    val rows = FmpIndexApi.dailyCandles(context, LocalDate.now(ZoneId.of("America/New_York")), null, 5000).getOrThrow()
                                    CandleStorage.saveCandles(context, "DJUSSC", rows)
                                    CandleSource.markCurrentSource(context, "DJUSSC")
                                    "FMP DJUSSC ${rows.size}일 수신 · ${rows.first().date} ~ ${rows.last().date}"
                                }.getOrElse { it.message ?: "FMP 조회 실패" }
                            }
                            key = ""
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "DJUSSC 받는 중…" else "저장 및 DJUSSC 받기") }
                TextButton(enabled = !busy, onClick = { NamuSecretStore.clearFmpKey(context); key = ""; status = "FMP 키를 지웠습니다." }) { Text("키 지우기") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
