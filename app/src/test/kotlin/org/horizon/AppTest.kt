package org.horizon

import org.horizon.dto.PolygonArticle
import org.horizon.model.SentimentML
import kotlin.test.Test
import kotlin.test.assertTrue

class AppTest {

    private fun makeArticle(title: String, description: String? = null) = PolygonArticle(
        title        = title,
        description  = description,
        tickers      = emptyList(),
        insights     = emptyList(),
        publishedUtc = "2024-01-01T00:00:00Z"
    )

    @Test
    fun testPolarityPositiveSentence() {
        val article = makeArticle("Revenues increased by 20% year-on-year beating all expectations.")
        val score = SentimentML.computePolarity(article)
        assertTrue(score > 0.0, "Expected positive polarity, got $score")
    }

    @Test
    fun testPolarityNegativeSentence() {
        val article = makeArticle("The company filed for bankruptcy and suspended all operations.")
        val score = SentimentML.computePolarity(article)
        assertTrue(score < 0.0, "Expected negative polarity, got $score")
    }

    @Test
    fun testPolarityEmptyText() {
        val article = makeArticle("")
        val score = SentimentML.computePolarity(article)
        assertTrue(score == 0.0, "Expected 0.0 for blank text, got $score")
    }
}