package com.wolffentp.stockanalyzer.domain

import java.time.Instant

enum class Horizon(
    val durationMinutes: Int,
    val candleIntervalMinutes: Int,
    val rangeMinutes: Int,
    val label: String,
    val freshnessMinutes: Long,
) {
    TEN(10, 1, 120, "10m", 15),
    TWENTY(20, 1, 120, "20m", 15),
    THIRTY(30, 1, 120, "30m", 15),
    FORTY(40, 1, 120, "40m", 15),
    FIFTY(50, 1, 120, "50m", 15),
    SIXTY(60, 1, 120, "60m", 15),
    ONE_DAY(1_440, 1_440, 129_600, "1d", 7_200),
    FIVE_DAYS(7_200, 1_440, 129_600, "5d", 7_200),
    TEN_DAYS(14_400, 1_440, 129_600, "10d", 7_200),
    ;

    val periods: Int get() = durationMinutes / candleIntervalMinutes
    val isDaily: Boolean get() = candleIntervalMinutes == 1_440
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