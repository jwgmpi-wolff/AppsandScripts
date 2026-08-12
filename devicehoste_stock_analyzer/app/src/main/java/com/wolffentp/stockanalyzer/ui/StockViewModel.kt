package com.wolffentp.stockanalyzer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolffentp.stockanalyzer.BuildConfig
import com.wolffentp.stockanalyzer.data.MarketAnalysisRepository
import com.wolffentp.stockanalyzer.data.MarketDataException
import com.wolffentp.stockanalyzer.data.ProxyMarketDataProvider
import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.AnalyzerConfig
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.StockMovementAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

data class StockRowState(val symbol: String, val analysis: AnalysisResult? = null, val error: String? = null)

data class StockUiState(
    val horizon: Horizon = Horizon.TEN,
    val rows: List<StockRowState> = listOf("MSFT", "AAPL", "NVDA").map(::StockRowState),
    val isRefreshing: Boolean = false,
    val autoRefresh: Boolean = true,
    val lastRefreshAt: Instant? = null,
    val selectedSymbol: String? = null,
)

class StockViewModel(
    private val repository: MarketAnalysisRepository = MarketAnalysisRepository(
        provider = ProxyMarketDataProvider(BuildConfig.MARKET_DATA_BASE_URL),
        analyzer = StockMovementAnalyzer(
            config = AnalyzerConfig(BuildConfig.POSITIVE_THRESHOLD, BuildConfig.NEGATIVE_THRESHOLD),
        ),
    ),
) : ViewModel() {
    private val mutableState = MutableStateFlow(StockUiState())
    val state: StateFlow<StockUiState> = mutableState.asStateFlow()
    private var autoRefreshJob: Job? = null
    private var refreshJob: Job? = null

    init {
        refresh()
        startAutoRefresh()
    }

    fun setHorizon(horizon: Horizon) {
        mutableState.update { it.copy(horizon = horizon) }
        refresh()
    }

    fun addSymbol(input: String): Boolean {
        val symbol = input.trim().uppercase()
        if (!Regex("^[A-Z0-9.-]{1,10}$").matches(symbol)) return false
        mutableState.update { current ->
            if (current.rows.any { it.symbol == symbol }) current
            else current.copy(rows = current.rows + StockRowState(symbol))
        }
        refresh()
        return true
    }

    fun select(symbol: String?) { mutableState.update { it.copy(selectedSymbol = symbol) } }

    fun setAutoRefresh(enabled: Boolean) {
        mutableState.update { it.copy(autoRefresh = enabled) }
        autoRefreshJob?.cancel()
        if (enabled) startAutoRefresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true) }
            val horizon = mutableState.value.horizon
            val updated = mutableState.value.rows.map { row ->
                try {
                    row.copy(analysis = repository.analyze(row.symbol, horizon), error = null)
                } catch (error: Exception) {
                    row.copy(analysis = null, error = error.displayMessage())
                }
            }
            mutableState.update { it.copy(rows = updated, isRefreshing = false, lastRefreshAt = Instant.now()) }
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_MILLIS)
                refresh()
            }
        }
    }

    private fun Exception.displayMessage(): String = when (this) {
        is MarketDataException -> "Live data unavailable. $message"
        is IllegalArgumentException -> "Live data unavailable. Unsupported symbol."
        else -> "Live data unavailable. Provider request failed."
    }

    private companion object { const val AUTO_REFRESH_MILLIS = 60_000L }
}