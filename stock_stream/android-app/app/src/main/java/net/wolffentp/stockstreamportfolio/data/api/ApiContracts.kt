package net.wolffentp.stockstreamportfolio.data.api

data class AddWatchlistItemRequest(val symbol: String, val displayName: String?, val notes: String?)
data class ValidateWatchlistRequest(val symbol: String)
data class UpdateSettingsRequest(
    val refreshIntervalSeconds: Int,
    val aggregateDuplicateSymbols: Boolean,
    val autoAddImportedSymbols: Boolean
)
data class UpdateColumnLayoutRequest(val orderedColumns: List<String>, val hiddenColumns: Set<String>, val displayDensity: String)
data class UpsertViewRequest(
    val id: String?,
    val name: String,
    val selectedColumns: List<String>,
    val sortBy: String,
    val sortDirection: String,
    val filter: String?,
    val refreshIntervalSeconds: Int,
    val rotationIntervalSeconds: Int,
    val isPaused: Boolean
)
data class CsvPayloadRequest(val csvText: String, val autoAddSymbolsToWatchlist: Boolean)
