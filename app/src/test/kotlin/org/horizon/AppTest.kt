package org.horizon

import org.horizon.dto.PolygonArticle
import org.horizon.model.SentimentML
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two-tier test strategy:
 *
 * 1. Contract tests (no model required) — always run in CI.
 *    Validate output shape and boundary conditions only.
 *
 * 2. Sign tests (real model required) — disabled in CI via @DisabledIfEnvironmentVariable.
 *    Verify FinBERT produces directionally correct scores. Run locally after `bash setup.sh`.
 *    To enable in CI: commit a quantised test fixture model and remove the annotations.
 */
class AppTest {

    companion object {
        private val modelPresent: Boolean = try {
            val onnxUrl      = AppTest::class.java.getResource("/finbert.onnx")
            val tokenizerUrl = AppTest::class.java.getResource("/tokenizer.json")
            if (onnxUrl == null || tokenizerUrl == null) false
            else {
                java.nio.file.Files.exists(java.nio.file.Paths.get(onnxUrl.toURI())) &&
                java.nio.file.Files.exists(java.nio.file.Paths.get(tokenizerUrl.toURI()))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun makeArticle(title: String, description: String? = null) = PolygonArticle(
        title        = title,
        description  = description,
        tickers      = emptyList(),
        insights     = emptyList(),
        publishedUtc = "2024-01-01T00:00:00Z"
    )

    // --- Contract tests: always run ---

    @Test
    fun `blank text returns exactly zero`() {
        val article = makeArticle("")
        val score = SentimentML.computePolarity(article)
        assertTrue(score == 0.0, "Expected 0.0 for blank text, got $score")
    }

    // --- Model-dependent tests: skipped automatically if finbert.onnx not present ---

    @Test
    fun `computePolarity returns a finite value in valid range`() {
        assumeTrue(modelPresent, "Skipping: finbert.onnx not on classpath — run bash setup.sh first")
        val article = makeArticle("Revenues increased by 20% year-on-year.")
        val score = SentimentML.computePolarity(article)
        assertTrue(!score.isNaN(),      "Score must not be NaN, got $score")
        assertTrue(!score.isInfinite(), "Score must not be infinite, got $score")
        assertTrue(score in -1.0..1.0,  "Score $score out of expected range [-1, 1]")
    }

    @Test
    fun `positive sentence scores above zero`() {
        assumeTrue(modelPresent, "Skipping: finbert.onnx not on classpath — run bash setup.sh first")
        val article = makeArticle("Revenues increased by 20% year-on-year beating all expectations.")
        val score = SentimentML.computePolarity(article)
        assertTrue(score > 0.0, "Expected positive polarity, got $score")
    }

    @Test
    fun `negative sentence scores below zero`() {
        assumeTrue(modelPresent, "Skipping: finbert.onnx not on classpath — run bash setup.sh first")
        val article = makeArticle("The company filed for bankruptcy and suspended all operations.")
        val score = SentimentML.computePolarity(article)
        assertTrue(score < 0.0, "Expected negative polarity, got $score")
    }
}