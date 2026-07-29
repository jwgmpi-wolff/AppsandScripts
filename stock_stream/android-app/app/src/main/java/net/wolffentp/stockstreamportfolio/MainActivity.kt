package net.wolffentp.stockstreamportfolio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import net.wolffentp.stockstreamportfolio.auth.MsalAuthManager
import net.wolffentp.stockstreamportfolio.data.api.ApiFactory
import net.wolffentp.stockstreamportfolio.data.api.SignalRQuoteClient
import net.wolffentp.stockstreamportfolio.data.repo.ColumnRepository
import net.wolffentp.stockstreamportfolio.data.repo.PortfolioRepository
import net.wolffentp.stockstreamportfolio.data.repo.SettingsRepository
import net.wolffentp.stockstreamportfolio.data.repo.ViewRepository
import net.wolffentp.stockstreamportfolio.storage.SecurePrefs
import net.wolffentp.stockstreamportfolio.ui.screens.StockStreamApp
import net.wolffentp.stockstreamportfolio.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val prefs by lazy { SecurePrefs(this) }
    private val authManager by lazy { MsalAuthManager(this) }
    private val api by lazy { ApiFactory.create(prefs) }
    private val signalR by lazy { SignalRQuoteClient { prefs.getAccessToken() } }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(
            authManager,
            prefs,
            PortfolioRepository(api),
            SettingsRepository(api),
            ViewRepository(api),
            ColumnRepository(api),
            signalR
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.initialize()

        setContent {
            StockStreamApp(
                activity = this,
                viewModel = viewModel,
                authManager = authManager
            )
        }
    }
}
