package net.wolffentp.stockstreamportfolio.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wolffentp.stockstreamportfolio.data.api.StockStreamApi
import net.wolffentp.stockstreamportfolio.data.api.UpdateColumnLayoutRequest
import net.wolffentp.stockstreamportfolio.data.model.ColumnLayout

class ColumnRepository(private val api: StockStreamApi) {
    suspend fun get(): ColumnLayout = withContext(Dispatchers.IO) { api.getColumns() }

    suspend fun put(layout: ColumnLayout): ColumnLayout = withContext(Dispatchers.IO) {
        api.updateColumns(UpdateColumnLayoutRequest(layout.orderedColumns, layout.hiddenColumns, layout.displayDensity))
    }
}
