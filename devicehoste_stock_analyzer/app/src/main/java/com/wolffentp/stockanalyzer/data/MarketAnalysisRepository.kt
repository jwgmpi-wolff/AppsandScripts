package com.wolffentp.stockanalyzer.data

import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.MarketSnapshot
import com.wolffentp.stockanalyzer.domain.StockMovementAnalyzer
import java.time.Instant

class MarketAnalysisRepository(
    private val provider: MarketDataProvider,
    private val analyzer: StockMovementAnalyzer = StockMovementAnalyzer(),
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun analyze(symbol: String, horizon: Horizon): AnalysisResult {
        val normalized = symbol.trim().uppercase()
        require(SYMBOL.matches(normalized)) { "Symbol must contain 1-10 letters, digits, dot, or hyphen." }
        val quote = provider.getQuote(normalized)
        val series = provider.getIntradayCandles(
            normalized,
            intervalMinutes = horizon.candleIntervalMinutes,
            rangeMinutes = horizon.rangeMinutes,
        )
        val snapshot = MarketSnapshot(normalized, series.provider, series.retrievedAt, series.intervalMinutes, quote, series.candles)
        return analyzer.analyze(snapshot, horizon, clock())
    }

    private companion object { val SYMBOL = Regex("^[A-Z0-9.-]{1,10}$") }
}