package org.horizon.model

import org.horizon.dto.PolygonArticle

fun interface SentimentScorer {
    fun computePolarity(article: PolygonArticle): Double
}
