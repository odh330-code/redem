package com.example.stocksgnal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.data.StockNewsApi
import com.example.stocksgnal.data.StockNewsItem
import com.example.stocksgnal.data.loadConfiguredWatchStocks
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class WatchlistNews(
    val ticker: String,
    val item: StockNewsItem
)

@Composable
fun NewsOverviewScreen(onStockClick: (String) -> Unit) {
    val context = LocalContext.current
    val stocks = remember { loadConfiguredWatchStocks(context).filter { it.enabled } }
    var loading by remember { mutableStateOf(true) }
    var articles by remember { mutableStateOf<List<WatchlistNews>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            stocks.flatMap { stock ->
                StockNewsApi.getNews(stock.ticker).items.take(3).map { item ->
                    WatchlistNews(stock.ticker, item)
                }
            }
        }
        articles = result
        if (result.isEmpty() && stocks.isNotEmpty()) error = "불러올 뉴스가 없습니다."
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(StockDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(StockDesign.CardGap)
    ) {
        item {
            Text("관심종목 뉴스", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("ON 상태인 관심종목의 최신 뉴스입니다.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
        }

        when {
            loading -> item { CircularProgressIndicator() }
            stocks.isEmpty() -> item { Text("관심종목 관리에서 종목을 추가하고 ON으로 설정하세요.") }
            error != null -> item {
                Column {
                    Text(error.orEmpty())
                    Button(onClick = { reloadKey += 1 }) { Text("다시 시도") }
                }
            }
            else -> items(articles, key = { "${it.ticker}-${it.item.link}" }) { article ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onStockClick(article.ticker) },
                    colors = glassCardColors(),
                    border = toneBorder(CardTone.News),
                    shape = RoundedCornerShape(StockDesign.CardRadius)
                ) {
                    Column(Modifier.padding(StockDesign.CardPadding)) {
                        Text(article.ticker, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(article.item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            listOf(article.item.source, article.item.publishedAt)
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
