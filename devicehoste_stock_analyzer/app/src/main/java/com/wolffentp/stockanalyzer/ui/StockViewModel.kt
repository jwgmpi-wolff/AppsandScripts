package com.wolffentp.stockanalyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wolffentp.stockanalyzer.BuildConfig
import com.wolffentp.stockanalyzer.data.MarketAnalysisRepository
import com.wolffentp.stockanalyzer.data.MarketDataException
import com.wolffentp.stockanalyzer.data.YahooFinanceMarketDataProvider
import com.wolffentp.stockanalyzer.data.DataStoreWatchlistStore
import com.wolffentp.stockanalyzer.data.WatchlistEntry
import com.wolffentp.stockanalyzer.data.WatchlistStore
import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.AnalyzerConfig
import com.wolffentp.stockanalyzer.domain.Horizon
import com.wolffentp.stockanalyzer.domain.HoldingInputParser
import com.wolffentp.stockanalyzer.domain.StockMovementAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

data class StockRowState(
    val symbol: String,
    val quantity: Double? = null,
    val averageCost: Double? = null,
    val analysis: AnalysisResult? = null,
    val error: String? = null,
)

data class StockUiState(
    val horizon: Horizon = Horizon.TEN,
    val rows: List<StockRowState> = emptyList(),
    val isRefreshing: Boolean = false,
    val autoRefresh: Boolean = true,
    val lastRefreshAt: Instant? = null,
    val selectedSymbol: String? = null,
)

class StockViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: MarketAnalysisRepository = MarketAnalysisRepository(
        provider = YahooFinanceMarketDataProvider(),
        analyzer = StockMovementAnalyzer(
            config = AnalyzerConfig(BuildConfig.POSITIVE_THRESHOLD, BuildConfig.NEGATIVE_THRESHOLD),
        ),
    ),
    private val watchlistStore: WatchlistStore = DataStoreWatchlistStore(application),
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(StockUiState())
    val state: StateFlow<StockUiState> = mutableState.asStateFlow()
    private var autoRefreshJob: Job? = null
    private var refreshJob: Job? = null

    init {
        loadWatchlist()
        startAutoRefresh()
    }

    private fun loadWatchlist() {
        viewModelScope.launch {
            val entries = watchlistStore.entries.first()
            mutableState.update { state -> state.copy(rows = entries.map(WatchlistEntry::toRowState)) }
            refresh()
        }
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
        persistWatchlist()
        refresh()
        return true
    }

    fun deleteSymbol(symbol: String) {
        mutableState.update { state ->
            state.copy(
                rows = state.rows.filterNot { it.symbol == symbol },
                selectedSymbol = state.selectedSymbol.takeUnless { it == symbol },
            )
        }
        persistWatchlist()
    }

    fun clearWatchlist() {
        refreshJob?.cancel()
        mutableState.update { it.copy(rows = emptyList(), selectedSymbol = null, isRefreshing = false) }
        persistWatchlist()
    }

    fun saveHolding(symbol: String, quantityText: String, averageCostText: String): Boolean {
        val holding = HoldingInputParser.parse(quantityText, averageCostText) ?: return false
        mutableState.update { state ->
            state.copy(rows = state.rows.map { row ->
                if (row.symbol == symbol) row.copy(quantity = holding.quantity, averageCost = holding.averageCost) else row
            })
        }
        persistWatchlist()
        return true
    }

    fun clearHolding(symbol: String) {
        mutableState.update { state ->
            state.copy(rows = state.rows.map { row ->
                if (row.symbol == symbol) row.copy(quantity = null, averageCost = null) else row
            })
        }
        persistWatchlist()
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

    private fun persistWatchlist() {
        val entries = mutableState.value.rows.map(StockRowState::toWatchlistEntry)
        viewModelScope.launch { watchlistStore.save(entries) }
    }

    private fun Exception.displayMessage(): String = when (this) {
        is MarketDataException -> "Live data unavailable. $message"
        is IllegalArgumentException -> "Live data unavailable. Unsupported symbol."
        else -> "Live data unavailable. Provider request failed."
    }

    private companion object { const val AUTO_REFRESH_MILLIS = 60_000L }
}

private fun WatchlistEntry.toRowState() = StockRowState(symbol, quantity, averageCost)
private fun StockRowState.toWatchlistEntry() = WatchlistEntry(symbol, quantity, averageCost)