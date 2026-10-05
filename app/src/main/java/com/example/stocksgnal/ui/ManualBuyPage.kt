package com.example.stocksgnal.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.concurrent.Executor

@Composable
fun ManualBuyPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts = remember { NamuAccountApi.linkedAccounts(context) }
    var enabled by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }
    var ticker by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<BuySession?>(null) }
    var currency by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var receipt by remember { mutableStateOf<ManualBuyReceipt?>(null) }
    var storageError by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<ManualBuyDraft?>(null) }
    var authDraft by remember { mutableStateOf<ManualBuyDraft?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var queryDate by remember { mutableStateOf("") }
    var executions by remember { mutableStateOf<List<ManualBuyExecution>>(emptyList()) }
    var quote by remember { mutableStateOf("") }
    var showReset by remember { mutableStateOf(false) }
    var confirmedNoOrder by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<ManualBuyReceipt>>(emptyList()) }
    var showNext by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var historyReceipt by remember { mutableStateOf<ManualBuyReceipt?>(null) }
    var historyDate by remember { mutableStateOf("") }
    var historyExecutions by remember { mutableStateOf<List<ManualBuyExecution>>(emptyList()) }
    var historyMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try { receipt = NamuOrderApi.loadReceipt(context); queryDate = receipt?.date.orEmpty(); history = NamuOrderApi.loadHistory(context) }
        catch (e: Exception) { storageError = true; message = e.message.orEmpty() }
        ready = true
    }
    DisposableEffect(Unit) { onDispose { authDraft = null; signal?.cancel() } }

    fun sendAuthenticated() {
        val d = authDraft ?: return
        authDraft = null
        scope.launch {
            try {
                receipt = withContext(Dispatchers.IO) { NamuOrderApi.submitBuy(context, d) }
                queryDate = receipt!!.date; message = receipt!!.message; enabled = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = e.message.orEmpty()
                try { receipt = NamuOrderApi.loadReceipt(context); queryDate = receipt?.date.orEmpty() }
                catch (_: Exception) { storageError = true }
            } finally { busy = false }
        }
    }
    val credentialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) sendAuthenticated()
        else { authDraft = null; busy = false; message = "인증 취소 · 주문을 전송하지 않았습니다." }
    }
    fun deviceCredential() {
        val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = manager.createConfirmDeviceCredentialIntent("미국주식 매수 본인 인증", "확인한 주문을 전송하려면 기기 잠금을 해제하세요")
        if (!manager.isDeviceSecure || intent == null) {
            authDraft = null; busy = false; message = "기기 화면 잠금을 설정해야 매수할 수 있습니다."
        } else credentialLauncher.launch(intent)
    }
    fun authenticate(d: ManualBuyDraft) {
        if (busy || authDraft != null) return
        busy = true; authDraft = d
        if (Build.VERSION.SDK_INT < 30) { deviceCredential(); return }
        val executor = Executor { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        val cancellation = CancellationSignal(); signal = cancellation
        BiometricPrompt.Builder(context).setTitle("미국주식 매수 본인 인증")
            .setSubtitle("${d.ticker} ${d.quantity}주 · 지정가 $${d.price.toPlainString()}")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build().authenticate(cancellation, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { sendAuthenticated() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT) deviceCredential()
                    else { authDraft = null; busy = false; message = "인증 실패 · 주문 미전송: $errString" }
                }
            })
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("실제 미국주식 매수", style = MaterialTheme.typography.titleMedium)
        Text("계좌·종목·수량·지정가를 직접 입력합니다. 주문 확인 → 본인 인증 후 실제 주문이 전송됩니다. 접수와 체결은 별도로 확인합니다.")
        val r = receipt
        if (r != null) {
            Text("${maskAccountNo(r.draft.account)} · ${r.draft.ticker} ${r.draft.quantity}주")
            Text("${r.draft.session.label} · 지정가 $${r.draft.price.toPlainString()}")
            Text(when (r.state) {
                ManualBuyState.ACCEPTED -> "접수 완료 · 주문번호 ${r.orderNo} · 체결은 아래에서 조회하세요"
                ManualBuyState.REJECTED -> "주문 거부 · ${r.message}"
                else -> "접수 여부 확인 필요 · 새 매수를 중지했습니다. 주문 내역을 확인하세요."
            })
            if (r.state != ManualBuyState.REJECTED) {
                OutlinedTextField(queryDate, { queryDate = it.filter(Char::isDigit).take(8) }, label = { Text("조회 주문일자 (YYYYMMDD)") },
                    enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    val requestedDate = queryDate
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { NamuOrderApi.executions(context, r, requestedDate) }
                            executions = result.getOrDefault(emptyList())
                            message = result.exceptionOrNull()?.message ?: if (executions.isEmpty())
                                "주문을 찾지 못했습니다. 전일·당일 주문일자 또는 나무 앱 내역을 확인하세요. 재주문하지 않습니다."
                            else if (r.orderNo == 0L) "해당 종목의 주문 목록입니다. 어떤 주문인지 나무 앱과 대조하세요."
                            else "주문번호 ${r.orderNo} 조회 완료"
                        } finally { busy = false }
                    }
                }) { Text("주문·체결 조회") }
                executions.forEach { row ->
                    Text("${row.orderDate} · 주문 ${row.orderNo} · ${row.summary}")
                    Text("주문 ${row.ordered}주 / 체결 ${row.filled}주 / 미체결 ${row.remaining}주 · 체결가 $${row.price ?: 0}")
                }
                Text("정정·취소는 나무 앱에서 직접 처리하세요. 접수된 주문은 보관한 뒤 별도의 새 매수를 작성할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                if (r.canStartNextBuy) OutlinedButton(enabled = !busy, onClick = { showNext = true }) {
                    Text("새 매수 작성")
                }
                if (r.state in setOf(ManualBuyState.UNKNOWN, ManualBuyState.PENDING)) {
                    Text(r.message)
                    TextButton(enabled = !busy, onClick = { confirmedNoOrder = false; showReset = true }) {
                        Text("이전 주문 확인 후 다시 작성")
                    }
                }
            } else TextButton(enabled = !busy, onClick = {
                NamuOrderApi.clearRejected(context); receipt = null; message = "거부된 주문을 지웠습니다. 주문 내용을 다시 확인하세요."
            }) { Text("거부된 주문 다시 작성") }
        } else if (!storageError && ready) {
            Row { Checkbox(enabled, { enabled = it }, enabled = !busy); Text("임시 매수 기능 켜기") }
            if (enabled) {
                if (accounts.isEmpty()) Text("계좌 화면에서 나무 계좌를 먼저 추가하세요.")
                accounts.forEach { a -> FilterChip(selected = account == a.acctNo, onClick = { account = a.acctNo }, enabled = !busy,
                    label = { Text("${a.label} · ${maskAccountNo(a.acctNo)}") }) }
                OutlinedTextField(ticker, { ticker = it.trim().uppercase(); quote = "" }, label = { Text("미국주식 티커") }, enabled = !busy)
                TextButton(enabled = !busy && ticker.isNotBlank(), onClick = {
                    busy = true; val requested = ticker
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { NamuMarketApi.stockQuote(context, requested) }
                            quote = result.fold({ "${it.name} · 최근 시세 $${it.price} (지정가는 직접 입력)" }, { "시세 조회 실패: ${it.message}" })
                        } finally { busy = false }
                    }
                }) { Text("종목·시세 확인") }
                if (quote.isNotBlank()) Text(quote)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("수량 (정수 주)") }, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(price, { price = it }, label = { Text("지정가 (USD, 소수점 두 자리)") }, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("주문 시간대 선택")
                BuySession.entries.forEach { s -> FilterChip(session == s, { session = s }, enabled = !busy, label = { Text(s.label) }) }
                Text("증거금 통화 선택")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(currency == "1", { currency = "1" }, enabled = !busy, label = { Text("달러") })
                    FilterChip(currency == "2", { currency = "2" }, enabled = !busy, label = { Text("원화") })
                }
                Button(enabled = !busy, onClick = {
                    try {
                        val d = ManualBuyDraft(account ?: "", ticker, quantity.trim().toLongOrNull() ?: 0,
                            price.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO, session ?: error("주문 시간대를 선택하세요"), currency ?: "")
                        d.validate(); review = d; message = ""
                    } catch (e: Exception) { message = e.message.orEmpty() }
                }) { Text("매수 주문 내용 확인") }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        if (message.isNotBlank()) Text(message)
        if (history.isNotEmpty()) TextButton(enabled = !busy, onClick = { showHistory = true }) {
            Text("이전 주문 내역 (${history.size}건)")
        }
    }
    val d = review
    val accepted = receipt
    if (showNext && accepted != null) AlertDialog(
        onDismissRequest = { showNext = false }, title = { Text("새 매수 작성") },
        text = { Text("주문 ${accepted.orderNo}을 이전 주문 내역에 보관합니다. 기존 미체결 주문은 유지됩니다. 새로 작성하는 매수는 별도의 추가 주문입니다.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; showNext = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { NamuOrderApi.startNextBuy(context, accepted) }
                    receipt = null; executions = emptyList(); queryDate = ""; enabled = false
                    account = null; ticker = ""; quantity = ""; price = ""; session = null; currency = null
                    quote = ""; review = null; authDraft = null
                    history = NamuOrderApi.loadHistory(context)
                    message = "이전 주문을 보관했습니다. 새 주문 내용을 입력하세요."
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    message = e.message.orEmpty()
                } finally { busy = false }
            }
        }) { Text("보관하고 새 매수 작성") } },
        dismissButton = { TextButton(onClick = { showNext = false }) { Text("취소") } }
    )
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("이전 주문 내역 (최근 100건)") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            history.forEach { old ->
                TextButton(onClick = {
                    showHistory = false; historyReceipt = old; historyDate = old.date
                    historyExecutions = emptyList(); historyMessage = "접수 기록입니다. 현재 체결 결과는 조회하세요."
                }) { Text("${old.date} · ${maskAccountNo(old.draft.account)}\n${old.draft.ticker} ${old.draft.quantity}주 · 지정가 $${old.draft.price.toPlainString()} · 주문 ${old.orderNo}") }
            }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("닫기") } })
    val old = historyReceipt
    if (old != null) AlertDialog(onDismissRequest = { if (!busy) historyReceipt = null }, title = { Text("이전 주문 ${old.orderNo}") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("${maskAccountNo(old.draft.account)} · ${old.draft.ticker} ${old.draft.quantity}주\n${old.draft.session.label} · 지정가 $${old.draft.price.toPlainString()}")
            OutlinedTextField(historyDate, { historyDate = it.filter(Char::isDigit).take(8) }, enabled = !busy,
                label = { Text("조회 주문일자 (YYYYMMDD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(enabled = !busy, onClick = {
                busy = true; val requestedDate = historyDate
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { NamuOrderApi.executions(context, old, requestedDate) }
                        historyExecutions = result.getOrDefault(emptyList())
                        historyMessage = result.exceptionOrNull()?.message ?: if (historyExecutions.isEmpty())
                            "주문을 찾지 못했습니다. 주문일자를 확인하세요." else "조회 완료"
                    } finally { busy = false }
                }
            }) { Text("주문·체결 조회") }
            historyExecutions.forEach { row ->
                Text("${row.orderDate} · ${row.summary}\n주문 ${row.ordered}주 / 체결 ${row.filled}주 / 미체결 ${row.remaining}주 · 체결가 $${row.price ?: 0}")
            }
            Text(historyMessage)
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { historyReceipt = null; showHistory = true }) { Text("목록") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { historyReceipt = null }) { Text("닫기") } })
    if (showReset) AlertDialog(onDismissRequest = { showReset = false }, title = { Text("이전 주문 직접 확인") },
        text = { Column {
            Text("나무 앱에서 이전 주문의 접수·체결·취소 결과를 직접 확인하세요. 아직 접수 또는 체결된 주문이 있으면 새 매수를 작성하지 마세요.")
            Row { Checkbox(confirmedNoOrder, { confirmedNoOrder = it }); Text("체결된 수량이 없고 진행 중인 주문도 없음을 나무 앱에서 확인했습니다.") }
        } },
        confirmButton = { TextButton(enabled = confirmedNoOrder, onClick = {
            NamuOrderApi.clearAfterManualCheck(context); receipt = null; executions = emptyList(); showReset = false; enabled = false
            message = "직접 확인한 이전 주문 기록을 지웠습니다."
        }) { Text("확인 완료 · 새 주문 작성") } },
        dismissButton = { TextButton(onClick = { showReset = false }) { Text("닫기") } })
    if (d != null) AlertDialog(onDismissRequest = { review = null }, title = { Text("실제 매수 주문 확인") },
        text = { Text("계좌 ${maskAccountNo(d.account)}\n${d.ticker} ${d.quantity}주\n${d.session.label}\n지정가 $${d.price.toPlainString()}\n주문금액 $${d.totalUsd.toPlainString()} (수수료 제외)\n증거금 ${if (d.currencyCode == "1") "달러" else "원화"}\n\n인증을 완료하면 주문을 한 번 전송합니다.") },
        confirmButton = { TextButton(onClick = { review = null; authenticate(d) }) { Text("본인 인증 후 매수 전송") } },
        dismissButton = { TextButton(onClick = { review = null }) { Text("취소") } })
}
