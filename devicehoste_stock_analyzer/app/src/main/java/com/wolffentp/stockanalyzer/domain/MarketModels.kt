package com.wolffentp.stockanalyzer.domain

import java.time.Instant

enum class Horizon(val minutes: Int) {
    TEN(10), TWENTY(20), THIRTY(30), FORTY(40), FIFTY(50), SIXTY(60)
}

enum class Direction { UP, DOWN, NEUTRAL_INSUFFICIENT_DATA }

data class Quote(
    val symbol: String,
    val price: Double,
    val timestamp: Instant,
    val provider: String,
)

data class Candle(
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?,
)

data class MarketSnapshot(
    val symbol: String,
    val provider: String,
    val retrievedAt: Instant,
    val intervalMinutes: Int,
    val quote: Quote?,
    val candles: List<Candle>,
)

data class CandleSeries(
    val provider: String,
    val retrievedAt: Instant,
    val intervalMinutes: Int,
    val candles: List<Candle>,
)

data class TimestampedSentiment(
    val score: Double,
    val source: String,
    val publishedAt: Instant,
)

data class IndicatorValues(
    val momentumPercent: Double?,
    val shortMovingAverage: Double?,
    val longMovingAverage: Double?,
    val relativeVolume: Double?,
    val rsi: Double?,
    val macd: Double?,
    val vwap: Double?,
)

data class SignalContribution(val name: String, val value: Double?, val weight: Double, val contribution: Double?)

data class AnalysisResult(
    val symbol: String,
    val horizon: Horizon,
    val direction: Direction,
    val confidence: Int,
    val provider: String,
    val lastDataTimestamp: Instant?,
    val retrievedAt: Instant,
    val sourceAgeMinutes: Long?,
    val candleIntervalMinutes: Int,
    val quote: Quote?,
    val indicators: IndicatorValues?,
    val signals: List<SignalContribution>,
    val warnings: List<String>,
    val reason: String,
)