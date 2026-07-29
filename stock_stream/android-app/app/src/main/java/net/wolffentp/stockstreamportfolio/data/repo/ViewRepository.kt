package net.wolffentp.stockstreamportfolio.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wolffentp.stockstreamportfolio.data.api.StockStreamApi
import net.wolffentp.stockstreamportfolio.data.api.UpsertViewRequest
import net.wolffentp.stockstreamportfolio.data.model.RotatingView

class ViewRepository(private val api: StockStreamApi) {
    suspend fun getViews(): List<RotatingView> = withContext(Dispatchers.IO) { api.getViews() }

    suspend fun saveView(request: UpsertViewRequest): RotatingView = withContext(Dispatchers.IO) {
        if (request.id.isNullOrBlank()) api.createView(request) else api.updateView(request.id, request)
    }

    suspend fun deleteView(id: String) = withContext(Dispatchers.IO) { api.deleteView(id) }
}
