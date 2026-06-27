package org.horizon

import kotlinx.serialization.json.Json
import org.horizon.dto.PolygonArticle
import org.horizon.model.SentimentML

fun main() {
    val json = Json { ignoreUnknownKeys = true }

    val jsonText = object {}.javaClass
        .getResourceAsStream("/polygon_news.json")
        ?.bufferedReader()
        ?.readText()
        ?: error("Could not find polygon_news.json in resources")

    val articles = json.decodeFromString<List<PolygonArticle>>(jsonText)
    println("Articles loaded: ${articles.size}\n")

    SentimentML.writeLeaderboard(articles)
}