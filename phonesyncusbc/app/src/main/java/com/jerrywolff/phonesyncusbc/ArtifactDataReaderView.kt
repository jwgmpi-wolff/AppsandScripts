package com.jerrywolff.phonesyncusbc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexDatabase
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexProgress
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexResult
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexStats
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexer
import com.jerrywolff.phonesyncusbc.data.ArtifactFocus
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.IndexedRecordDetail
import com.jerrywolff.phonesyncusbc.data.IndexedRecordSummary
import com.jerrywolff.phonesyncusbc.data.IndexedSource
import com.jerrywolff.phonesyncusbc.data.ParsedRecordKind
import com.jerrywolff.phonesyncusbc.data.externalDeviceRecoveryEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

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
    var selectedFocus by remember { mutableStateOf(ArtifactFocus.ALL) }
    var selectedRecordIds by remember { mutableStateOf(emptySet<Long>()) }
    var selectedOnly by remember { mutableStateOf(false) }
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
                    focus = selectedFocus,
                    recordIds = if (selectedOnly) selectedRecordIds else null,
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

    LaunchedEffect(initialSourceId, entries.map(AuditEntry::id)) {
        val sourceId = initialSourceId
        if (sourceId.isNullOrBlank()) {
            status = "No selected external USB source is available."
            records = emptyList()
            return@LaunchedEffect
        }
        indexing = true
        val strictEntries = externalDeviceRecoveryEntries(entries, sourceId)
        val result = withContext(Dispatchers.IO) {
            database.retainOnlySource(sourceId)
            val expectedTransferIds = strictEntries.mapTo(linkedSetOf(), AuditEntry::id)
            if (database.sourceTransferIds(sourceId) != expectedTransferIds) {
                indexer.rebuild(strictEntries, sourceId, initialSourceName) { update ->
                    scope.launch(Dispatchers.Main.immediate) { progress = update }
                }
            } else {
                null
            }
        }
        indexing = false
        if (result != null) {
            indexResult = result
            status = if (result.failedArtifacts == 0) {
                "Verified external-source index: ${result.recordsIndexed} records."
            } else {
                "External-source index refreshed; ${result.failedArtifacts} artifacts failed."
            }
        } else if (strictEntries.isEmpty()) {
            status = "No verified records exist for the selected external USB source."
        }
        selectedSourceId = sourceId
        refresh(resetOffset = true)
    }

    detail?.let { selected ->
        ParsedRecordDetail(
            detail = selected,
            onBack = { detail = null },
        )
        return
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recovered data browser", style = MaterialTheme.typography.headlineSmall)
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
            Text("Focus", style = MaterialTheme.typography.titleSmall)
            ArtifactFocus.entries.chunked(3).forEach { focusRow ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    focusRow.forEach { focus ->
                    ReaderFilterButton(focus.label, selectedFocus == focus) {
                        selectedFocus = focus
                        refresh(resetOffset = true)
                    }
                    }
                }
            }
            Text("Source", style = MaterialTheme.typography.titleSmall)
            Text(
                sources.singleOrNull { it.sourceId == selectedSourceId }?.displayName ?: initialSourceName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Record type", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    ReaderFilterButton("All", selectedKind == null) {
                        selectedKind = null
                        refresh(resetOffset = true)
                    }
                }
                items(kinds, key = ParsedRecordKind::name) { kind ->
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFilterButton("Selected only (${selectedRecordIds.size})", selectedOnly) {
                    selectedOnly = !selectedOnly
                    refresh(resetOffset = true)
                }
                OutlinedButton(
                    onClick = {
                        selectedRecordIds = emptySet()
                        if (selectedOnly) refresh(resetOffset = true)
                    },
                    enabled = selectedRecordIds.isNotEmpty(),
                ) { Text("Clear selection") }
            }
            if (records.isEmpty()) {
                Text("No parsed records match this query.")
            } else {
                records.forEach { record ->
                    ParsedRecordCard(
                        record = record,
                        selected = record.id in selectedRecordIds,
                        onSelectedChange = { selected ->
                            selectedRecordIds = if (selected) selectedRecordIds + record.id else selectedRecordIds - record.id
                            if (selectedOnly) refresh(resetOffset = true)
                        },
                        onOpen = {
                            scope.launch {
                                detail = selectedSourceId?.let { sourceId ->
                                    withContext(Dispatchers.IO) { database.recordDetail(record.id, sourceId) }
                                }
                            }
                        },
                    )
                }
                OutlinedButton(
                    onClick = { selectedRecordIds = selectedRecordIds + records.map(IndexedRecordSummary::id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Select this page") }
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
private fun ParsedRecordCard(
    record: IndexedRecordSummary,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Text("Selected", modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = onOpen) { Text("View") }
            }
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
            RecordMediaPreview(detail.record)
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
private fun RecordMediaPreview(record: IndexedRecordSummary) {
    val context = LocalContext.current
    if (record.isImageRecord()) {
        var bitmap by remember(record.id) { mutableStateOf<Bitmap?>(null) }
        var loading by remember(record.id) { mutableStateOf(true) }
        LaunchedEffect(record.id) {
            bitmap = withContext(Dispatchers.IO) { readRecordBitmap(context, record) }
            loading = false
        }
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = record.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(360.dp),
            )
            loading -> Text("Loading image preview...")
            else -> Text("This image format could not be previewed on this device.")
        }
    }
    if (record.isVoicemailAudio()) {
        VoicemailPlayer(record)
    }
}

@Composable
private fun VoicemailPlayer(record: IndexedRecordSummary) {
    val context = LocalContext.current
    var prepared by remember(record.id) { mutableStateOf<PreparedAudio?>(null) }
    var error by remember(record.id) { mutableStateOf<String?>(null) }
    var playing by remember(record.id) { mutableStateOf(false) }
    LaunchedEffect(record.id) {
        runCatching { withContext(Dispatchers.IO) { prepareAudio(context, record) } }
            .onSuccess { audio ->
                prepared = audio
                audio.player.setOnCompletionListener { playing = false }
            }
            .onFailure { error = it.message ?: "This voicemail format is not supported on this device." }
    }
    DisposableEffect(prepared) {
        onDispose {
            prepared?.player?.release()
            prepared?.cacheFile?.delete()
        }
    }
    Text("Voicemail", style = MaterialTheme.typography.titleMedium)
    when {
        prepared != null -> Button(
            onClick = {
                val player = prepared!!.player
                if (player.isPlaying) {
                    player.pause()
                    playing = false
                } else {
                    player.start()
                    playing = true
                }
            },
        ) { Text(if (playing) "Pause" else "Play") }
        error != null -> Text(error!!, style = MaterialTheme.typography.bodySmall)
        else -> Text("Preparing voicemail audio...", style = MaterialTheme.typography.bodySmall)
    }
}

private data class PreparedAudio(val player: MediaPlayer, val cacheFile: File? = null)

private fun readRecordBitmap(context: Context, record: IndexedRecordSummary): Bitmap? {
    val uri = record.destinationUri?.let(Uri::parse) ?: return null
    return if (record.isZipEntry()) {
        var bitmap: Bitmap? = null
        context.contentResolver.openInputStream(uri).use { input ->
            if (input != null) {
                ZipInputStream(input.buffered()).use { archive ->
                    while (bitmap == null) {
                        val entry = archive.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.normalizedEntry() == record.jsonSource.normalizedEntry()) {
                            bitmap = BitmapFactory.decodeStream(archive)
                        }
                        archive.closeEntry()
                    }
                }
            }
        }
        bitmap
    } else {
        context.contentResolver.openInputStream(uri).use(BitmapFactory::decodeStream)
    }
}

private fun prepareAudio(context: Context, record: IndexedRecordSummary): PreparedAudio {
    val uri = record.destinationUri?.let(Uri::parse) ?: error("Recovered voicemail is unavailable.")
    if (!record.isZipEntry()) {
        val player = MediaPlayer()
        player.setDataSource(context, uri)
        player.prepare()
        return PreparedAudio(player)
    }
    val cacheDirectory = File(context.cacheDir, "reader-audio").apply { mkdirs() }
    val cacheFile = File(cacheDirectory, "${record.id}-${record.title.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
    context.contentResolver.openInputStream(uri).use { input ->
        checkNotNull(input) { "Recovered voicemail archive is unavailable." }
        ZipInputStream(input.buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: error("Voicemail entry is missing from its archive.")
                if (!entry.isDirectory && entry.name.normalizedEntry() == record.jsonSource.normalizedEntry()) {
                    cacheFile.outputStream().use(archive::copyTo)
                    break
                }
                archive.closeEntry()
            }
        }
    }
    val player = MediaPlayer()
    player.setDataSource(cacheFile.absolutePath)
    player.prepare()
    return PreparedAudio(player, cacheFile)
}

private fun IndexedRecordSummary.isZipEntry(): Boolean =
    sourcePath.lowercase().endsWith(".zip") && jsonSource.normalizedEntry() != sourcePath.normalizedEntry()

private fun IndexedRecordSummary.isImageRecord(): Boolean = extensionOf(jsonSource) in IMAGE_EXTENSIONS

private fun IndexedRecordSummary.isVoicemailAudio(): Boolean =
    category == com.jerrywolff.phonesyncusbc.domain.ConsentCategory.VOICEMAIL_EXPORTS &&
        extensionOf(jsonSource) in AUDIO_EXTENSIONS

private fun String.normalizedEntry(): String = replace('\\', '/').trimStart('/')

private fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase()

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
private val IMAGE_EXTENSIONS = setOf("bmp", "dng", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
private val AUDIO_EXTENSIONS = setOf("3gp", "3gpp", "aac", "aif", "aiff", "amr", "au", "awb", "caf", "evrc", "flac", "m4a", "m4b", "mp3", "mp4", "oga", "ogg", "opus", "qcp", "snd", "wav", "weba", "wma")