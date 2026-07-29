package net.wolffentp.stockstreamportfolio.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wolffentp.stockstreamportfolio.data.api.StockStreamApi
import net.wolffentp.stockstreamportfolio.data.api.UpdateSettingsRequest
import net.wolffentp.stockstreamportfolio.data.model.SettingsResponse
import net.wolffentp.stockstreamportfolio.data.model.UserSettings

class SettingsRepository(private val api: StockStreamApi) {
    suspend fun get(): SettingsResponse = withContext(Dispatchers.IO) { api.getSettings() }

    suspend fun put(settings: UserSettings): UserSettings = withContext(Dispatchers.IO) {
        api.updateSettings(
            UpdateSettingsRequest(
                settings.refreshIntervalSeconds,
                settings.aggregateDuplicateSymbols,
                settings.autoAddImportedSymbols
            )
        )
    }
}
