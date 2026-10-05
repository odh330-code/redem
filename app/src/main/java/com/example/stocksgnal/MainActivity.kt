package com.example.stocksgnal

import android.os.Bundle
import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.stocksgnal.data.NamuSecretStore
import com.example.stocksgnal.longterm.LongStrategyRepository
import com.example.stocksgnal.ui.HomeScreen
import com.example.stocksgnal.ui.NamuAccountsScreen
import com.example.stocksgnal.ui.ProfitScreen
import com.example.stocksgnal.ui.RefreshCoordinator
import com.example.stocksgnal.ui.ExecutionScreen
import com.example.stocksgnal.ui.SettingsScreen
import com.example.stocksgnal.ui.SignalScreen
import com.example.stocksgnal.ui.StockChartScreen
import com.example.stocksgnal.ui.WatchlistScreen
import com.example.stocksgnal.ui.theme.AppBackground
import com.example.stocksgnal.ui.theme.AppPalette
import com.example.stocksgnal.ui.theme.StockSgnalTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StockSignalApp()
        }
    }
}

@Composable
fun StockSignalApp() {

    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("stock_signal_settings", android.content.Context.MODE_PRIVATE)
    }

    var selectedTab by remember {
        mutableIntStateOf(0)
    }

    var selectedTicker by remember {
        mutableStateOf<String?>(null)
    }

    var watchlistTicker by remember {
        mutableStateOf<String?>(null)
    }
    var strategyWatchlist by remember { mutableStateOf(false) }

    val menuResetKeys = remember { mutableStateListOf(0, 0, 0, 0, 0) }
    var refreshRevision by remember { mutableIntStateOf(0) }

    fun openMenu(index: Int, resetToStart: Boolean) {
        selectedTicker = null
        watchlistTicker = null
        strategyWatchlist = false
        if (resetToStart) menuResetKeys[index]++
        selectedTab = index
    }

    // 계좌·손익 및 키/주문 설정 화면의 캡처·화면 공유와 최근 앱 미리보기를 제한한다.
    // 차트·관심종목 등 다른 탭은 기존처럼 캡처할 수 있다.
    val activity = context as? Activity
    val refreshRepository = remember { LongStrategyRepository(context.applicationContext) }
    LaunchedEffect(activity) {
        val owner = activity as? ComponentActivity ?: return@LaunchedEffect
        // 이번 업데이트에서 한 번 자동 갱신을 기본으로 켠다. 이후 사용자가 수동을 고르면 그대로 따른다.
        if (!preferences.getBoolean("refresh_mode_migrated_20261003", false)) {
            preferences.edit().putString("refresh_mode", "hourly")
                .putBoolean("refresh_mode_migrated_20261003", true).apply()
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val mode = preferences.getString("refresh_mode", "hourly") ?: "hourly"
                val last = preferences.getLong("last_refresh_epoch_ms", 0L)
                if (mode == "hourly" && NamuSecretStore.load(context) != null &&
                    System.currentTimeMillis() - last >= RefreshCoordinator.INTERVAL_MS) {
                    if (RefreshCoordinator.refresh(context, refreshRepository) != null) refreshRevision++
                }
                delay(60_000L)
            }
        }
    }
    DisposableEffect(activity, selectedTab) {
        val window = activity?.window
        if (selectedTab == 2 || selectedTab == 3 || selectedTab == 4) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }

    val darkMode = true

    var palette by remember {
        mutableStateOf(
            AppPalette.entries.firstOrNull {
                it.name == preferences.getString("app_palette", AppPalette.BLUE.name)
            } ?: AppPalette.BLUE
        )
    }

    var background by remember {
        val savedBackground = preferences.getString("app_background", null)
        mutableStateOf(
            if (savedBackground.isNullOrBlank() || savedBackground == AppBackground.SOLID.name ||
                savedBackground == "CHART_WAVE" || savedBackground == "BRIGHT_NYC") {
                AppBackground.COUNTRY_FIELD
            } else {
                AppBackground.entries.firstOrNull { it.name == savedBackground }
                    ?: AppBackground.WALL_STREET
            }
        )
    }

    StockSgnalTheme(darkTheme = darkMode, palette = palette, background = background) {
        AppBackground(background = background, darkMode = darkMode) {

        val tabs = listOf("홈", "전략", "계좌", "손익", "설정")

        BackHandler(enabled = selectedTicker != null || watchlistTicker != null || selectedTab != 0) {
            if (selectedTicker != null) {
                selectedTicker = null
            } else if (watchlistTicker != null) {
                watchlistTicker = null
            } else {
                openMenu(0, resetToStart = false)
            }
        }

        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Top
            ),
            bottomBar = {
                StockBottomBar(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    onTabSelected = { index ->
                        // 현재 메뉴를 다시 눌러도 하위 화면·내부 탭·스크롤을 처음으로 돌린다.
                        openMenu(index, resetToStart = true)
                    }
                )
            }
        ) { padding ->

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(selectedTab) {
                        if (selectedTab == 0) {
                            var distance = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { distance = 0f },
                                onHorizontalDrag = { _, amount -> distance += amount },
                                onDragCancel = { distance = 0f },
                                onDragEnd = {
                                    if (kotlin.math.abs(distance) > 72f) {
                                        val next = (selectedTab + if (distance < 0) 1 else -1).coerceIn(0, tabs.lastIndex)
                                        if (next != selectedTab) openMenu(next, resetToStart = false)
                                    }
                                    distance = 0f
                                }
                            )
                        }
                    }
            ) {
                key(selectedTab, menuResetKeys[selectedTab]) {
                if (selectedTicker != null) {
                    StockChartScreen(
                        ticker = selectedTicker!!,
                        onBack = { selectedTicker = null }
                    )
                } else {
                    when (selectedTab) {
                        0 -> HomeScreen(dataRevision = refreshRevision, onStockClick = { selectedTicker = it })
                        1 -> if (strategyWatchlist) {
                            WatchlistScreen(selectedTicker = watchlistTicker,
                                onTickerSelected = { watchlistTicker = it },
                                onTickerBack = { watchlistTicker = null },
                                onBack = { strategyWatchlist = false })
                        } else SignalScreen(
                            onStockClick = { selectedTicker = it },
                            onOpenWatchlist = { strategyWatchlist = true }
                        )
                        2 -> ExecutionScreen(resetKey = menuResetKeys[2], dataRevision = refreshRevision,
                            onStockClick = { selectedTicker = it })
                        3 -> ProfitScreen(onStockClick = { selectedTicker = it })
                        4 -> SettingsScreen(
                            resetKey = menuResetKeys[4],
                            palette = palette,
                            onPaletteChange = {
                                palette = it
                                preferences.edit().putString("app_palette", it.name).apply()
                            },
                            background = background,
                            onBackgroundChange = {
                                background = it
                                preferences.edit().putString("app_background", it.name).apply()
                            }
                        )
                    }
                }
                }
            }
        }
        }
    }
}

@Composable
private fun StockBottomBar(
    tabs: List<String>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedTab == index
                val color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(34.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // 하단 메뉴 아이콘: res/drawable/ic_nav_*.xml (선 아이콘, 색은 tint)
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                when (index) {
                                    0 -> R.drawable.ic_nav_home
                                    1 -> R.drawable.ic_nav_strategy
                                    2 -> R.drawable.ic_nav_account
                                    3 -> R.drawable.ic_nav_profit
                                    else -> R.drawable.ic_nav_settings
                                }
                            ),
                            contentDescription = title,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = title,
                        color = color,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun TemporaryScreen(
    title: String,
    description: String
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
