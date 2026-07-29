package net.wolffentp.stockstreamportfolio.data.api

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.wolffentp.stockstreamportfolio.BuildConfig

data class SignalREnvelope(
    val provider: String,
    val lastSuccessfulLiveUpdateTimestampUtc: String?,
    val rows: List<Map<String, Any?>>
)

class SignalRQuoteClient(private val tokenProvider: () -> String?) {
    private var connection: HubConnection? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun connect(onQuoteUpdate: (SignalREnvelope) -> Unit) {
        if (connection != null) {
            return
        }

        val token = tokenProvider() ?: return
        val base = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
        val hubUrl = "$base/hubs/quotes"

        val hubConnection = HubConnectionBuilder.create(hubUrl)
            .withAccessTokenProvider(io.reactivex.rxjava3.core.Single.just(token))
            .build()

        hubConnection.on("QuoteUpdate", { payload: Map<String, Any?> ->
            val provider = payload["provider"] as? String ?: "Unknown"
            val last = payload["lastSuccessfulLiveUpdateTimestampUtc"] as? String
            val rows = payload["rows"] as? List<Map<String, Any?>> ?: emptyList()
            onQuoteUpdate(SignalREnvelope(provider, last, rows))
        }, Map::class.java)

        hubConnection.onClosed {
            _isConnected.value = false
            connection = null
        }

        hubConnection.start().doOnComplete {
            _isConnected.value = true
        }.doOnError {
            _isConnected.value = false
        }.subscribe()

        connection = hubConnection
    }

    fun disconnect() {
        connection?.stop()
        connection = null
        _isConnected.value = false
    }
}
