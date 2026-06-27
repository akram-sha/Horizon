package org.horizon

import org.horizon.dto.Insight
import org.horizon.model.SentimentML
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test
    fun testPolarityPositive() {
        val insights = listOf(
            Insight(ticker = "AAPL", sentiment = "positive"),
            Insight(ticker = "AAPL", sentiment = "positive"),
            Insight(ticker = "AAPL", sentiment = "negative")
        )
        val score = SentimentML.computePolarity(insights)
        assertEquals(0.333, score, absoluteTolerance = 0.001)
    }

    @Test
    fun testPolarityEmpty() {
        assertEquals(0.0, SentimentML.computePolarity(emptyList()))
    }
}