package net.wolffentp.stockstreamportfolio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.wolffentp.stockstreamportfolio.data.api.SignalRQuoteClient
import net.wolffentp.stockstreamportfolio.auth.AuthState
import net.wolffentp.stockstreamportfolio.auth.MsalAuthManager
import net.wolffentp.stockstreamportfolio.data.model.ColumnLayout
import net.wolffentp.stockstreamportfolio.data.model.QuoteEnvelope
import net.wolffentp.stockstreamportfolio.data.model.RotatingView
import net.wolffentp.stockstreamportfolio.data.model.SettingsResponse
import net.wolffentp.stockstreamportfolio.data.model.WatchlistItem
import net.wolffentp.stockstreamportfolio.data.model.SignalRMapper
import net.wolffentp.stockstreamportfolio.data.repo.ColumnRepository
import net.wolffentp.stockstreamportfolio.data.repo.PortfolioRepository
import net.wolffentp.stockstreamportfolio.data.repo.SettingsRepository
import net.wolffentp.stockstreamportfolio.data.repo.ViewRepository
import net.wolffentp.stockstreamportfolio.storage.SecurePrefs

class MainViewModel(
    private val authManager: MsalAuthManager,
    private val securePrefs: SecurePrefs,
    private val portfolioRepository: PortfolioRepository,
    private val settingsRepository: SettingsRepository,
    private val viewRepository: ViewRepository,
    private val columnRepository: ColumnRepository,
    private val signalRQuoteClient: SignalRQuoteClient
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _watchlist = MutableStateFlow<List<WatchlistItem>>(emptyList())
    val watchlist: StateFlow<List<WatchlistItem>> = _watchlist.asStateFlow()

    private val _quotes = MutableStateFlow<QuoteEnvelope?>(null)
    val quotes: StateFlow<QuoteEnvelope?> = _quotes.asStateFlow()

    private val _settings = MutableStateFlow<SettingsResponse?>(null)
    val settings: StateFlow<SettingsResponse?> = _settings.asStateFlow()

    private val _views = MutableStateFlow<List<RotatingView>>(emptyList())
    val views: StateFlow<List<RotatingView>> = _views.asStateFlow()

    private val _activeViewIndex = MutableStateFlow(0)
    val activeViewIndex: StateFlow<Int> = _activeViewIndex.asStateFlow()

    private val _columnLayout = MutableStateFlow<ColumnLayout?>(null)
    val columnLayout: StateFlow<ColumnLayout?> = _columnLayout.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    private val _signalRConnected = MutableStateFlow(false)
    val signalRConnected: StateFlow<Boolean> = _signalRConnected.asStateFlow()

    private var pollingJob: Job? = null
    private var rotationJob: Job? = null
    private val layoutManager = ColumnLayoutManager()

    fun initialize() {
        viewModelScope.launch {
            _authState.value = AuthState.SigningIn
            val init = authManager.initialize()
            if (init.isFailure) {
                _authState.value = AuthState.Error(init.exceptionOrNull()?.message ?: "MSAL init failed")
                return@launch
            }

            val account = authManager.getCurrentAccount()
            if (account == null) {
                _authState.value = AuthState.SignedOut
            } else {
                val token = authManager.acquireTokenSilent()
                if (token.isNullOrBlank()) {
                    _authState.value = AuthState.SignedOut
                } else {
                    securePrefs.allowSignIn()
                    securePrefs.saveAccessToken(token)
                    _authState.value = AuthState.SignedIn(account.id)
                    refreshAll()
                    startSignalR()
                }
            }
        }
    }

    fun onSignedIn(accessToken: String, accountId: String) {
        securePrefs.allowSignIn()
        securePrefs.saveAccessToken(accessToken)
        _authState.value = AuthState.SignedIn(accountId)
        refreshAll()
        startSignalR()
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            authManager.clearCurrentAccount()
            clearSessionState()
            _authState.value = AuthState.SignedOut
        }
    }

    fun clearContext() {
        viewModelScope.launch {
            authManager.signOut()
            authManager.clearCurrentAccount()
            clearSessionState()
            _authState.value = AuthState.SignedOut
        }
    }

    fun updateRefreshInterval(refreshIntervalSeconds: Int) {
        val current = _settings.value ?: return
        val clamped = refreshIntervalSeconds.coerceIn(current.minAllowedSeconds, current.maxAllowedSeconds)
        viewModelScope.launch {
            val updatedSettings = settingsRepository.put(current.settings.copy(refreshIntervalSeconds = clamped))
            _settings.value = current.copy(settings = updatedSettings)
            schedulePolling()
        }
    }

    fun setFullscreen(value: Boolean) {
        _isFullscreen.value = value
    }

    fun refreshAll() {
        viewModelScope.launch {
            runCatching {
                _watchlist.value = portfolioRepository.watchlist()
                _settings.value = settingsRepository.get()
                _views.value = viewRepository.getViews()
                _columnLayout.value = columnRepository.get()
                fetchQuotes()
                schedulePolling()
                scheduleRotation()
            }.onFailure {
                _authState.value = AuthState.Error(it.message ?: "Failed to load data")
            }
        }
    }

    private fun clearSessionState() {
        pollingJob?.cancel()
        rotationJob?.cancel()
        signalRQuoteClient.disconnect()
        securePrefs.clearAccessToken()
        _watchlist.value = emptyList()
        _quotes.value = null
        _settings.value = null
        _views.value = emptyList()
        _activeViewIndex.value = 0
        _columnLayout.value = null
        _isFullscreen.value = false
        _signalRConnected.value = false
    }

    private suspend fun fetchQuotes() {
        val symbols = _watchlist.value.map { it.symbol }.distinct()
        if (symbols.isEmpty()) {
            _quotes.value = null
            return
        }

        _quotes.value = portfolioRepository.quotes(symbols)
    }

    fun addSymbol(symbol: String, displayName: String?, notes: String?) {
        viewModelScope.launch {
            val validation = portfolioRepository.validateSymbol(symbol)
            if (!validation.isValidFormat) {
                _authState.value = AuthState.Error("Invalid symbol format")
                return@launch
            }

            portfolioRepository.addWatchlist(symbol, displayName, notes)
            _watchlist.value = portfolioRepository.watchlist()
            fetchQuotes()
        }
    }

    fun removeSymbol(symbol: String) {
        viewModelScope.launch {
            portfolioRepository.removeWatchlist(symbol)
            _watchlist.value = portfolioRepository.watchlist()
            fetchQuotes()
        }
    }

    private fun schedulePolling() {
        pollingJob?.cancel()
        val intervalSeconds = _settings.value?.settings?.refreshIntervalSeconds ?: 15
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(intervalSeconds * 1000L)
                if (!_signalRConnected.value) {
                    fetchQuotes()
                }
            }
        }
    }

    private fun startSignalR() {
        signalRQuoteClient.connect { payload ->
            _quotes.value = SignalRMapper.toEnvelope(payload)
        }

        viewModelScope.launch {
            signalRQuoteClient.isConnected.collect {
                _signalRConnected.value = it
            }
        }
    }

    private fun scheduleRotation() {
        rotationJob?.cancel()
        rotationJob = viewModelScope.launch {
            while (true) {
                val list = _views.value
                val current = list.getOrNull(_activeViewIndex.value)
                val rotationSeconds = current?.rotationIntervalSeconds ?: 20
                val paused = current?.isPaused ?: true
                if (!paused && list.isNotEmpty()) {
                    delay(rotationSeconds * 1000L)
                    _activeViewIndex.value = (_activeViewIndex.value + 1) % list.size
                } else {
                    delay(1000)
                }
            }
        }
    }

    fun nextView() {
        val list = _views.value
        if (list.isNotEmpty()) {
            _activeViewIndex.value = (_activeViewIndex.value + 1) % list.size
        }
    }

    fun previousView() {
        val list = _views.value
        if (list.isNotEmpty()) {
            val size = list.size
            _activeViewIndex.value = (_activeViewIndex.value - 1 + size) % size
        }
    }

    fun moveColumn(fromIndex: Int, toIndex: Int) {
        val current = _columnLayout.value ?: return
        _columnLayout.value = layoutManager.move(current, fromIndex, toIndex)
    }

    fun hideColumn(column: String) {
        val current = _columnLayout.value ?: return
        _columnLayout.value = layoutManager.hide(current, column)
    }

    fun unhideColumn(column: String) {
        val current = _columnLayout.value ?: return
        _columnLayout.value = layoutManager.unhide(current, column)
    }

    fun resetColumns() {
        _columnLayout.value = layoutManager.reset()
    }

    fun saveColumnLayout() {
        val current = _columnLayout.value ?: return
        viewModelScope.launch {
            _columnLayout.value = columnRepository.put(current)
        }
    }

    override fun onCleared() {
        signalRQuoteClient.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(
            authManager: MsalAuthManager,
            securePrefs: SecurePrefs,
            portfolioRepository: PortfolioRepository,
            settingsRepository: SettingsRepository,
            viewRepository: ViewRepository,
            columnRepository: ColumnRepository,
            signalRQuoteClient: SignalRQuoteClient
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(
                    authManager,
                    securePrefs,
                    portfolioRepository,
                    settingsRepository,
                    viewRepository,
                    columnRepository,
                    signalRQuoteClient
                ) as T
            }
        }
    }
}
