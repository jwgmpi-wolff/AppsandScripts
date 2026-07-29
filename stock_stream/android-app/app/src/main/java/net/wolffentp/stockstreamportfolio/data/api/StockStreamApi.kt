package net.wolffentp.stockstreamportfolio.data.api

import net.wolffentp.stockstreamportfolio.data.model.ColumnLayout
import net.wolffentp.stockstreamportfolio.data.model.CsvValidationResult
import net.wolffentp.stockstreamportfolio.data.model.MarketStatusResponse
import net.wolffentp.stockstreamportfolio.data.model.QuoteEnvelope
import net.wolffentp.stockstreamportfolio.data.model.RotatingView
import net.wolffentp.stockstreamportfolio.data.model.SettingsResponse
import net.wolffentp.stockstreamportfolio.data.model.SymbolValidationResult
import net.wolffentp.stockstreamportfolio.data.model.UserSettings
import net.wolffentp.stockstreamportfolio.data.model.WatchlistItem
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface StockStreamApi {
    @GET("health")
    suspend fun health(): Map<String, Any>

    @GET("settings")
    suspend fun getSettings(): SettingsResponse

    @PUT("settings")
    suspend fun updateSettings(@Body body: UpdateSettingsRequest): UserSettings

    @GET("watchlist")
    suspend fun getWatchlist(): List<WatchlistItem>

    @POST("watchlist")
    suspend fun addWatchlist(@Body body: AddWatchlistItemRequest): Map<String, Any>

    @DELETE("watchlist/{symbol}")
    suspend fun deleteWatchlist(@Path("symbol") symbol: String)

    @POST("watchlist/validate")
    suspend fun validateSymbol(@Body body: ValidateWatchlistRequest): SymbolValidationResult

    @GET("quotes")
    suspend fun getQuotes(@Query("symbols") symbols: String): QuoteEnvelope

    @GET("market-status")
    suspend fun marketStatus(): MarketStatusResponse

    @GET("columns")
    suspend fun getColumns(): ColumnLayout

    @PUT("columns/layout")
    suspend fun updateColumns(@Body body: UpdateColumnLayoutRequest): ColumnLayout

    @GET("views")
    suspend fun getViews(): List<RotatingView>

    @POST("views")
    suspend fun createView(@Body body: UpsertViewRequest): RotatingView

    @PUT("views/{id}")
    suspend fun updateView(@Path("id") id: String, @Body body: UpsertViewRequest): RotatingView

    @DELETE("views/{id}")
    suspend fun deleteView(@Path("id") id: String)

    @POST("csv/validate")
    suspend fun validateCsv(@Body body: CsvPayloadRequest): CsvValidationResult

    @POST("csv/import")
    suspend fun importCsv(@Body body: CsvPayloadRequest): Map<String, Any>
}
