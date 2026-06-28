package org.horizon.model

import org.horizon.dto.PolygonArticle
import org.horizon.storage.DynamoWriter
import java.nio.file.Paths
import java.nio.LongBuffer
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor

object SentimentML {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession by lazy {
        val modelUrl = SentimentML::class.java.getResource("/finbert.onnx") ?: error("finbert.onnx not found on classpath")
        val modelPath = Paths.get(modelUrl.toURI()).toString()
        env.createSession(modelPath, OrtSession.SessionOptions())
    }

    private val tokenizer: HuggingFaceTokenizer by lazy {
        val tokenizerUrl = SentimentML::class.java.getResource("/tokenizer.json") ?: error("tokenizer.json not found on classpath")
        val tokenizerPath = Paths.get(tokenizerUrl.toURI())
        HuggingFaceTokenizer.newInstance(tokenizerPath)
    }

    private fun runInference(text: String): Double {
        val encoding      = tokenizer.encode(text)
        val inputIds      = encoding.ids.take(512).toLongArray()
        val attentionMask = encoding.attentionMask.take(512).toLongArray()
        val tokenTypeIds  = encoding.typeIds.take(512).toLongArray()
        val seqLen        = inputIds.size.toLong()

        val inputIdsTensor      = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds),      longArrayOf(1, seqLen))
        val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen))
        val tokenTypeIdsTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds),  longArrayOf(1, seqLen))

        val logits = session.run(mapOf(
            "input_ids"      to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeIdsTensor,
        )).use { result ->
            @Suppress("UNCHECKED_CAST")
            (result[0].value as Array<FloatArray>)[0]
        }

        inputIdsTensor.close()
        attentionMaskTensor.close()
        tokenTypeIdsTensor.close()

        val probs = softmax(logits)   // [negative, neutral, positive]
        return (probs[0] - probs[1]).toDouble()
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exp = logits.map { Math.exp((it - max).toDouble()) }
        val sum = exp.sum()
        return FloatArray(logits.size) { i -> (exp[i] / sum).toFloat() }
    }

    fun computePolarity(article: PolygonArticle): Double {
        val text = listOfNotNull(article.title, article.description).joinToString(" ")
        if (text.isBlank()) return 0.0
        return runInference(text)
    }

    fun writeLeaderboard(articles: List<PolygonArticle>) {
        println("ONNX session inputs: ${session.inputNames}")
        println("Tokenizer ready: ${tokenizer.encode("test").ids.size} tokens")
        println("Test inference: ${runInference("Earnings per share beat expectations significantly.")}")

        val tickerScores = mutableMapOf<String, MutableList<Double>>()

        for (article in articles) {
            for (ticker in article.tickers) {
                val polarity = computePolarity(article)
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
                    ticker        = ticker,
                    timestamp     = java.time.Instant.now().toString(),
                    polarityScore = score,
                    articleCount  = tickerScores[ticker]?.size ?: 0
                )
            }
    }
}