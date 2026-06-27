package org.horizon.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Publisher(
    val name:        String,
    @SerialName("homepage_url")
    val homepageUrl: String? = null,
)

@Serializable
data class Insight(
    val ticker:             String,
    val sentiment:          String,
    @SerialName("sentiment_reasoning")
    val sentimentReasoning: String? = null,
)

@Serializable
data class PolygonArticle(
    val title:        String,
    val author:       String? = null,
    val tickers:      List<String> = emptyList(),
    val insights:     List<Insight> = emptyList(),
    val publisher:    Publisher? = null,
    val description:  String? = null,
    @SerialName("published_utc")
    val publishedUtc: String,
)