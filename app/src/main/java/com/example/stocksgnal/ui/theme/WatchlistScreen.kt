package com.example.stocksgnal.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withContext
import com.example.stocksgnal.data.NamuMarketApi
import com.example.stocksgnal.data.WatchIndex
import com.example.stocksgnal.data.loadWatchIndices
import com.example.stocksgnal.data.saveWatchIndices
import com.example.stocksgnal.model.WatchStock
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import org.json.JSONArray
import org.json.JSONObject

private data class SymbolInfo(
    val symbol: String,
    val name: String,
    val type: String,
    val exchange: String
)

@Composable
fun WatchlistScreen(
    selectedTicker: String?,
    onTickerSelected: (String) -> Unit,
    onTickerBack: () -> Unit,
    onBack: (() -> Unit)? = null
) {

    if (selectedTicker != null) {
        StockChartScreen(
            ticker = selectedTicker,
            onBack = onTickerBack
        )
        return
    }

    val context = LocalContext.current

    val preferences = remember {
        context.getSharedPreferences("stock_signal_settings", Context.MODE_PRIVATE)
    }

    val symbolList = remember { loadSymbolList(context) }

    var stocks by remember { mutableStateOf(loadWatchStocks(preferences)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var quotes by remember { mutableStateOf<Map<String, NamuMarketApi.StockQuote>>(emptyMap()) }

    LaunchedEffect(stocks.map { it.ticker }) {
        quotes = withContext(kotlinx.coroutines.Dispatchers.IO) {
            buildMap {
                stocks.forEach { stock ->
                    NamuMarketApi.stockQuote(context, stock.ticker).getOrNull()?.let { put(stock.ticker, it) }
                }
            }
        }
    }

    fun updateStocks(newStocks: List<WatchStock>) {
        stocks = newStocks
        saveWatchStocks(preferences = preferences, stocks = newStocks)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(StockDesign.ScreenPadding)
    ) {

        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("내 자산", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("관심종목", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "✎", style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = { showAddDialog = true }) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
        }
        if (onBack != null) TextButton(onClick = onBack) { Text("‹ 전략") }
        Text("관심종목", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${stocks.size}개", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("이름순 ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(
        ) {
            items(items = stocks, key = { it.ticker }) { stock ->
                WatchStockRow(
                    stock = stock,
                    quote = quotes[stock.ticker],
                    editing = editing,
                    onClick = { onTickerSelected(stock.ticker) },
                    onEnabledChange = { enabled ->
                        updateStocks(stocks.map { if (it.ticker == stock.ticker) it.copy(enabled = enabled) else it })
                    },
                    onDelete = { updateStocks(stocks.filterNot { it.ticker == stock.ticker }) }
                )
            }
        }
    }

    if (showAddDialog) {
        AddStockDialog(
            symbolList = symbolList,
            onDismiss = { showAddDialog = false },
            onAdd = { selected ->
                if (stocks.none { it.ticker == selected.symbol }) {
                    updateStocks(
                        stocks + WatchStock(ticker = selected.symbol, name = selected.name, enabled = true)
                    )
                }
                showAddDialog = false
            }
        )
    }

}

@Composable
private fun WatchIndexCard(
    index: WatchIndex,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StockDesign.CardRadius),
        colors = glassCardColors()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(StockDesign.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = index.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = when (index.source) {
                        com.example.stocksgnal.data.IndexSource.CME_FUTURE -> "나무증권 · CME ${index.symbol} 근월물"
                        com.example.stocksgnal.data.IndexSource.INDEX_FX -> "나무증권 · ${index.symbol}"
                        com.example.stocksgnal.data.IndexSource.USD_KRW -> "나무증권 · 환율"
                        com.example.stocksgnal.data.IndexSource.US_TREASURY -> "미 재무부 공식 금리 · 종가"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = index.enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun WatchStockRow(
    stock: WatchStock,
    quote: NamuMarketApi.StockQuote?,
    editing: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {

    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stock.ticker.take(1), fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stock.ticker, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(stock.name, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            val price = quote?.price?.let { "${"%.2f".format(it)}" } ?: "-"
            val change = quote?.changePct?.let { String.format("%+.2f%%", it) } ?: "-"
            Row { Text(price); Spacer(Modifier.width(8.dp)); Text(change, color = MaterialTheme.colorScheme.tertiary) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("시가 배당률", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("-", style = MaterialTheme.typography.titleMedium)
        }
        if (editing) {
            TextButton(onClick = onDelete) { Text("삭제") }
            Switch(checked = stock.enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun AddStockDialog(
    symbolList: List<SymbolInfo>,
    onDismiss: () -> Unit,
    onAdd: (SymbolInfo) -> Unit
) {

    var query by remember {
        mutableStateOf("")
    }

    val cleanQuery =
        query
            .trim()
            .uppercase()

    val searchResults =
        remember(
            cleanQuery,
            symbolList
        ) {

            if (
                cleanQuery.length < 2
            ) {

                emptyList()

            } else {

                val tickerMatches =
                    symbolList.filter {

                        it.symbol.startsWith(
                            cleanQuery,
                            ignoreCase = true
                        )
                    }

                val nameMatches =
                    symbolList.filter {

                        !it.symbol.startsWith(
                            cleanQuery,
                            ignoreCase = true
                        ) &&
                                it.name.contains(
                                    cleanQuery,
                                    ignoreCase = true
                                )
                    }

                (tickerMatches +
                        nameMatches)
                    .distinctBy {
                        it.symbol
                    }
                    .take(8)
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("관심종목 추가")
        },

        text = {

            Column {

                Text(
                    text =
                        "티커 또는 종목명을 입력하세요."
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                OutlinedTextField(
                    value = query,

                    onValueChange = {
                        query = it
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    label = {
                        Text("종목 검색")
                    },

                    placeholder = {
                        Text(
                            "예: NVDA 또는 NVIDIA"
                        )
                    },

                    singleLine = true
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                if (
                    cleanQuery.length == 1
                ) {

                    Text(
                        text =
                            "2글자 이상 입력하면 검색됩니다.",

                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }

                if (
                    cleanQuery.length >= 2 &&
                    searchResults.isEmpty()
                ) {

                    Text(
                        text =
                            "검색 결과가 없습니다.",

                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }

                if (
                    searchResults.isNotEmpty()
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    searchResults.forEach {
                            result ->

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = 3.dp
                                )
                                .clickable {
                                    onAdd(result)
                                },

                            shape =
                                RoundedCornerShape(
                                    10.dp
                                )
                        ) {

                            Column(
                                modifier =
                                    Modifier.padding(
                                        12.dp
                                    )
                            ) {

                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),

                                    horizontalArrangement =
                                        Arrangement
                                            .SpaceBetween
                                ) {

                                    Text(
                                        text =
                                            result.symbol,

                                        fontWeight =
                                            FontWeight.Bold
                                    )

                                    Text(
                                        text =
                                            result.type,

                                        style =
                                            MaterialTheme
                                                .typography
                                                .labelSmall
                                    )
                                }

                                Spacer(
                                    modifier =
                                        Modifier.height(
                                            2.dp
                                        )
                                )

                                Text(
                                    text =
                                        result.name,

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )

                                Text(
                                    text =
                                        result.exchange,

                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall
                                )
                            }
                        }
                    }
                }
            }
        },

        confirmButton = {},

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("취소")
            }
        }
    )
}

private fun loadSymbolList(
    context: Context
): List<SymbolInfo> {

    return try {

        context.assets
            .open("us_symbols.csv")
            .bufferedReader()
            .useLines { lines ->

                lines
                    .drop(1)
                    .mapNotNull { line ->

                        parseCsvLine(
                            line
                        )
                    }
                    .toList()
            }

    } catch (_: Exception) {

        emptyList()
    }
}

private fun parseCsvLine(
    line: String
): SymbolInfo? {

    val columns =
        mutableListOf<String>()

    val current =
        StringBuilder()

    var insideQuotes = false

    var i = 0

    while (
        i < line.length
    ) {

        val char =
            line[i]

        when {

            char == '"' -> {

                if (
                    insideQuotes &&
                    i + 1 <
                    line.length &&
                    line[i + 1] == '"'
                ) {

                    current.append('"')

                    i++

                } else {

                    insideQuotes =
                        !insideQuotes
                }
            }

            char == ',' &&
                    !insideQuotes -> {

                columns.add(
                    current.toString()
                )

                current.clear()
            }

            else -> {

                current.append(
                    char
                )
            }
        }

        i++
    }

    columns.add(
        current.toString()
    )

    if (
        columns.size < 4
    ) {

        return null
    }

    return SymbolInfo(
        symbol =
            columns[0].trim(),

        name =
            columns[1].trim(),

        type =
            columns[2].trim(),

        exchange =
            columns[3].trim()
    )
}

private fun saveWatchStocks(
    preferences: SharedPreferences,
    stocks: List<WatchStock>
) {

    val array =
        JSONArray()

    stocks.forEach { stock ->

        val item =
            JSONObject()

        item.put(
            "ticker",
            stock.ticker
        )

        item.put(
            "name",
            stock.name
        )

        item.put(
            "enabled",
            stock.enabled
        )

        array.put(
            item
        )
    }

    preferences
        .edit()
        .putString(
            "watch_stocks",
            array.toString()
        )
        .apply()
}

private fun loadWatchStocks(
    preferences: SharedPreferences
): List<WatchStock> {

    val saved =
        preferences.getString(
            "watch_stocks",
            null
        )

    if (
        saved.isNullOrBlank()
    ) {

        return listOf(
            WatchStock(
                ticker = "QQQ",
                name =
                    "Invesco QQQ Trust",
                enabled = true
            ),

            WatchStock(
                ticker = "QLD",
                name =
                    "ProShares Ultra QQQ",
                enabled = true
            ),

            WatchStock(
                ticker = "USD",
                name =
                    "ProShares Ultra Semiconductors",
                enabled = true
            ),

            WatchStock(
                ticker = "GOOGL",
                name = "Alphabet",
                enabled = true
            )
        )
    }

    return try {

        val array =
            JSONArray(saved)

        buildList {

            for (
            i in 0 until
                    array.length()
            ) {

                val item =
                    array.getJSONObject(i)

                add(
                    WatchStock(
                        ticker =
                            item.getString(
                                "ticker"
                            ),

                        name =
                            item.optString(
                                "name",
                                "종목 정보 연결 전"
                            ),

                        enabled =
                            item.optBoolean(
                                "enabled",
                                true
                            )
                    )
                )
            }
        }

    } catch (_: Exception) {

        emptyList()
    }
}
