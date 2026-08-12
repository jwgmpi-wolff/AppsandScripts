package com.wolffentp.stockanalyzer.data

import com.wolffentp.stockanalyzer.domain.Candle
import com.wolffentp.stockanalyzer.domain.CandleSeries
import com.wolffentp.stockanalyzer.domain.Direction
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.Quote
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketAnalysisRepositoryTest {
    private val now = Instant.parse("2026-08-11T15:00:00Z")

    @Test
    fun mockedProviderDataFlowsIntoAnalysisWithoutSubstitution() = runBlocking {
        val provider = TestProvider(now.minusSeconds(60))
        val result = MarketAnalysisRepository(provider, clock = { now }).analyze("msft", Horizon.TEN)
        assertEquals("Mock provider (test only)", result.provider)
        assertEquals(129.0, result.quote?.price ?: 0.0, 0.0)
        assertEquals(Direction.UP, result.direction)
        assertTrue(provider.quoteCalled && provider.candlesCalled)
    }

    @Test(expected = MarketDataException.RateLimited::class)
    fun providerErrorsAreNotConvertedIntoPredictions() = runBlocking<Unit> {
        MarketAnalysisRepository(object : MarketDataProvider {
            override val displayName = "Mock provider (test only)"
            override suspend fun getQuote(symbol: String): Quote = throw MarketDataException.RateLimited()
            override suspend fun getIntradayCandles(symbol: String, intervalMinutes: Int, rangeMinutes: Int): CandleSeries = error("must not be called")
        }, clock = { now }).analyze("MSFT", Horizon.TEN)
    }

    private class TestProvider(private val latest: Instant) : MarketDataProvider {
        override val displayName = "Mock provider (test only)"
        var quoteCalled = false
        var candlesCalled = false

        override suspend fun getQuote(symbol: String): Quote {
            quoteCalled = true
            return Quote(symbol, 129.0, latest, displayName)
        }

        override suspend fun getIntradayCandles(symbol: String, intervalMinutes: Int, rangeMinutes: Int): CandleSeries {
            candlesCalled = true
            val candles = (0 until 30).map { index ->
                val close = 100.0 + index
                Candle(latest.minusSeconds((29L - index) * 60), close, close + 0.5, close - 0.5, close, 1_000L + index * 20)
            }
            return CandleSeries(displayName, latest, intervalMinutes, candles)
        }
    }
}