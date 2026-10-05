package com.example.stocksgnal.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.data.CandleStorage
import com.example.stocksgnal.data.StockNewsApi
import com.example.stocksgnal.data.StockNewsItem
import com.example.stocksgnal.strategy.DailyCandle
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt


@Composable
fun StockChartScreen(
    ticker: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val allCandles = CandleStorage.loadCandles(
        context = context,
        ticker = ticker
    )

    var chartVisibleCount by remember(allCandles.size) {
        mutableIntStateOf(
            minOf(100, allCandles.size).coerceAtLeast(1)
        )
    }

    var endIndex by remember(allCandles.size) {
        mutableIntStateOf(allCandles.size)
    }

    var dragAccumulator by remember {
        mutableFloatStateOf(0f)
    }

    var selectedIndex by remember {
        mutableStateOf<Int?>(null)
    }

    var selectedDetailTab by rememberSaveable(ticker) {
        mutableIntStateOf(0)
    }

    val startIndex = max(
        0,
        endIndex - chartVisibleCount
    )

    val visibleCandles =
        if (allCandles.isEmpty()) {
            emptyList()
        } else {
            allCandles.subList(
                startIndex,
                endIndex.coerceAtMost(allCandles.size)
            )
        }

    BackHandler(onBack = onBack)

    val isTradeTicker = remember(ticker) { isLongTradeTicker(context, ticker) }
    val detailTabs = if (isTradeTicker) listOf("실행", "차트", "뉴스") else listOf("차트", "뉴스")
    Column(modifier = Modifier.fillMaxSize().swipeTabs(selectedDetailTab, detailTabs.size) { selectedDetailTab = it }) {

        ChartTopBar(title = ticker, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = StockDesign.ScreenPadding)
                .padding(bottom = StockDesign.ScreenPadding)
        ) {

        Text(
            text = ticker,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "일봉 · 저장 데이터 ${allCandles.size}일",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 계곡다이빙 매수 종목(QLD, USD 등)은 [실행] 탭이 먼저 열린다
        val currentTab = detailTabs.getOrElse(selectedDetailTab) { detailTabs.first() }

        PrimaryTabRow(selectedTabIndex = detailTabs.indexOf(currentTab)) {
            detailTabs.forEachIndexed { index, label ->
                Tab(
                    selected = currentTab == label,
                    onClick = { selectedDetailTab = index },
                    text = { Text(label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (currentTab == "실행") {
            ExecutionPanel(ticker)
        } else if (currentTab == "뉴스") {
            StockNewsPanel(ticker = ticker)
        } else {

        if (visibleCandles.isEmpty()) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = glassCardColors(),
                border = toneBorder(CardTone.Chart),
                shape = RoundedCornerShape(StockDesign.CardRadius)
            ) {
                Text(
                    text = "저장된 데이터가 없습니다.\n홈에서 새로고침을 먼저 실행하세요.",
                    modifier = Modifier.padding(StockDesign.CardPadding)
                )
            }

        } else {

            val displayIndex =
                selectedIndex ?: (endIndex - 1)

            val displayCandle =
                allCandles[displayIndex]

            val previous =
                allCandles.getOrNull(displayIndex - 1)

            val changePercent =
                if (previous != null && previous.close != 0.0) {
                    (
                            (displayCandle.close - previous.close) /
                                    previous.close
                            ) * 100.0
                } else {
                    0.0
                }

            val rsi14Values = calculateRsi(
                candles = allCandles,
                period = 14
            )

            val displayRsi =
                rsi14Values.getOrNull(displayIndex)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Column {

                    Text(
                        text =
                            if (selectedIndex != null)
                                "선택 종가"
                            else
                                "종가",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Text(
                        text = "$${"%.2f".format(displayCandle.close)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text =
                            "${if (changePercent >= 0) "+" else ""}" +
                                    "${"%.2f".format(changePercent)}%"
                    )
                }

                Column {

                    Text(
                        text = displayCandle.date,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight =
                            if (selectedIndex != null)
                                FontWeight.Bold
                            else
                                FontWeight.Normal
                    )

                    Text(
                        text =
                            "O ${"%.2f".format(displayCandle.open)}  " +
                                    "H ${"%.2f".format(displayCandle.high)}"
                    )

                    Text(
                        text =
                            "L ${"%.2f".format(displayCandle.low)}  " +
                                    "C ${"%.2f".format(displayCandle.close)}"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = glassCardColors(),
                border = toneBorder(CardTone.Chart),
                shape = RoundedCornerShape(StockDesign.CardRadius)
            ) {

                Column(
                    modifier = Modifier.padding(12.dp)
                ) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        Text(
                            text = "MA20",
                            style = MaterialTheme.typography.labelMedium
                        )

                        Text(
                            text = "MA50",
                            style = MaterialTheme.typography.labelMedium
                        )

                        Text(
                            text = "MA200",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {

                        Text(
                            text = "클릭: 날짜 확인 · 좌우이동 · 확대/축소",
                            style = MaterialTheme.typography.labelSmall
                        )

                        Text(
                            text = "${visibleCandles.size}일",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    CandleChart(
                        allCandles = allCandles,
                        startIndex = startIndex,
                        visibleCandles = visibleCandles,
                        selectedIndex = selectedIndex,

                        onSelect = { index ->
                            selectedIndex = index
                        },

                        onTransform = { panX, zoom ->

                            selectedIndex = null

                            if (allCandles.size > 1) {

                                if (
                                    zoom > 1.01f ||
                                    zoom < 0.99f
                                ) {

                                    val maxVisible =
                                        minOf(
                                            200,
                                            allCandles.size
                                        )

                                    val minVisible =
                                        minOf(
                                            30,
                                            maxVisible
                                        )

                                    val newCount =
                                        (
                                                chartVisibleCount /
                                                        zoom
                                                )
                                            .roundToInt()
                                            .coerceIn(
                                                minVisible,
                                                maxVisible
                                            )

                                    if (newCount != chartVisibleCount) {

                                        chartVisibleCount = newCount

                                        if (endIndex < chartVisibleCount) {
                                            endIndex = chartVisibleCount
                                        }
                                    }
                                }

                                dragAccumulator += panX

                                val dragThreshold = 24f

                                if (
                                    abs(dragAccumulator) >=
                                    dragThreshold
                                ) {

                                    val shift =
                                        (
                                                dragAccumulator /
                                                        dragThreshold
                                                ).toInt()

                                    endIndex =
                                        (
                                                endIndex - shift
                                                )
                                            .coerceIn(
                                                chartVisibleCount,
                                                allCandles.size
                                            )

                                    dragAccumulator -=
                                        shift * dragThreshold
                                }
                            }
                        },

                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    HorizontalDivider()

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "거래량",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    VolumeChart(
                        candles = visibleCandles,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = glassCardColors(),
                border = toneBorder(CardTone.Chart),
                shape = RoundedCornerShape(StockDesign.CardRadius)
            ) {

                Column(
                    modifier = Modifier.padding(12.dp)
                ) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {

                        Text(
                            text = "RSI(14)",
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text =
                                displayRsi?.let {
                                    "%.1f".format(it)
                                } ?: "-",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    RsiChart(
                        values = rsi14Values,
                        startIndex = startIndex,
                        visibleCount = visibleCandles.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {

                        Text(
                            text = "30 과매도",
                            style = MaterialTheme.typography.labelSmall
                        )

                        Text(
                            text = "50",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "70 과매수",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val highest =
                visibleCandles.maxOf {
                    it.high
                }

            val lowest =
                visibleCandles.minOf {
                    it.low
                }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Text(
                    text =
                        "${visibleCandles.size}일 고가  " +
                                "$${"%.2f".format(highest)}"
                )

                Text(
                    text =
                        "저가  " +
                                "$${"%.2f".format(lowest)}"
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text =
                    if (selectedIndex != null) {
                        "선택 거래량  ${formatVolume(displayCandle.volume)}"
                    } else {
                        "최근 거래량  ${formatVolume(displayCandle.volume)}"
                    },
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
        }
    }
    }
}


@Composable
private fun StockNewsPanel(ticker: String) {
    val context = LocalContext.current
    var loading by remember(ticker) { mutableStateOf(true) }
    var news by remember(ticker) { mutableStateOf<List<StockNewsItem>>(emptyList()) }
    var errorMessage by remember(ticker) { mutableStateOf<String?>(null) }
    var reloadKey by remember(ticker) { mutableIntStateOf(0) }

    LaunchedEffect(ticker, reloadKey) {
        loading = true
        errorMessage = null
        val result = withContext(Dispatchers.IO) {
            StockNewsApi.getNews(ticker)
        }
        news = result.items
        errorMessage = result.errorMessage
        loading = false
    }

    when {
        loading -> Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp)
        ) {
            CircularProgressIndicator()
        }

        errorMessage != null -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = glassCardColors(),
                border = toneBorder(CardTone.News),
            shape = RoundedCornerShape(StockDesign.CardRadius)
        ) {
            Column(modifier = Modifier.padding(StockDesign.CardPadding)) {
                Text(errorMessage.orEmpty())
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { reloadKey += 1 }) {
                    Text("다시 시도")
                }
            }
        }

        news.isEmpty() -> Text("표시할 최신 뉴스가 없습니다.")

        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "$ticker 관련 최신 뉴스",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            news.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = glassCardColors(),
                border = toneBorder(CardTone.News),
                    shape = RoundedCornerShape(StockDesign.CardRadius),
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(item.link))
                        )
                    }
                ) {
                    Column(modifier = Modifier.padding(StockDesign.CardPadding)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (item.source.isNotBlank() || item.publishedAt.isNotBlank()) {
                            Spacer(modifier = Modifier.height(7.dp))
                            Text(
                                text = listOf(item.source, item.publishedAt)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun CandleChart(
    allCandles: List<DailyCandle>,
    startIndex: Int,
    visibleCandles: List<DailyCandle>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onTransform: (
        panX: Float,
        zoom: Float
    ) -> Unit,
    modifier: Modifier = Modifier
) {

    val gridColor =
        MaterialTheme.colorScheme.outlineVariant

    val ma20Color =
        MaterialTheme.colorScheme.primary

    val ma50Color =
        MaterialTheme.colorScheme.tertiary

    val ma200Color =
        MaterialTheme.colorScheme.secondary

    val selectionColor =
        MaterialTheme.colorScheme.onSurface.copy(
            alpha = 0.7f
        )

    val upColor =
        Color(0xFFE53935)

    val downColor =
        Color(0xFF1E88E5)

    val ma20 =
        movingAverage(allCandles, 20)

    val ma50 =
        movingAverage(allCandles, 50)

    val ma200 =
        movingAverage(allCandles, 200)

    val prices =
        mutableListOf<Double>()

    visibleCandles.forEach { candle ->
        prices.add(candle.high)
        prices.add(candle.low)
    }

    visibleCandles.indices.forEach { localIndex ->

        val globalIndex =
            startIndex + localIndex

        ma20.getOrNull(globalIndex)?.let {
            prices.add(it)
        }

        ma50.getOrNull(globalIndex)?.let {
            prices.add(it)
        }

        ma200.getOrNull(globalIndex)?.let {
            prices.add(it)
        }
    }

    var minPrice =
        prices.minOrNull() ?: 0.0

    var maxPrice =
        prices.maxOrNull() ?: 1.0

    if (maxPrice == minPrice) {
        maxPrice += 1.0
        minPrice -= 1.0
    }

    val rawRange =
        maxPrice - minPrice

    minPrice -= rawRange * 0.05
    maxPrice += rawRange * 0.05

    val priceRange =
        maxPrice - minPrice

    val currentOnTransform by
    rememberUpdatedState(onTransform)

    val currentOnSelect by
    rememberUpdatedState(onSelect)

    Canvas(
        modifier = modifier

            // 좌우이동 / 확대축소
            .pointerInput(Unit) {

                detectTransformGestures {
                        _,
                        pan,
                        zoom,
                        _ ->

                    currentOnTransform(
                        pan.x,
                        zoom
                    )
                }
            }

            // 클릭 / 탭
            .pointerInput(
                visibleCandles.size,
                startIndex
            ) {

                detectTapGestures { tap ->

                    if (visibleCandles.isNotEmpty()) {

                        val slotWidth =
                            size.width.toFloat() /
                                    visibleCandles.size

                        val localIndex =
                            (
                                    tap.x /
                                            slotWidth
                                    )
                                .toInt()
                                .coerceIn(
                                    0,
                                    visibleCandles.lastIndex
                                )

                        currentOnSelect(
                            startIndex + localIndex
                        )
                    }
                }
            }
    ) {

        if (visibleCandles.isEmpty()) {
            return@Canvas
        }

        for (i in 0..4) {

            val y =
                size.height * i / 4f

            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }

        val slotWidth =
            size.width /
                    visibleCandles.size

        val candleWidth =
            max(
                2f,
                slotWidth * 0.62f
            )

        fun priceToY(
            price: Double
        ): Float {

            val normalized =
                (
                        (price - minPrice) /
                                priceRange
                        ).toFloat()

            return size.height -
                    normalized * size.height
        }

        visibleCandles.forEachIndexed {
                index,
                candle ->

            val x =
                slotWidth * index +
                        slotWidth / 2f

            val highY =
                priceToY(candle.high)

            val lowY =
                priceToY(candle.low)

            val openY =
                priceToY(candle.open)

            val closeY =
                priceToY(candle.close)

            val candleColor =
                if (candle.close >= candle.open) {
                    upColor
                } else {
                    downColor
                }

            drawLine(
                color = candleColor,
                start = Offset(x, highY),
                end = Offset(x, lowY),
                strokeWidth = 2f
            )

            val top =
                minOf(
                    openY,
                    closeY
                )

            val bottom =
                maxOf(
                    openY,
                    closeY
                )

            val bodyHeight =
                max(
                    2f,
                    bottom - top
                )

            drawRect(
                color = candleColor,
                topLeft = Offset(
                    x - candleWidth / 2f,
                    top
                ),
                size = Size(
                    candleWidth,
                    bodyHeight
                )
            )
        }

        fun drawMa(
            values: List<Double?>,
            color: Color
        ) {

            val path =
                Path()

            var started =
                false

            visibleCandles.indices.forEach { localIndex ->

                val globalIndex =
                    startIndex + localIndex

                val value =
                    values.getOrNull(globalIndex)

                if (value != null) {

                    val x =
                        slotWidth * localIndex +
                                slotWidth / 2f

                    val y =
                        priceToY(value)

                    if (!started) {

                        path.moveTo(x, y)
                        started = true

                    } else {

                        path.lineTo(x, y)
                    }
                }
            }

            if (started) {

                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 2.5f,
                        cap = StrokeCap.Round
                    )
                )
            }
        }

        drawMa(ma20, ma20Color)
        drawMa(ma50, ma50Color)
        drawMa(ma200, ma200Color)

        // 선택한 캔들 표시
        selectedIndex?.let { globalIndex ->

            val localIndex =
                globalIndex - startIndex

            if (
                localIndex >= 0 &&
                localIndex < visibleCandles.size
            ) {

                val candle =
                    visibleCandles[localIndex]

                val x =
                    slotWidth * localIndex +
                            slotWidth / 2f

                val closeY =
                    priceToY(candle.close)

                drawLine(
                    color = selectionColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.5f
                )

                drawCircle(
                    color = selectionColor,
                    radius = 6f,
                    center = Offset(
                        x,
                        closeY
                    )
                )
            }
        }
    }
}


@Composable
private fun VolumeChart(
    candles: List<DailyCandle>,
    modifier: Modifier = Modifier
) {

    val upColor =
        Color(0xFFE53935)

    val downColor =
        Color(0xFF1E88E5)

    val gridColor =
        MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
    ) {

        if (candles.isEmpty()) {
            return@Canvas
        }

        val maxVolume =
            candles
                .maxOfOrNull {
                    it.volume
                }
                ?.coerceAtLeast(1.0)
                ?: 1.0

        val slotWidth =
            size.width /
                    candles.size

        val barWidth =
            max(
                1.5f,
                slotWidth * 0.62f
            )

        drawLine(
            color = gridColor,
            start = Offset(
                0f,
                size.height / 2f
            ),
            end = Offset(
                size.width,
                size.height / 2f
            ),
            strokeWidth = 1f
        )

        candles.forEachIndexed {
                index,
                candle ->

            val x =
                slotWidth * index +
                        slotWidth / 2f

            val ratio =
                (
                        candle.volume /
                                maxVolume
                        )
                    .toFloat()
                    .coerceIn(
                        0f,
                        1f
                    )

            val barHeight =
                size.height * ratio

            val barColor =
                if (candle.close >= candle.open) {
                    upColor
                } else {
                    downColor
                }

            drawRect(
                color = barColor,
                topLeft = Offset(
                    x - barWidth / 2f,
                    size.height - barHeight
                ),
                size = Size(
                    barWidth,
                    barHeight
                )
            )
        }
    }
}


@Composable
private fun RsiChart(
    values: List<Double?>,
    startIndex: Int,
    visibleCount: Int,
    modifier: Modifier = Modifier
) {

    val lineColor =
        MaterialTheme.colorScheme.primary

    val thresholdColor =
        MaterialTheme.colorScheme.outline

    val middleLineColor =
        MaterialTheme.colorScheme
            .onSurface
            .copy(alpha = 0.7f)

    Canvas(
        modifier = modifier
    ) {

        if (visibleCount <= 0) {
            return@Canvas
        }

        fun rsiToY(
            value: Double
        ): Float {

            return size.height -
                    (
                            value
                                .coerceIn(
                                    0.0,
                                    100.0
                                ) /
                                    100.0
                            )
                        .toFloat() *
                    size.height
        }

        drawLine(
            color = thresholdColor,
            start = Offset(
                0f,
                rsiToY(70.0)
            ),
            end = Offset(
                size.width,
                rsiToY(70.0)
            ),
            strokeWidth = 1.5f
        )

        // 50선 더 진하고 굵게
        drawLine(
            color = middleLineColor,
            start = Offset(
                0f,
                rsiToY(50.0)
            ),
            end = Offset(
                size.width,
                rsiToY(50.0)
            ),
            strokeWidth = 3.5f
        )

        drawLine(
            color = thresholdColor,
            start = Offset(
                0f,
                rsiToY(30.0)
            ),
            end = Offset(
                size.width,
                rsiToY(30.0)
            ),
            strokeWidth = 1.5f
        )

        val slotWidth =
            size.width /
                    visibleCount

        val path =
            Path()

        var started =
            false

        for (
        localIndex in
        0 until visibleCount
        ) {

            val globalIndex =
                startIndex +
                        localIndex

            val value =
                values.getOrNull(
                    globalIndex
                )

            if (value != null) {

                val x =
                    slotWidth * localIndex +
                            slotWidth / 2f

                val y =
                    rsiToY(value)

                if (!started) {

                    path.moveTo(x, y)
                    started = true

                } else {

                    path.lineTo(x, y)
                }
            }
        }

        if (started) {

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round
                )
            )
        }
    }
}


private fun movingAverage(
    candles: List<DailyCandle>,
    period: Int
): List<Double?> {

    if (candles.isEmpty()) {
        return emptyList()
    }

    val result =
        MutableList<Double?>(
            candles.size
        ) {
            null
        }

    var sum = 0.0

    for (i in candles.indices) {

        sum +=
            candles[i].close

        if (i >= period) {

            sum -=
                candles[
                    i - period
                ].close
        }

        if (i >= period - 1) {

            result[i] =
                sum /
                        period
        }
    }

    return result
}


private fun calculateRsi(
    candles: List<DailyCandle>,
    period: Int = 14
): List<Double?> {

    val result =
        MutableList<Double?>(
            candles.size
        ) {
            null
        }

    if (candles.size <= period) {
        return result
    }

    var gainSum = 0.0
    var lossSum = 0.0

    for (i in 1..period) {

        val change =
            candles[i].close -
                    candles[i - 1].close

        if (change >= 0) {

            gainSum += change

        } else {

            lossSum += -change
        }
    }

    var averageGain =
        gainSum / period

    var averageLoss =
        lossSum / period

    fun rsi(
        gain: Double,
        loss: Double
    ): Double {

        if (
            loss == 0.0 &&
            gain == 0.0
        ) {
            return 50.0
        }

        if (loss == 0.0) {
            return 100.0
        }

        if (gain == 0.0) {
            return 0.0
        }

        val rs =
            gain / loss

        return 100.0 -
                (
                        100.0 /
                                (1.0 + rs)
                        )
    }

    result[period] =
        rsi(
            averageGain,
            averageLoss
        )

    for (
    i in
    period + 1 until candles.size
    ) {

        val change =
            candles[i].close -
                    candles[i - 1].close

        val gain =
            if (change > 0) {
                change
            } else {
                0.0
            }

        val loss =
            if (change < 0) {
                -change
            } else {
                0.0
            }

        averageGain =
            (
                    averageGain *
                            (period - 1) +
                            gain
                    ) /
                    period

        averageLoss =
            (
                    averageLoss *
                            (period - 1) +
                            loss
                    ) /
                    period

        result[i] =
            rsi(
                averageGain,
                averageLoss
            )
    }

    return result
}


private fun formatVolume(
    volume: Double
): String {

    return when {

        volume >= 1_000_000_000 ->
            "${
                "%.2f".format(
                    volume /
                            1_000_000_000
                )
            }B"

        volume >= 1_000_000 ->
            "${
                "%.2f".format(
                    volume /
                            1_000_000
                )
            }M"

        volume >= 1_000 ->
            "${
                "%.1f".format(
                    volume /
                            1_000
                )
            }K"

        else ->
            "%.0f".format(volume)
    }
}


/* =========================
   상단 바: 상태바 아래 고정, 굵은 뒤로가기 화살표, 넓은 터치 영역
   ========================= */

@Composable
private fun ChartTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clickable(onClick = onBack),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            val color = MaterialTheme.colorScheme.onBackground
            Canvas(modifier = Modifier.size(26.dp)) {
                val w = size.width
                val h = size.height
                val stroke = 3.2.dp.toPx()
                // 가로선
                drawLine(color, Offset(w * 0.12f, h / 2), Offset(w * 0.9f, h / 2), strokeWidth = stroke, cap = StrokeCap.Round)
                // 화살촉
                drawLine(color, Offset(w * 0.12f, h / 2), Offset(w * 0.48f, h * 0.14f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.12f, h / 2), Offset(w * 0.48f, h * 0.86f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        Text(
            text = "뒤로",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 12.dp, horizontal = 4.dp)
        )
    }
}
