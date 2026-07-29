package net.wolffentp.stockstreamportfolio.data.model

data class QuoteEnvelope(
    val provider: String,
    val lastSuccessfulLiveUpdateTimestampUtc: String?,
    val rows: List<QuoteRow>
)

data class QuoteRow(
    val symbol: String,
    val displayName: String?,
    val dataSource: String,
    val retrievedAtUtc: String,
    val marketStatus: String,
    val freshnessStatus: String,
    val isLive: Boolean,
    val message: String?,
    val fields: Map<String, String?>,
    val missingFields: List<String>,
    val calculatedFields: List<String>,
    val errorCode: String?,
    val errorMessage: String?
)

data class WatchlistItem(
    val symbol: String,
    val displayName: String?,
    val notes: String?,
    val addedAtUtc: String,
    val isProviderSupported: Boolean
)

data class UserSettings(
    val refreshIntervalSeconds: Int,
    val aggregateDuplicateSymbols: Boolean,
    val autoAddImportedSymbols: Boolean,
    val updatedAtUtc: String
)

data class SettingsResponse(
    val settings: UserSettings,
    val minAllowedSeconds: Int,
    val maxAllowedSeconds: Int
)

data class ColumnLayout(
    val orderedColumns: List<String>,
    val hiddenColumns: Set<String>,
    val displayDensity: String,
    val updatedAtUtc: String
)

data class RotatingView(
    val id: String,
    val name: String,
    val selectedColumns: List<String>,
    val sortBy: String,
    val sortDirection: String,
    val filter: String?,
    val refreshIntervalSeconds: Int,
    val rotationIntervalSeconds: Int,
    val isPaused: Boolean,
    val updatedAtUtc: String
)

data class SymbolValidationResult(
    val symbol: String,
    val isValidFormat: Boolean,
    val existsAtProvider: Boolean,
    val status: String,
    val error: String?
)

data class MarketStatusResponse(
    val provider: String,
    val marketStatus: String,
    val message: String,
    val retrievedAtUtc: String
)

data class CsvValidationError(val rowNumber: Int, val column: String, val message: String)

data class ImportedPortfolioRow(
    val rowNumber: Int,
    val rawValues: Map<String, String>,
    val symbol: String,
    val quantity: Double?,
    val purchasePrice: Double?,
    val account: String?,
    val isBaselineValue: Boolean
)

data class CsvValidationResult(
    val isValid: Boolean,
    val errors: List<CsvValidationError>,
    val parsedRows: List<ImportedPortfolioRow>
)
