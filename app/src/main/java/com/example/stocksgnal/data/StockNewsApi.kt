package com.example.stocksgnal.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

data class StockNewsItem(
    val title: String,
    val link: String,
    val publishedAt: String,
    val source: String
)

data class StockNewsResult(
    val items: List<StockNewsItem>,
    val errorMessage: String? = null
)

object StockNewsApi {

    fun getNews(ticker: String): StockNewsResult {
        if (ticker.isBlank()) return StockNewsResult(emptyList(), "종목 코드가 없습니다.")

        var connection: HttpURLConnection? = null
        return try {
            val query = URLEncoder.encode("${ticker.uppercase()} stock", "UTF-8")
            val url = URL(
                "https://news.google.com/rss/search?q=$query&hl=ko&gl=KR&ceid=KR:ko"
            )
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "StockSignal/1.0")

            if (connection.responseCode !in 200..299) {
                return StockNewsResult(emptyList(), "뉴스 서버 오류 · HTTP ${connection.responseCode}")
            }

            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(connection.inputStream)
            val nodes = document.getElementsByTagName("item")
            val items = buildList {
                for (index in 0 until minOf(nodes.length, 30)) {
                    val element = nodes.item(index)
                    fun text(tag: String): String =
                        (element as org.w3c.dom.Element)
                            .getElementsByTagName(tag)
                            .item(0)
                            ?.textContent
                            ?.trim()
                            .orEmpty()

                    val title = text("title")
                    val source = text("source")
                    val link = text("link")
                    if (title.isNotBlank() && link.isNotBlank()) {
                        add(
                            StockNewsItem(
                                title = title,
                                link = link,
                                publishedAt = text("pubDate"),
                                source = source
                            )
                        )
                    }
                }
            }
            StockNewsResult(items)
        } catch (error: Exception) {
            StockNewsResult(emptyList(), "뉴스를 불러오지 못했습니다: ${error.message ?: "알 수 없는 오류"}")
        } finally {
            connection?.disconnect()
        }
    }
}
