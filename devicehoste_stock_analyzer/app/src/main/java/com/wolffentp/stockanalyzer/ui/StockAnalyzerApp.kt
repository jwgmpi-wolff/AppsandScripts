package com.wolffentp.stockanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wolffentp.stockanalyzer.domain.AnalysisResult
import com.wolffentp.stockanalyzer.domain.Direction
import com.wolffentp.stockanalyzer.domain.Horizon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val AppColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF7B1FA2),
    background = Color(0xFFF7F7F4),
    surface = Color.White,
    onSurface = Color(0xFF19191D),
)

@Composable
fun StockAnalyzerApp(stockViewModel: StockViewModel = viewModel()) {
    val state by stockViewModel.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = AppColors, typography = MaterialTheme.typography.copy(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    )) {
        val selected = state.selectedSymbol?.let { symbol -> state.rows.firstOrNull { it.symbol == symbol } }
        if (selected == null) {
            Dashboard(state, stockViewModel)
        } else {
            AnalysisDetail(selected, onBack = { stockViewModel.select(null) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(state: StockUiState, model: StockViewModel) {
    var symbolInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }
    var holdingEntry by remember { mutableStateOf<StockRowState?>(null) }
    var deleteSymbol by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Stock Movement Analyzer") },
            actions = {
                IconButton(onClick = { confirmClear = true }, enabled = state.rows.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear watchlist")
                }
                IconButton(onClick = model::refresh, enabled = !state.isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh live data")
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).padding(horizontal = 16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Probabilistic analysis", style = MaterialTheme.typography.headlineLarge, fontSize = 30.sp)
                Text("Fresh timestamped trends and sourced news. Not financial advice.", color = Color(0xFF55555D))
                Text("Time horizon", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Horizon.entries) { horizon ->
                        FilterChip(
                            selected = state.horizon == horizon,
                            onClick = { model.setHorizon(horizon) },
                            label = { Text(horizon.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = symbolInput,
                        onValueChange = { symbolInput = it; inputError = false },
                        modifier = Modifier.weight(1f),
                        label = { Text("Stock symbol") },
                        singleLine = true,
                        isError = inputError,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            inputError = !model.addSymbol(symbolInput)
                            if (!inputError) symbolInput = ""
                        }),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        inputError = !model.addSymbol(symbolInput)
                        if (!inputError) symbolInput = ""
                    }) { Icon(Icons.Default.Add, contentDescription = "Save symbol to watchlist") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(if (state.isRefreshing) "Refreshing live data" else "Live refresh", fontWeight = FontWeight.Bold)
                        Text(
                            "Every 60 seconds · Last ${state.lastRefreshAt.formatTimeOnly()}",
                            color = Color(0xFF66666D),
                            fontSize = 13.sp,
                        )
                    }
                    Switch(checked = state.autoRefresh, onCheckedChange = model::setAutoRefresh)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.rows.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(state.rows, key = { it.symbol }) { row ->
                        StockGridCard(
                            row = row,
                            horizon = state.horizon,
                            onClick = { model.select(row.symbol) },
                            onEditHolding = { holdingEntry = row },
                            onDelete = { deleteSymbol = row.symbol },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Watchlist is empty", style = MaterialTheme.typography.titleLarge)
                    Text("Enter a stock symbol above to save it locally.", color = Color(0xFF606067))
                }
            }
        }
    }
    holdingEntry?.let { row ->
        HoldingDialog(
            row = row,
            onDismiss = { holdingEntry = null },
            onSave = { quantity, averageCost ->
                model.saveHolding(row.symbol, quantity, averageCost).also { saved ->
                    if (saved) holdingEntry = null
                }
            },
            onClear = {
                model.clearHolding(row.symbol)
                holdingEntry = null
            },
        )
    }
    deleteSymbol?.let { symbol ->
        ConfirmationDialog(
            title = "Delete $symbol?",
            message = "This removes the symbol and its logged holding from this device.",
            confirmLabel = "Delete",
            onConfirm = { model.deleteSymbol(symbol); deleteSymbol = null },
            onDismiss = { deleteSymbol = null },
        )
    }
    if (confirmClear) {
        ConfirmationDialog(
            title = "Clear watchlist?",
            message = "This removes every saved symbol and logged holding from this device.",
            confirmLabel = "Clear all",
            onConfirm = { model.clearWatchlist(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun StockGridCard(
    row: StockRowState,
    horizon: Horizon,
    onClick: () -> Unit,
    onEditHolding: () -> Unit,
    onDelete: () -> Unit,
) {
    val result = row.analysis
    val color = Color(DirectionPalette.argb(result?.direction ?: Direction.NEUTRAL_INSUFFICIENT_DATA))
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.symbol, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(horizon.label, color = Color(0xFF606067), fontWeight = FontWeight.Bold)
            }
            Text(result?.quote?.price?.let { String.format(Locale.US, "$%,.2f", it) } ?: "Price unavailable", fontSize = 21.sp)
            Text(directionLabel(result?.direction), color = color, fontWeight = FontWeight.Bold)
            Text(result?.let { "Confidence ${it.confidence}%" } ?: "Not calculated", color = color)
            Text(holdingText(row), color = Color(0xFF3F3F45), fontSize = 13.sp)
            Text("Source ${result?.lastDataTimestamp.formatTimestamp()}", color = Color(0xFF606067), fontSize = 12.sp)
            if (row.error != null) Text(row.error, color = color, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEditHolding) {
                    Icon(Icons.Default.Edit, contentDescription = "Log ${row.symbol} holding")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${row.symbol} from watchlist")
                }
            }
        }
    }
}

@Composable
private fun HoldingDialog(
    row: StockRowState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Boolean,
    onClear: () -> Unit,
) {
    var quantity by remember(row.symbol) { mutableStateOf(row.quantity?.displayNumber().orEmpty()) }
    var averageCost by remember(row.symbol) { mutableStateOf(row.averageCost?.displayNumber().orEmpty()) }
    var invalid by remember(row.symbol) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log ${row.symbol} holding") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saved locally on this device. Holdings do not affect movement analysis.")
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it; invalid = false },
                    label = { Text("Shares") },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = averageCost,
                    onValueChange = { averageCost = it; invalid = false },
                    label = { Text("Average cost per share (optional)") },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (invalid) Text("Enter positive shares and a nonnegative average cost.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onSave(quantity, averageCost) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (row.quantity != null) TextButton(onClick = onClear) { Text("Clear holding") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisDetail(row: StockRowState, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("${row.symbol} analysis") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (row.analysis == null) {
                item {
                    Text("Live data unavailable", style = MaterialTheme.typography.headlineLarge)
                    Text(row.error ?: "No timestamped provider data was returned. No prediction was generated.")
                    Text("Provider: Not available\nConfidence: Not calculated\nSignals: Not calculated")
                }
            } else {
                val result = row.analysis
                item { DetailHeader(result) }
                item { DetailSection("Holding", holdingText(row)) }
                item { DetailSection("Source", sourceText(result)) }
                item { DetailSection("Indicators", indicatorText(result)) }
                item { DetailSection("Signal weights", signalText(result)) }
                item { DetailSection("News research", newsText(result)) }
                item { DetailSection("Gaps and limitations", result.warnings.ifEmpty { listOf("None reported") }.joinToString("\n") { "- $it" }) }
                item { DetailSection("Final reason", result.reason) }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun DetailHeader(result: AnalysisResult) {
    val color = Color(DirectionPalette.argb(result.direction))
    Column {
        Text(directionLabel(result.direction), style = MaterialTheme.typography.headlineLarge, color = color)
        Text("Confidence ${result.confidence}%", color = color, fontWeight = FontWeight.Bold)
        Text("Probabilistic ${result.horizon.label} projection. Not financial advice.")
    }
}

@Composable
private fun DetailSection(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Color(0xFF3F3F45))
    }
}

private fun sourceText(result: AnalysisResult) = buildString {
    appendLine("Provider: ${result.provider}")
    appendLine("Retrieved at: ${result.retrievedAt.formatTimestamp()}")
    appendLine("Latest source timestamp: ${result.lastDataTimestamp.formatTimestamp()}")
    appendLine("Source age: ${result.sourceAgeMinutes?.let { "$it minutes" } ?: "Unavailable"}")
    appendLine("Candle interval: ${result.candleIntervalMinutes} minute(s)")
    append("Latest quote: ${result.quote?.let { String.format(Locale.US, "$%,.2f at %s", it.price, it.timestamp.formatTimestamp()) } ?: "Unsupported / unavailable"}")
}

private fun indicatorText(result: AnalysisResult): String {
    val values = result.indicators ?: return "Indicators were not calculated because live-data validation failed."
    fun Double?.display() = this?.let { String.format(Locale.US, "%.4f", it) } ?: "Unsupported / unavailable"
    return "Momentum: ${values.momentumPercent.display()}%\nShort moving average: ${values.shortMovingAverage.display()}\nLong moving average: ${values.longMovingAverage.display()}\nRelative volume: ${values.relativeVolume.display()}\nRSI: ${values.rsi.display()}\nMACD: ${values.macd.display()}\nVWAP: ${values.vwap.display()}\nFresh news sentiment average: ${values.sentimentAverage.display()}"
}

private fun signalText(result: AnalysisResult) = buildString {
    result.signals.forEach { signal ->
        appendLine("${signal.name}: value ${signal.value?.let { String.format(Locale.US, "%.3f", it) } ?: "unused"} x weight ${String.format(Locale.US, "%.2f", signal.weight)} = ${signal.contribution?.let { String.format(Locale.US, "%.3f", it) } ?: "unused"}")
    }
    append("Confidence: absolute normalized weighted score x 100 = ${result.confidence}%")
}

private fun newsText(result: AnalysisResult): String {
    val news = result.news ?: return "No timestamped news was returned for this refresh."
    if (news.items.isEmpty()) return "${news.provider} returned no sourced articles at ${news.retrievedAt.formatTimestamp()}."
    return buildString {
        appendLine("Provider: ${news.provider} · Retrieved ${news.retrievedAt.formatTimestamp()}")
        news.items.forEach { article ->
            appendLine()
            appendLine(article.headline)
            append("${article.source} · ${article.publishedAt.formatTimestamp()} · Score ${String.format(Locale.US, "%+.3f", article.score)} · ${article.scoringMethod}")
        }
    }
}

private fun directionLabel(direction: Direction?) = when (direction) {
    Direction.UP -> "UP (probabilistic)"
    Direction.DOWN -> "DOWN (probabilistic)"
    Direction.NEUTRAL_INSUFFICIENT_DATA -> "NEUTRAL / INSUFFICIENT DATA"
    null -> "LIVE DATA UNAVAILABLE"
}

private fun holdingText(row: StockRowState): String {
    val quantity = row.quantity ?: return "No holding logged"
    val cost = row.averageCost?.let { " @ ${String.format(Locale.US, "$%,.2f", it)} avg" }.orEmpty()
    val liveValue = row.analysis?.quote?.price?.let { price ->
        " · Live value ${String.format(Locale.US, "$%,.2f", quantity * price)}"
    }.orEmpty()
    return "${quantity.displayNumber()} shares$cost$liveValue"
}

private fun Double.displayNumber(): String = String.format(Locale.US, "%.4f", this).trimEnd('0').trimEnd('.')

private fun Instant?.formatTimestamp(): String = this?.atZone(ZoneId.systemDefault())?.format(TIMESTAMP_FORMAT) ?: "Not available"
private fun Instant?.formatTimeOnly(): String = this?.atZone(ZoneId.systemDefault())?.format(TIME_FORMAT) ?: "not yet"

private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")