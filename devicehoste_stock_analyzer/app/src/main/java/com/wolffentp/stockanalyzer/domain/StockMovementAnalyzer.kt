package com.wolffentp.stockanalyzer.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

data class AnalyzerConfig(val positiveThreshold: Double = 0.2, val negativeThreshold: Double = -0.2)

class StockMovementAnalyzer(
    private val validator: AnalysisValidator = AnalysisValidator(),
    private val config: AnalyzerConfig = AnalyzerConfig(),
) {
    fun analyze(snapshot: MarketSnapshot, horizon: Horizon, now: Instant = Instant.now()): AnalysisResult {
        val latestTimestamp = snapshot.candles.maxOfOrNull { it.timestamp }
        val age = latestTimestamp?.let { Duration.between(it, now).toMinutes() }
        val warnings = validator.validate(snapshot, horizon, now)
        if (warnings.isNotEmpty()) return insufficient(snapshot, horizon, latestTimestamp, age, warnings)

        val ordered = snapshot.candles.sortedBy { it.timestamp }
        val closes = ordered.map { it.close }
        val horizonPeriods = horizon.minutes / snapshot.intervalMinutes
        val indicators = IndicatorValues(
            momentumPercent = Indicators.momentumPercent(closes, horizonPeriods),
            shortMovingAverage = Indicators.simpleMovingAverage(closes, 5),
            longMovingAverage = Indicators.simpleMovingAverage(closes, 12),
            relativeVolume = Indicators.relativeVolume(ordered.map { it.volume }, 10),
            rsi = Indicators.rsi(closes),
            macd = Indicators.macd(closes),
            vwap = Indicators.vwap(ordered.takeLast(20)),
        )
        val signals = listOf(
            contribution("Momentum", indicators.momentumPercent?.coerceIn(-2.0, 2.0)?.div(2.0), 0.35),
            contribution("Trend", trendScore(indicators), 0.25),
            contribution("Volume", volumeScore(indicators, closes.last()), 0.10),
            contribution("RSI", rsiScore(indicators.rsi), 0.15),
            contribution("MACD", indicators.macd?.let { if (it > 0) 1.0 else if (it < 0) -1.0 else 0.0 }, 0.15),
        )
        val availableWeight = signals.filter { it.contribution != null }.sumOf { it.weight }
        if (availableWeight < 0.6) {
            return insufficient(snapshot, horizon, latestTimestamp, age, listOf("Too few supported signals to calculate a prediction."), indicators, signals)
        }
        val score = signals.sumOf { it.contribution ?: 0.0 } / availableWeight
        val direction = when {
            score >= config.positiveThreshold -> Direction.UP
            score <= config.negativeThreshold -> Direction.DOWN
            else -> Direction.NEUTRAL_INSUFFICIENT_DATA
        }
        val confidence = (abs(score) * 100).roundToInt().coerceIn(0, 100)
        val reason = "Probabilistic intraday analysis from ${signals.count { it.contribution != null }} supported technical signals; weighted score ${"%.3f".format(score)}. This is not financial advice."
        val limitations = buildList {
            if (indicators.relativeVolume == null || indicators.vwap == null) add("Volume or VWAP was unavailable and was not used.")
            if (indicators.rsi == null) add("RSI was unavailable and was not used.")
            if (indicators.macd == null) add("MACD was unavailable and was not used.")
            add("Timestamped sentiment was not supplied and was not used.")
        }
        return AnalysisResult(snapshot.symbol, horizon, direction, confidence, snapshot.provider, latestTimestamp, snapshot.retrievedAt, age, snapshot.intervalMinutes, snapshot.quote, indicators, signals, limitations, reason)
    }

    private fun contribution(name: String, value: Double?, weight: Double) =
        SignalContribution(name, value, weight, value?.times(weight))

    private fun trendScore(values: IndicatorValues): Double? {
        val short = values.shortMovingAverage ?: return null
        val long = values.longMovingAverage ?: return null
        return when { short > long -> 1.0; short < long -> -1.0; else -> 0.0 }
    }

    private fun volumeScore(values: IndicatorValues, latestPrice: Double): Double? {
        val relative = values.relativeVolume ?: return null
        val vwap = values.vwap ?: return null
        if (relative < 1.1) return 0.0
        return if (latestPrice >= vwap) 1.0 else -1.0
    }

    private fun rsiScore(rsi: Double?): Double? = when {
        rsi == null -> null
        rsi >= 70 -> -1.0
        rsi <= 30 -> 1.0
        else -> 0.0
    }

    private fun insufficient(snapshot: MarketSnapshot, horizon: Horizon, timestamp: Instant?, age: Long?, warnings: List<String>, indicators: IndicatorValues? = null, signals: List<SignalContribution> = emptyList()) =
        AnalysisResult(snapshot.symbol, horizon, Direction.NEUTRAL_INSUFFICIENT_DATA, 0, snapshot.provider.ifBlank { "Unknown" }, timestamp, snapshot.retrievedAt, age, snapshot.intervalMinutes, snapshot.quote, indicators, signals, warnings, "Insufficient live data. ${warnings.joinToString(" ")} No directional prediction was generated.")
}