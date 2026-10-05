package com.example.stocksgnal.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.data.NamuCredentials
import com.example.stocksgnal.data.NamuSecretStore
import com.example.stocksgnal.data.WatchIndex
import com.example.stocksgnal.data.loadWatchIndices
import com.example.stocksgnal.data.saveWatchIndices
import com.example.stocksgnal.longterm.LongStrategyRepository
import com.example.stocksgnal.ui.theme.AppBackground
import com.example.stocksgnal.ui.theme.AppPalette
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsPage {
    MAIN,
    APPEARANCE,
    DATA,
    STRATEGY,
    WATCH_INDICES,
    MANUAL_BUY
}

@Composable
fun SettingsScreen(
    resetKey: Int = 0,
    palette: AppPalette,
    onPaletteChange: (AppPalette) -> Unit,
    background: AppBackground,
    onBackgroundChange: (AppBackground) -> Unit
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("stock_signal_settings", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    val strategyRepository = remember { LongStrategyRepository(context.applicationContext) }

    var page by remember { mutableStateOf(SettingsPage.MAIN) }
    var strategyVersion by remember { mutableIntStateOf(0) }
    var namuAppKey by remember { mutableStateOf("") }
    var namuAppSecret by remember { mutableStateOf("") }
    var namuStatusMessage by remember {
        mutableStateOf(if (NamuSecretStore.load(context) == null) "나무증권 인증 정보가 저장되지 않았습니다." else "나무증권 인증 정보가 저장되어 있습니다.")
    }

    LaunchedEffect(resetKey) {
        page = SettingsPage.MAIN
    }

    BackHandler(enabled = page != SettingsPage.MAIN) {
        page = SettingsPage.MAIN
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StockDesign.ScreenPadding)
    ) {
        SettingsHeader(
            title = when (page) {
                SettingsPage.MAIN -> "설정"
                SettingsPage.APPEARANCE -> "커스텀관리"
                SettingsPage.DATA -> "데이터관리"
                SettingsPage.STRATEGY -> "계곡다이빙 설정"
                SettingsPage.WATCH_INDICES -> "관심지수"
                SettingsPage.MANUAL_BUY -> "임시 미국주식 매수"
            },
            onBack = if (page == SettingsPage.MAIN) null else { { page = SettingsPage.MAIN } }
        )

        Spacer(modifier = Modifier.height(20.dp))

        when (page) {
            SettingsPage.MAIN -> {
                SettingsNavCard(
                    title = "커스텀관리",
                    description = "테마 색상과 배경을 바꿉니다.",
                    onClick = { page = SettingsPage.APPEARANCE }
                )

                Spacer(modifier = Modifier.height(16.dp))

                SettingsNavCard(
                    title = "데이터관리",
                    description = "나무증권 API Key와 데이터 갱신 방식을 관리합니다.",
                    onClick = { page = SettingsPage.DATA }
                )

                Spacer(modifier = Modifier.height(16.dp))

                SettingsNavCard(
                    title = "계곡다이빙 설정",
                    description = "새 전략 추가, 매도 기준, 체결 기록 시작일, 환율 직접 입력을 관리합니다.",
                    onClick = { page = SettingsPage.STRATEGY }
                )

                Spacer(modifier = Modifier.height(16.dp))
                SettingsNavCard(
                    title = "관심지수",
                    description = "홈에 표시할 지수·환율·금리 항목을 관리합니다.",
                    onClick = { page = SettingsPage.WATCH_INDICES }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "각 사용자의 API Key와 설정은 해당 기기에만 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                SettingsNavCard(title = "임시 미국주식 매수", description = "직접 입력 · 주문 확인 · 본인 인증 · 체결 조회",
                    onClick = { page = SettingsPage.MANUAL_BUY })
            }

            SettingsPage.APPEARANCE -> {
                AppearanceSettingsPage(
                    palette = palette,
                    onPaletteChange = onPaletteChange,
                    background = background,
                    onBackgroundChange = onBackgroundChange
                )
            }

            SettingsPage.DATA -> {
                RefreshStatusCard()
                Spacer(modifier = Modifier.height(16.dp))
                NamuApiSettingsCard(
                    appKey = namuAppKey,
                    appSecret = namuAppSecret,
                    statusMessage = namuStatusMessage,
                    onAppKeyChange = { namuAppKey = it.trim() },
                    onAppSecretChange = { namuAppSecret = it.trim() },
                    onSave = {
                        if (namuAppKey.isBlank() || namuAppSecret.isBlank()) {
                            namuStatusMessage = "AppKey와 AppSecret을 모두 입력하세요."
                        } else {
                            NamuSecretStore.save(context, NamuCredentials(namuAppKey, namuAppSecret))
                            namuAppKey = ""
                            namuAppSecret = ""
                            namuStatusMessage = "인증 정보를 이 기기의 암호화 저장소에 저장했습니다."
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
                MassiveKeyCard()
                FmpKeyCard()

                Spacer(modifier = Modifier.height(16.dp))
                RefreshModeCard()

            }

                SettingsPage.MANUAL_BUY -> ManualBuyPage()
            SettingsPage.WATCH_INDICES -> WatchIndicesSettingsPage()
            SettingsPage.STRATEGY -> {
                val instances = remember(strategyVersion) { strategyRepository.instances() }
                InstanceManagerCard(
                    repo = strategyRepository,
                    instances = instances,
                    onChanged = { strategyVersion++ }
                )

                Spacer(modifier = Modifier.height(16.dp))

                CommonSettingsCard(
                    repo = strategyRepository,
                    version = strategyVersion,
                    onSaved = { strategyVersion++ }
                )
            }
        }
    }
}

@Composable
private fun WatchIndicesSettingsPage() {
    val context = LocalContext.current
    var indices by remember { mutableStateOf(loadWatchIndices(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("관심지수", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("켜 둔 항목은 홈의 주요지수에 표시됩니다.", style = MaterialTheme.typography.bodySmall)
        indices.forEach { index ->
            Card(modifier = Modifier.fillMaxWidth(), colors = glassCardColors()) {
                Row(Modifier.fillMaxWidth().padding(StockDesign.CardPadding), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(index.name, fontWeight = FontWeight.Bold)
                        Text(index.symbol, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(
                        checked = index.enabled,
                        onCheckedChange = { enabled ->
                            val next = indices.map { if (it.name == index.name) it.copy(enabled = enabled) else it }
                            indices = next
                            saveWatchIndices(context, next)
                        }
                    )
                }
            }
        }
    }
}

/** Massive(옛 Polygon.io) 무료 키 — 해외주식 배당 일정(배당락일·지급일·주당 배당금) 표시용 */
@Composable
private fun MassiveKeyCard() {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(if (NamuSecretStore.loadMassiveKey(context) == null) "저장된 키가 없습니다." else "키가 저장되어 있습니다.")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = glassCardColors(),
        shape = RoundedCornerShape(StockDesign.CardRadius)
    ) {
        Column(modifier = Modifier.padding(StockDesign.CardPadding)) {
            Text("배당 일정 (Massive)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "나무증권 API에 해외주식 배당 일정이 없어서 이것만 Massive(옛 Polygon.io) 무료 키로 받습니다. " +
                    "무료 한도(분당 5회)를 넘지 않게 1분에 4번까지만, 필요한 종목만 받습니다. 시세·전략 계산에는 쓰지 않습니다.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Massive API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (key.isBlank()) status = "키를 입력하세요." else {
                            NamuSecretStore.saveMassiveKey(context, key)
                            key = ""
                            status = "키를 암호화해 저장했습니다. 홈에서 새로고침하면 배당 일정을 받습니다."
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("키 저장") }
                TextButton(onClick = { NamuSecretStore.clearMassiveKey(context); status = "키를 지웠습니다." }) { Text("지우기") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NamuApiSettingsCard(
    appKey: String,
    appSecret: String,
    statusMessage: String,
    onAppKeyChange: (String) -> Unit,
    onAppSecretChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = glassCardColors(),
        shape = RoundedCornerShape(StockDesign.CardRadius)
    ) {
        Column(modifier = Modifier.padding(StockDesign.CardPadding)) {
            Text("나무증권 API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("AppKey와 AppSecret은 소스에 포함되지 않으며 이 기기의 Android Keystore로 암호화해 저장합니다.", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = appKey,
                onValueChange = onAppKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("나무증권 AppKey") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = appSecret,
                onValueChange = onAppSecretChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("나무증권 AppSecret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("나무증권 인증 정보 저장")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: (() -> Unit)?) {
    Row(modifier = Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("‹ 뒤로")
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SettingsNavCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = glassCardColors(),
        shape = RoundedCornerShape(StockDesign.CardRadius),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(StockDesign.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun AppearanceSettingsPage(
    palette: AppPalette,
    onPaletteChange: (AppPalette) -> Unit,
    background: AppBackground,
    onBackgroundChange: (AppBackground) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = glassCardColors(),
        shape = RoundedCornerShape(StockDesign.CardRadius)
    ) {
        Column(modifier = Modifier.padding(StockDesign.CardPadding)) {
            Text("기본 테마 강조 색상", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            AppPalette.entries.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { item ->
                        FilterChip(
                            selected = palette == item,
                            onClick = { onPaletteChange(item) },
                            label = { Text(item.label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("테마 배경", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            AppBackground.entries.filterNot {
                it == AppBackground.SOLID || it == AppBackground.CHART_WAVE || it == AppBackground.BRIGHT_NYC
            }.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { item ->
                        FilterChip(
                            selected = background == item,
                            onClick = { onBackgroundChange(item) },
                            label = { Text(item.label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}
