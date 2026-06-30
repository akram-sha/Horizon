package org.horizon.storage

fun interface PolarityStore {
    fun writePolarityScore(ticker: String, timestamp: String, score: Double, articleCount: Int)
}
