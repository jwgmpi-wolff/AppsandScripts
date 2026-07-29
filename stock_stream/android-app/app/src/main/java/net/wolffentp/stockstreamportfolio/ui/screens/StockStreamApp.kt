package net.wolffentp.stockstreamportfolio.ui.screens

import android.app.Activity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.wolffentp.stockstreamportfolio.auth.AuthState
import net.wolffentp.stockstreamportfolio.auth.MsalAuthManager
import net.wolffentp.stockstreamportfolio.data.model.ColumnLayout
import net.wolffentp.stockstreamportfolio.data.model.QuoteRow
import net.wolffentp.stockstreamportfolio.ui.viewmodel.MainViewModel

@Composable
fun StockStreamApp(
    activity: Activity,
    viewModel: MainViewModel,
    authManager: MsalAuthManager
) {
    val authState by viewModel.authState.collectAsState()

    when (val state = authState) {
        AuthState.SignedOut -> SignInScreen(activity, viewModel, authManager)
        AuthState.SigningIn -> LoadingScreen("Checking secure session...")
        is AuthState.Error -> ErrorScreen(state.message)
        is AuthState.SignedIn -> PortfolioScreen(viewModel, state.accountId)
    }
}

@Composable
private fun SignInScreen(activity: Activity, viewModel: MainViewModel, authManager: MsalAuthManager) {
    val scope = rememberCoroutineScope()
    var signInError by remember { mutableStateOf<String?>(null) }
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("StockStreamPortfolio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("Microsoft Entra ID sign-in is required before any portfolio or quote data is shown.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                scope.launch {
                    signInError = null
                    val tokenResult = authManager.signIn(activity)
                    tokenResult.onSuccess { result ->
                        viewModel.onSignedIn(result.accessToken, result.accountId)
                    }.onFailure {
                        signInError = "${it.javaClass.simpleName}: ${it.message ?: "Microsoft sign-in failed. Check app registration and redirect URI configuration."}"
                    }
                }
            }) {
                Text("Sign in with Microsoft")
            }

            if (!signInError.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = signInError ?: "",
                    color = Color(0xFFB00020)
                )
            }
        }
    }
}

@Composable
private fun PortfolioScreen(viewModel: MainViewModel, accountId: String) {
    val watchlist by viewModel.watchlist.collectAsState()
    val quotes by viewModel.quotes.collectAsState()
    val views by viewModel.views.collectAsState()
    val activeViewIndex by viewModel.activeViewIndex.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isFullscreen by viewModel.isFullscreen.collectAsState()
    val signalRConnected by viewModel.signalRConnected.collectAsState()
    val columnLayout by viewModel.columnLayout.collectAsState()

    var symbolInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var notesInput by remember { mutableStateOf("") }
    var refreshIntervalInput by remember { mutableStateOf("") }
    var refreshIntervalError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings?.settings?.refreshIntervalSeconds) {
        val interval = settings?.settings?.refreshIntervalSeconds
        if (interval != null) {
            refreshIntervalInput = interval.toString()
        }
    }

    val subtleShift = rememberInfiniteTransition(label = "burnin").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24000), repeatMode = RepeatMode.Reverse),
        label = "shift"
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF071219), Color(0xFF102B2F), Color(0xFF0D1D24))
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
                .alpha(if (isFullscreen) 0.96f else 1f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Live Portfolio", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Row {
                    OutlinedButton(onClick = { viewModel.previousView() }) { Text("Prev") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.nextView() }) { Text("Next") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.setFullscreen(!isFullscreen) }) { Text(if (isFullscreen) "Windowed" else "Full Screen") }
                }
            }

            Spacer(Modifier.height(8.dp))
            val activeView = views.getOrNull(activeViewIndex)
            Text(
                text = "View: ${activeView?.name ?: "None"} | Market status comes from backend/provider only",
                color = Color(0xFFB7E5D4)
            )

            Text(
                text = quotes?.lastSuccessfulLiveUpdateTimestampUtc
                    ?.let { "Last successful live update: $it" }
                    ?: "Market closed or live data unavailable.",
                color = Color(0xFFFFD08A),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                text = if (signalRConnected) "Streaming: Connected (SignalR)" else "Streaming unavailable, polling fallback active",
                color = if (signalRConnected) Color(0xFF9BF3C8) else Color(0xFFFFC9A7)
            )

            Spacer(Modifier.height(10.dp))
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium)
                    Text("Signed in account: $accountId")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.signOut() }) { Text("Log out") }
                        OutlinedButton(onClick = { viewModel.clearContext() }) { Text("Clear context") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Server refresh interval: ${settings?.settings?.refreshIntervalSeconds ?: "?"} sec",
                        color = Color.White
                    )
                    Text(
                        text = "Allowed range: ${settings?.minAllowedSeconds ?: "?"} - ${settings?.maxAllowedSeconds ?: "?"} sec",
                        color = Color(0xFFB7E5D4)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = refreshIntervalInput,
                        onValueChange = {
                            refreshIntervalInput = it
                            refreshIntervalError = null
                        },
                        label = { Text("Refresh interval (sec)") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val parsed = refreshIntervalInput.toIntOrNull()
                        val settingsState = settings
                        if (parsed == null) {
                            refreshIntervalError = "Enter a whole number"
                            return@Button
                        }
                        if (settingsState != null && (parsed < settingsState.minAllowedSeconds || parsed > settingsState.maxAllowedSeconds)) {
                            refreshIntervalError = "Choose a value between ${settingsState.minAllowedSeconds} and ${settingsState.maxAllowedSeconds} seconds"
                            return@Button
                        }
                        viewModel.updateRefreshInterval(parsed)
                    }) {
                        Text("Set interval")
                    }
                    if (!refreshIntervalError.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(refreshIntervalError ?: "", color = Color(0xFFB00020))
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Add Symbol")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = symbolInput, onValueChange = { symbolInput = it }, label = { Text("Symbol") })
                        OutlinedTextField(value = displayNameInput, onValueChange = { displayNameInput = it }, label = { Text("Display") })
                    }
                    OutlinedTextField(value = notesInput, onValueChange = { notesInput = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes") })
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        viewModel.addSymbol(symbolInput, displayNameInput.ifBlank { null }, notesInput.ifBlank { null })
                        symbolInput = ""
                        displayNameInput = ""
                        notesInput = ""
                    }) { Text("Add") }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Refresh interval: ${settings?.settings?.refreshIntervalSeconds ?: "?"} sec (admin limits enforced server-side)",
                color = Color.White
            )

            Spacer(Modifier.height(10.dp))
            ColumnLayoutEditor(
                layout = columnLayout,
                onMove = viewModel::moveColumn,
                onHide = viewModel::hideColumn,
                onUnhide = viewModel::unhideColumn,
                onReset = viewModel::resetColumns,
                onSave = viewModel::saveColumnLayout
            )

            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(watchlist, key = { it.symbol + it.addedAtUtc }) { item ->
                    val quote = quotes?.rows?.firstOrNull { it.symbol.equals(item.symbol, ignoreCase = true) }
                    QuoteRowCard(
                        quote = quote,
                        symbol = item.symbol,
                        displayName = item.displayName,
                        onRemove = { viewModel.removeSymbol(item.symbol) },
                        shift = subtleShift.value
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnLayoutEditor(
    layout: ColumnLayout?,
    onMove: (Int, Int) -> Unit,
    onHide: (String) -> Unit,
    onUnhide: (String) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit
) {
    if (layout == null) {
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Columns", style = MaterialTheme.typography.titleMedium)
            Text("Long-press and drag rows to reorder. Hidden columns are listed below.")

            layout.orderedColumns.forEachIndexed { index, column ->
                var dragDistance by remember(column) { mutableStateOf(0f) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(column, index) {
                            detectDragGesturesAfterLongPress(
                                onDrag = { _, dragAmount ->
                                    dragDistance += dragAmount.y
                                    if (dragDistance > 36f && index < layout.orderedColumns.lastIndex) {
                                        onMove(index, index + 1)
                                        dragDistance = 0f
                                    } else if (dragDistance < -36f && index > 0) {
                                        onMove(index, index - 1)
                                        dragDistance = 0f
                                    }
                                }
                            )
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val hidden = layout.hiddenColumns.contains(column)
                    Text(text = "${index + 1}. $column${if (hidden) " (hidden)" else ""}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (hidden) onUnhide(column) else onHide(column) }) {
                            Text(if (hidden) "Unhide" else "Hide")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset) { Text("Reset") }
                Button(onClick = onSave) { Text("Save Layout") }
            }
        }
    }
}

@Composable
private fun QuoteRowCard(
    quote: QuoteRow?,
    symbol: String,
    displayName: String?,
    onRemove: () -> Unit,
    shift: Float
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${displayName ?: symbol} ($symbol)", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }

            if (quote == null) {
                Text("Market closed or live data unavailable.")
                return@Column
            }

            Text("Provider: ${quote.dataSource}")
            Text("Retrieved: ${quote.retrievedAtUtc}")
            Text("Market: ${quote.marketStatus} | Freshness: ${quote.freshnessStatus} | Live: ${quote.isLive}")

            if (!quote.isLive) {
                Text(
                    text = quote.message ?: "Market closed or live data unavailable.",
                    color = Color(0xFFC15A00)
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val last = quote.fields["Last"] ?: "Unavailable"
                val bid = quote.fields["Bid"] ?: "Unavailable"
                val ask = quote.fields["Ask"] ?: "Unavailable"
                Text("Last: $last")
                Text("Bid: $bid")
                Text("Ask: $ask")
            }

            val afterHoursPrice = quote.fields["AfterHoursPrice"]
            val afterHoursChange = quote.fields["AfterHoursChange"]
            val afterHoursChangePercent = quote.fields["AfterHoursChangePercent"]
            if (!afterHoursPrice.isNullOrBlank() || !afterHoursChange.isNullOrBlank() || !afterHoursChangePercent.isNullOrBlank()) {
                Text(
                    text = "After Hours: ${afterHoursPrice ?: "N/A"} | Change: ${afterHoursChange ?: "N/A"} (${afterHoursChangePercent ?: "N/A"})",
                    color = Color(0xFF66C2FF)
                )
            }

            if (quote.errorCode != null || quote.errorMessage != null) {
                Text("Error: ${quote.errorCode ?: "UNKNOWN"} - ${quote.errorMessage ?: "Unavailable"}", color = Color.Red)
            }

            Box(modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF2D4951).copy(alpha = 0.35f + (0.2f * shift))))
        }
    }
}

@Composable
private fun LoadingScreen(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text)
    }
}

@Composable
private fun ErrorScreen(message: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Error", style = MaterialTheme.typography.titleLarge)
        Text(message)
    }
}
