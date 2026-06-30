package org.horizon.model

import org.horizon.dto.PolygonArticle
import java.nio.file.Paths
import java.nio.LongBuffer
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import org.slf4j.LoggerFactory

object SentimentML : SentimentScorer {

    private val logger = LoggerFactory.getLogger(SentimentML::class.java)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionDelegate = lazy {
        val modelUrl = SentimentML::class.java.getResource("/finbert.onnx")
            ?: error("finbert.onnx not found on classpath")
        env.createSession(Paths.get(modelUrl.toURI()).toString(), OrtSession.SessionOptions())
    }
    private val session: OrtSession by sessionDelegate

    private val tokenizerDelegate = lazy {
        val classpathUrl = SentimentML::class.java.getResource("/tokenizer.json")
        if (classpathUrl != null) {
            HuggingFaceTokenizer.newInstance(Paths.get(classpathUrl.toURI()))
        } else {
            HuggingFaceTokenizer.newInstance(Paths.get("horizon-ml/tokenizer.json"))
        }
    }
    private val tokenizer: HuggingFaceTokenizer by tokenizerDelegate

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            if (sessionDelegate.isInitialized()) try { session.close() } catch (_: Exception) {}
            if (tokenizerDelegate.isInitialized()) try { tokenizer.close() } catch (_: Exception) {}
            try { env.close() } catch (_: Exception) {}
        })
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

        try {
            val logits = session.run(mapOf(
                "input_ids"      to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor,
            )).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<FloatArray>)[0]
            }

            val probs = softmax(logits)   // [positive, negative, neutral]
            return (probs[0] - probs[1]).toDouble()
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exp = logits.map { Math.exp((it - max).toDouble()) }
        val sum = exp.sum()
        return FloatArray(logits.size) { i -> (exp[i] / sum).toFloat() }
    }

    override fun computePolarity(article: PolygonArticle): Double {
        val text = listOfNotNull(article.title, article.description).joinToString(" ")
        if (text.isBlank()) return 0.0
        return runInference(text)
    }
}
