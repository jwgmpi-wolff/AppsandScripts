package com.jerrywolff.phonesyncusbc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexDatabase
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexProgress
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexResult
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexStats
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexer
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.IndexedRecordDetail
import com.jerrywolff.phonesyncusbc.data.IndexedRecordSummary
import com.jerrywolff.phonesyncusbc.data.IndexedSource
import com.jerrywolff.phonesyncusbc.data.ParsedRecordKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArtifactDataReaderView(
    entries: List<AuditEntry>,
    initialSourceId: String?,
    initialSourceName: String,
    database: ArtifactIndexDatabase,
    indexer: ArtifactIndexer,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var indexing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ArtifactIndexProgress?>(null) }
    var indexResult by remember { mutableStateOf<ArtifactIndexResult?>(null) }
    var stats by remember { mutableStateOf(ArtifactIndexStats(0, 0, 0, 0, 0)) }
    var sources by remember { mutableStateOf(emptyList<IndexedSource>()) }
    var selectedSourceId by remember { mutableStateOf(initialSourceId) }
    var kinds by remember { mutableStateOf(emptyList<ParsedRecordKind>()) }
    var selectedKind by remember { mutableStateOf<ParsedRecordKind?>(null) }
    var search by remember { mutableStateOf("") }
    var records by remember { mutableStateOf(emptyList<IndexedRecordSummary>()) }
    var offset by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<IndexedRecordDetail?>(null) }
    var status by remember { mutableStateOf("Build the local index to flatten recovered JSON data.") }

    fun refresh(resetOffset: Boolean = false) {
        if (resetOffset) offset = 0
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val currentStats = database.stats()
                val currentSources = database.sources()
                val currentKinds = database.recordKinds(selectedSourceId)
                val currentRecords = database.queryRecords(
                    search = search,
                    sourceId = selectedSourceId,
                    recordKind = selectedKind,
                    limit = PAGE_SIZE,
                    offset = if (resetOffset) 0 else offset,
                )
                ReaderSnapshot(currentStats, currentSources, currentKinds, currentRecords)
            }
            stats = snapshot.stats
            sources = snapshot.sources
            kinds = snapshot.kinds
            records = snapshot.records
        }
    }

    LaunchedEffect(initialSourceId) { refresh(resetOffset = true) }

    detail?.let { selected ->
        ParsedRecordDetail(
            detail = selected,
            onBack = { detail = null },
        )
        return
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Parsed data reader", style = MaterialTheme.typography.headlineSmall)
            Text(
                "JSON records and every allowed SMS ZIP item are indexed locally. Password artifacts are excluded.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, enabled = !indexing && !exporting) { Text("Files") }
                Button(
                    onClick = {
                        val sourceId = initialSourceId
                        if (sourceId == null) {
                            status = "No external recovery source is selected."
                        } else {
                            indexing = true
                            indexResult = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    indexer.rebuild(entries, sourceId, initialSourceName) { update ->
                                        scope.launch(Dispatchers.Main.immediate) { progress = update }
                                    }
                                }
                                indexing = false
                                indexResult = result
                                status = if (result.failedArtifacts == 0) {
                                    "Indexed ${result.recordsIndexed} records and ${result.fieldsIndexed} fields."
                                } else {
                                    "Indexed ${result.recordsIndexed} records; ${result.failedArtifacts} artifacts failed."
                                }
                                selectedSourceId = sourceId
                                refresh(resetOffset = true)
                            }
                        }
                    },
                    enabled = !indexing && !exporting && entries.isNotEmpty(),
                ) {
                    Text(if (stats.artifactCount > 0) "Refresh index" else "Build index")
                }
            }
            if (indexing) {
                val current = progress
                val fraction = if (current != null && current.totalArtifacts > 0) {
                    current.processedArtifacts.toFloat() / current.totalArtifacts
                } else {
                    0f
                }
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    current?.let {
                        "${it.processedArtifacts}/${it.totalArtifacts} artifacts · " +
                            "${it.recordsIndexed} records · ${it.currentArtifact}"
                    } ?: "Preparing index...",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "${stats.sourceCount} sources · ${stats.artifactCount} artifacts · " +
                    "${stats.recordCount} records · ${stats.fieldCount} fields",
                style = MaterialTheme.typography.titleSmall,
            )
            Button(
                onClick = {
                    exporting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { database.exportSnapshot() }
                        exporting = false
                        status = if (result.uri != null) {
                            "Windows database exported to Downloads / Phone Sync / Data Reader / ${result.displayName}."
                        } else {
                            "Database export failed: ${result.error ?: "unknown error"}"
                        }
                    }
                },
                enabled = !indexing && !exporting && stats.artifactCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (exporting) "Exporting database..." else "Export SQLite database for Windows")
            }
            HorizontalDivider()
            Text("Source", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderFilterButton("All", selectedSourceId == null) {
                    selectedSourceId = null
                    selectedKind = null
                    refresh(resetOffset = true)
                }
                sources.take(MAX_FILTERS).forEach { source ->
                    ReaderFilterButton(source.displayName, selectedSourceId == source.sourceId) {
                        selectedSourceId = source.sourceId
                        selectedKind = null
                        refresh(resetOffset = true)
                    }
                }
            }
            Text("Record type", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderFilterButton("All", selectedKind == null) {
                    selectedKind = null
                    refresh(resetOffset = true)
                }
                kinds.take(MAX_FILTERS).forEach { kind ->
                    ReaderFilterButton(kind.readerLabel(), selectedKind == kind) {
                        selectedKind = kind
                        refresh(resetOffset = true)
                    }
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search messages, fields, names, or values") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { refresh(resetOffset = true) }, enabled = !indexing, modifier = Modifier.fillMaxWidth()) {
                Text("Search database")
            }
            if (records.isEmpty()) {
                Text("No parsed records match this query.")
            } else {
                records.forEach { record ->
                    ParsedRecordCard(record) {
                        scope.launch {
                            detail = withContext(Dispatchers.IO) { database.recordDetail(record.id) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            offset = (offset - PAGE_SIZE).coerceAtLeast(0)
                            refresh()
                        },
                        enabled = offset > 0,
                    ) { Text("Previous") }
                    OutlinedButton(
                        onClick = {
                            offset += PAGE_SIZE
                            refresh()
                        },
                        enabled = records.size == PAGE_SIZE,
                    ) { Text("Next") }
                    Text("${offset + 1}-${offset + records.size}", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun ParsedRecordCard(record: IndexedRecordSummary, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium)
            record.timestamp?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            Text(record.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${record.recordKind.readerLabel()} · ${record.collectionLabel} · ${record.sourceName}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(record.sourcePath, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpen) { Text("View parsed fields") }
        }
    }
}

@Composable
private fun ParsedRecordDetail(detail: IndexedRecordDetail, onBack: () -> Unit) {
    var fieldOffset by remember(detail.record.id) { mutableStateOf(0) }
    val visibleFields = detail.fields.drop(fieldOffset).take(FIELD_PAGE_SIZE)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(detail.record.title, style = MaterialTheme.typography.headlineSmall)
            detail.record.timestamp?.let { Text(it) }
            Text(detail.record.summary, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${detail.record.recordKind.readerLabel()} · ${detail.record.collectionLabel} · " +
                    "${detail.record.folderLabel} · ${detail.record.sourceName}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onBack) { Text("Back to query results") }
            HorizontalDivider()
            visibleFields.forEach { field ->
                Column {
                    Text(field.path, style = MaterialTheme.typography.labelMedium)
                    Text(if (field.valueType.name == "NULL") "null" else field.value)
                }
                HorizontalDivider()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { fieldOffset = (fieldOffset - FIELD_PAGE_SIZE).coerceAtLeast(0) },
                    enabled = fieldOffset > 0,
                ) { Text("Previous fields") }
                OutlinedButton(
                    onClick = { fieldOffset += FIELD_PAGE_SIZE },
                    enabled = fieldOffset + FIELD_PAGE_SIZE < detail.fields.size,
                ) { Text("Next fields") }
            }
            Text("${fieldOffset + 1}-${fieldOffset + visibleFields.size} of ${detail.fields.size} fields")
        }
    }
}

@Composable
private fun ReaderFilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

private fun ParsedRecordKind.readerLabel(): String = name
    .lowercase()
    .replaceFirstChar(Char::uppercase)

private data class ReaderSnapshot(
    val stats: ArtifactIndexStats,
    val sources: List<IndexedSource>,
    val kinds: List<ParsedRecordKind>,
    val records: List<IndexedRecordSummary>,
)

private const val PAGE_SIZE = 50
private const val FIELD_PAGE_SIZE = 100
private const val MAX_FILTERS = 6