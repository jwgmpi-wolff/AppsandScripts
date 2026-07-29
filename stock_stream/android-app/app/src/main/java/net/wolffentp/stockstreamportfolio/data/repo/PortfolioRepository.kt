package net.wolffentp.stockstreamportfolio.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wolffentp.stockstreamportfolio.data.api.AddWatchlistItemRequest
import net.wolffentp.stockstreamportfolio.data.api.StockStreamApi
import net.wolffentp.stockstreamportfolio.data.api.ValidateWatchlistRequest
import net.wolffentp.stockstreamportfolio.data.model.QuoteEnvelope
import net.wolffentp.stockstreamportfolio.data.model.SymbolValidationResult
import net.wolffentp.stockstreamportfolio.data.model.WatchlistItem

class PortfolioRepository(private val api: StockStreamApi) {
    suspend fun watchlist(): List<WatchlistItem> = withContext(Dispatchers.IO) { api.getWatchlist() }

    suspend fun addWatchlist(symbol: String, displayName: String?, notes: String?) = withContext(Dispatchers.IO) {
        api.addWatchlist(AddWatchlistItemRequest(symbol, displayName, notes))
    }

    suspend fun removeWatchlist(symbol: String) = withContext(Dispatchers.IO) {
        api.deleteWatchlist(symbol)
    }

    suspend fun validateSymbol(symbol: String): SymbolValidationResult = withContext(Dispatchers.IO) {
        api.validateSymbol(ValidateWatchlistRequest(symbol))
    }

    suspend fun quotes(symbols: List<String>): QuoteEnvelope = withContext(Dispatchers.IO) {
        api.getQuotes(symbols.joinToString(","))
    }
}
