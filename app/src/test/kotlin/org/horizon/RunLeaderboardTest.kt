package org.horizon

import org.horizon.dto.PolygonArticle
import org.horizon.model.SentimentScorer
import org.horizon.storage.PolarityStore
import kotlin.test.Test
import kotlin.test.assertEquals

class RunLeaderboardTest {

    private fun article(title: String, vararg tickers: String) = PolygonArticle(
        title        = title,
        tickers      = tickers.toList(),
        publishedUtc = "2024-01-01T00:00:00Z"
    )

    private val noOpStore = PolarityStore { _, _, _, _ -> }

    @Test
    fun `scores each article once regardless of ticker count`() {
        var calls = 0
        val scorer = SentimentScorer { _ -> calls++; 0.5 }

        runLeaderboard(listOf(article("Good news", "AAPL", "MSFT", "GOOG")), scorer, noOpStore)

        assertEquals(1, calls)
    }

    @Test
    fun `filters out tickers with fewer than 5 articles`() {
        val written = mutableListOf<String>()
        val store = PolarityStore { ticker, _, _, _ -> written.add(ticker) }

        val articles = List(5) { article("Article", "AAPL") } +
                       List(4) { article("Article", "MSFT") }
        runLeaderboard(articles, SentimentScorer { 0.5 }, store)

        assertEquals(listOf("AAPL"), written)
    }

    @Test
    fun `writes at most 10 tickers`() {
        val written = mutableListOf<String>()
        val store = PolarityStore { ticker, _, _, _ -> written.add(ticker) }

        val tickers = (1..15).map { "T%02d".format(it) }
        val articles = tickers.flatMap { t -> List(5) { article("Article", t) } }
        runLeaderboard(articles, SentimentScorer { 0.5 }, store)

        assertEquals(10, written.size)
    }

    @Test
    fun `writes tickers in descending score order`() {
        val written = mutableListOf<String>()
        val store = PolarityStore { ticker, _, _, _ -> written.add(ticker) }

        val articles = List(5) { article("Good", "HIGH") } +
                       List(5) { article("Bad",  "LOW")  }
        val scorer = SentimentScorer { a -> if ("Good" in a.title) 0.9 else 0.1 }
        runLeaderboard(articles, scorer, store)

        assertEquals(listOf("HIGH", "LOW"), written)
    }
}
