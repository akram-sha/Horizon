package org.horizon.model

import org.horizon.dto.Insight
import org.horizon.dto.PolygonArticle
import org.horizon.storage.DynamoWriter

object SentimentML {

    fun computePolarity(insights: List<Insight>): Double {
        if (insights.isEmpty()) return 0.0
        val positive = insights.count { it.sentiment == "positive" }.toDouble()
        val negative = insights.count { it.sentiment == "negative" }.toDouble()
        return (positive - negative) / insights.size.toDouble()
    }

    fun writeLeaderboard(articles: List<PolygonArticle>) {
        val tickerScores = mutableMapOf<String, MutableList<Double>>()

        for (article in articles) {
            for (ticker in article.tickers) {
                val polarity = computePolarity(article.insights.filter { it.ticker == ticker })
                tickerScores.getOrPut(ticker) { mutableListOf() }.add(polarity)
            }
        }

        val green = "\u001B[32m"
        val red   = "\u001B[31m"
        val reset = "\u001B[0m"

        println("––– Top 10 Tickers by Sentiment Polarity –––")
        tickerScores
            .filter { (_, scores) -> scores.size >= 5 }
            .mapValues { (_, scores) -> scores.average() }
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .forEach { (ticker, score) ->
                val bar   = if (score > 0) "▲" else "▼"
                val color = if (score > 0) green else red
                println("  $color$bar $ticker: ${"%.3f".format(score)}$reset")
                DynamoWriter.writePolarityScore(
                    ticker       = ticker,
                    timestamp    = java.time.Instant.now().toString(),
                    polarityScore = score,
                    articleCount  = tickerScores[ticker]?.size ?: 0
                )
            }
    }
}