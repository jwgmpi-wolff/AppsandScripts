package com.wolffentp.stockanalyzer.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockMovementAnalyzerTest {
    private val now = Instant.parse("2026-08-11T15:00:00Z")

    @Test
    fun staleDataNeverProducesDirectionalPrediction() {
        val result = StockMovementAnalyzer().analyze(snapshot(now.minusSeconds(3600)), Horizon.TEN, now)
        assertEquals(Direction.NEUTRAL_INSUFFICIENT_DATA, result.direction)
        assertEquals(0, result.confidence)
        assertTrue(result.warnings.any { it.contains("stale") })
    }

    @Test
    fun missingQuoteNeverProducesDirectionalPrediction() {
        val source = snapshot(now.minusSeconds(60)).copy(quote = null)
        val result = StockMovementAnalyzer().analyze(source, Horizon.TEN, now)
        assertEquals(Direction.NEUTRAL_INSUFFICIENT_DATA, result.direction)
        assertTrue(result.reason.startsWith("Insufficient live data"))
    }

    @Test
    fun risingTimestampedCandlesProduceExplainableResult() {
        val result = StockMovementAnalyzer().analyze(snapshot(now.minusSeconds(60)), Horizon.TEN, now)
        assertEquals(Direction.UP, result.direction)
        assertTrue(result.confidence in 1..100)
        assertTrue(result.signals.any { it.name == "Momentum" && it.contribution != null })
    }

    @Test
    fun indicatorsDoNotInventUnavailableVolume() {
        assertEquals(null, Indicators.vwap(candles(now, includeVolume = false)))
        assertEquals(null, Indicators.relativeVolume(List(12) { null }, 10))
    }

    @Test
    fun indicatorCalculationsUseRetrievedValues() {
        assertEquals(4.0, Indicators.simpleMovingAverage(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3) ?: 0.0, 0.0001)
        assertEquals(100.0, Indicators.rsi((1..16).map(Int::toDouble)) ?: 0.0, 0.0001)
        assertTrue((Indicators.macd((1..30).map(Int::toDouble)) ?: 0.0) > 0.0)
    }

    @Test
    fun fallingTimestampedCandlesProduceExplainableDownResult() {
        val source = snapshot(now.minusSeconds(60))
        val falling = source.candles.mapIndexed { index, candle ->
            val close = 130.0 - index
            candle.copy(open = close + 0.2, high = close + 0.4, low = close - 0.4, close = close)
        }
        val result = StockMovementAnalyzer().analyze(source.copy(
            quote = source.quote?.copy(price = falling.last().close),
            candles = falling,
        ), Horizon.TEN, now)
        assertEquals(Direction.DOWN, result.direction)
        assertTrue(result.reason.contains("weighted score"))
    }

    @Test
    fun invalidIntervalFailsClosedInsteadOfDividingByZero() {
        val result = StockMovementAnalyzer().analyze(snapshot(now.minusSeconds(60)).copy(intervalMinutes = 0), Horizon.TEN, now)
        assertEquals(Direction.NEUTRAL_INSUFFICIENT_DATA, result.direction)
        assertTrue(result.warnings.any { it.contains("interval") })
    }

    private fun snapshot(latest: Instant): MarketSnapshot {
        val candles = candles(latest, includeVolume = true)
        return MarketSnapshot("MSFT", "Mock provider (test only)", now, 1, Quote("MSFT", candles.last().close, latest, "Mock provider (test only)"), candles)
    }

    private fun candles(latest: Instant, includeVolume: Boolean): List<Candle> =
        (0 until 30).map { index ->
            val close = 100.0 + index
            Candle(latest.minusSeconds((29L - index) * 60), close - 0.2, close + 0.4, close - 0.4, close, if (includeVolume) 1_000L + index * 20 else null)
        }
}