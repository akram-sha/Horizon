package org.horizon

import kotlinx.serialization.json.Json
import org.horizon.dto.PolygonArticle
import org.horizon.model.SentimentML
import org.horizon.model.SentimentScorer
import org.horizon.storage.DynamoWriter
import org.horizon.storage.PolarityStore
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("org.horizon.App")

fun main() {
    val json = Json { ignoreUnknownKeys = true }

    val jsonText = object {}.javaClass
        .getResourceAsStream("/polygon_news.json")
        ?.bufferedReader()
        ?.readText()
        ?: error("Could not find polygon_news.json in resources")

    val articles = json.decodeFromString<List<PolygonArticle>>(jsonText)
    logger.info("Articles loaded: {}", articles.size)

    runLeaderboard(articles, scorer = SentimentML, store = DynamoWriter)
}

fun runLeaderboard(
    articles: List<PolygonArticle>,
    scorer: SentimentScorer,
    store: PolarityStore,
) {
    val tickerScores = mutableMapOf<String, MutableList<Double>>()

    for (article in articles) {
        val polarity = scorer.computePolarity(article)
        for (ticker in article.tickers) {
            tickerScores.getOrPut(ticker) { mutableListOf() }.add(polarity)
        }
    }

    val timestamp = Instant.now().toString()

    logger.info("Top 10 tickers by sentiment polarity")
    tickerScores
        .filter  { (_, scores) -> scores.size >= 5 }
        .mapValues { (_, scores) -> scores.average() }
        .entries
        .sortedByDescending { it.value }
        .take(10)
        .forEach { (ticker, score) ->
            val arrow = if (score > 0) "▲" else "▼"
            logger.info("  {} {} {}", arrow, ticker, "%.3f".format(score))
            store.writePolarityScore(
                ticker       = ticker,
                timestamp    = timestamp,
                score        = score,
                articleCount = tickerScores[ticker]?.size ?: 0
            )
        }
}
