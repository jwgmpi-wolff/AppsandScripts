package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jerrywolff.phonesyncusbc.ArtifactDataReaderView
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexDatabase
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexer
import com.jerrywolff.phonesyncusbc.data.RecoveryIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TabletReaderApp() }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TabletReaderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { ArchiveImporter(context) }
    val folderSourceManager = remember { ExternalFolderSourceManager(context) }
    val sourcePreferences = remember {
        context.getSharedPreferences(READER_SOURCE_PREFERENCES, Context.MODE_PRIVATE)
    }
    val database = remember { ArtifactIndexDatabase(context, "tablet_reader.sqlite") }
    val indexer = remember { ArtifactIndexer(context, database) }
    val initialArchive = remember { importer.load() }
    val initialFolder = remember { folderSourceManager.loadSnapshot() }
    val initialMode = remember {
        runCatching {
            ReaderContentMode.valueOf(
                sourcePreferences.getString(READER_SOURCE_MODE_KEY, null)
                    ?: if (initialFolder != null) ReaderContentMode.FOLDER.name else ReaderContentMode.ARCHIVE.name,
            )
        }.getOrDefault(ReaderContentMode.ARCHIVE)
    }
    var archiveImport by remember { mutableStateOf(initialArchive) }
    var folderSource by remember { mutableStateOf(initialFolder) }
    var sourceModeName by remember { mutableStateOf(initialMode.name) }
    var showReader by remember {
        mutableStateOf(
            when (initialMode) {
                ReaderContentMode.ARCHIVE -> initialArchive?.entries?.isNotEmpty() == true
                ReaderContentMode.FOLDER -> initialFolder?.entries?.isNotEmpty() == true
            },
        )
    }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ArchiveImportProgress?>(null) }
    var folderScanning by remember { mutableStateOf(false) }
    var folderProgress by remember { mutableStateOf<FolderScanProgress?>(null) }
    var status by remember {
        mutableStateOf(
            when (initialMode) {
                ReaderContentMode.ARCHIVE -> initialArchive?.let { "Verified ${it.entries.size} archive item(s)." }
                ReaderContentMode.FOLDER -> initialFolder?.let { "Loaded ${it.entries.size} folder file(s)." }
            } ?: "Choose an external-storage folder or open a Phone Sync backup archive.",
        )
    }

    fun selectMode(mode: ReaderContentMode) {
        sourceModeName = mode.name
        sourcePreferences.edit().putString(READER_SOURCE_MODE_KEY, mode.name).apply()
    }

    fun scanFolder(treeUri: Uri) {
        selectMode(ReaderContentMode.FOLDER)
        folderSourceManager.rememberTreeUri(treeUri)
        folderScanning = true
        folderProgress = null
        status = "Scanning the selected external-storage folder recursively..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                folderSourceManager.scan(treeUri) { update ->
                    scope.launch(Dispatchers.Main.immediate) { folderProgress = update }
                }
            }
            folderScanning = false
            folderSource = result
            status = if (result.entries.isNotEmpty()) {
                "Refreshed ${result.entries.size} file(s) from ${result.sourceName}."
            } else {
                result.error ?: "No readable files were found in the selected folder."
            }
            showReader = result.entries.isNotEmpty()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val permissionError = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.exceptionOrNull()
        if (permissionError != null || !folderSourceManager.hasPersistedReadAccess(uri)) {
            status = "Android could not retain read access to this folder. Choose it again and approve folder access. " +
                permissionError?.message.orEmpty()
            showReader = false
            return@rememberLauncherForActivityResult
        }
        scanFolder(uri)
    }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectMode(ReaderContentMode.ARCHIVE)
        importing = true
        progress = null
        status = "Validating archive provenance and integrity..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                importer.import(uri) { update ->
                    scope.launch(Dispatchers.Main.immediate) { progress = update }
                }
            }
            importing = false
            archiveImport = result
            if (result.entries.isNotEmpty()) {
                status = "Verified ${result.entries.size} external-source item(s)."
                showReader = true
            } else {
                status = "Import failed: ${result.error ?: "no item passed verification"}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialMode == ReaderContentMode.FOLDER) {
            folderSourceManager.selectedTreeUri()?.let { treeUri ->
                if (folderSourceManager.hasPersistedReadAccess(treeUri)) {
                    scanFolder(treeUri)
                } else {
                    showReader = false
                    status = "Folder access expired. Choose the external-storage folder again to refresh its content."
                }
            }
        }
    }

    val sourceMode = ReaderContentMode.valueOf(sourceModeName)
    val currentContent = when (sourceMode) {
        ReaderContentMode.ARCHIVE -> archiveImport?.toContentSnapshot()
        ReaderContentMode.FOLDER -> folderSource?.toContentSnapshot()
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Phone Sync Data Reader") },
                    navigationIcon = {
                        Image(
                            painter = painterResource(R.drawable.ic_reader_logo),
                            contentDescription = "Data reader",
                            modifier = Modifier.padding(start = 12.dp).size(40.dp),
                        )
                    },
                )
            },
        ) { padding ->
            if (showReader && currentContent != null && currentContent.entries.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (currentContent.issues.isNotEmpty()) {
                        Text(
                            "${currentContent.issues.size} item(s) need remediation; readable content remains available.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ArtifactDataReaderView(
                        entries = currentContent.entries,
                        initialSourceId = currentContent.sourceId,
                        initialSourceName = currentContent.sourceName,
                        database = database,
                        indexer = indexer,
                        onBack = { showReader = false },
                        contentSourceLabel = currentContent.sourceLabel,
                        contentRefreshing = folderScanning,
                        onChooseContentSource = { folderPicker.launch(null) },
                        onRefreshContent = if (sourceMode == ReaderContentMode.FOLDER) {
                            {
                                folderSourceManager.selectedTreeUri()?.let(::scanFolder)
                                    ?: run { status = "Choose an external-storage folder first." }
                            }
                        } else {
                            null
                        },
                    )
                }
                return@Scaffold
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Tablet recovery data reader", style = MaterialTheme.typography.headlineMedium)
                Text("Choose an external-storage folder for recursive browsing and refresh, or open a verified Phone Sync backup archive.")
                Button(
                    onClick = { folderPicker.launch(null) },
                    enabled = !folderScanning && !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose external-storage folder")
                }
                if (folderSourceManager.selectedTreeUri() != null) {
                    OutlinedButton(
                        onClick = {
                            folderSourceManager.selectedTreeUri()?.let(::scanFolder)
                        },
                        enabled = !folderScanning && !importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (folderScanning) "Refreshing folder..." else "Refresh selected folder")
                    }
                }
                Button(
                    onClick = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (importing) "Importing archive..." else "Open recovery archive")
                }
                if (folderScanning) {
                    val current = folderProgress
                    val fraction = current?.takeIf { it.totalFiles > 0 }
                        ?.let { it.processedFiles.toFloat() / it.totalFiles }
                        ?: 0f
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    current?.let {
                        Text(
                            if (it.totalFiles > 0) {
                                "${it.processedFiles}/${it.totalFiles} files · ${it.currentItem} · ${formatBytes(it.bytesRead)}"
                            } else {
                                "Discovering folders · ${it.discoveredFiles} files found · ${it.currentItem}"
                            },
                        )
                    }
                }
                if (importing) {
                    val current = progress
                    val fraction = current?.takeIf { it.totalItems > 0 }
                        ?.let { it.completedItems.toFloat() / it.totalItems }
                        ?: 0f
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    current?.let {
                        Text(
                            "${it.completedItems}/${it.totalItems} verified · ${it.currentItem.substringAfterLast('/')} · " +
                                formatBytes(it.bytesRead),
                        )
                    }
                }
                Text(status)
                currentContent?.takeIf { it.entries.isNotEmpty() }?.let {
                    OutlinedButton(onClick = { showReader = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open reader database")
                    }
                }
                currentContent?.issues?.takeIf(List<*>::isNotEmpty)?.let { issues ->
                    Text("Recovery actions", style = MaterialTheme.typography.titleLarge)
                    issues.take(12).forEach { issue ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(issue.sourceItem.substringAfterLast('/').ifBlank { issue.sourceItem })
                                Text(issue.remediation, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.1f GB".format(bytes / 1_073_741_824.0)
}

private enum class ReaderContentMode {
    ARCHIVE,
    FOLDER,
}

private data class ReaderContentSnapshot(
    val sourceId: String,
    val sourceName: String,
    val sourceLabel: String,
    val entries: List<AuditEntry>,
    val issues: List<RecoveryIssue>,
)

private fun ArchiveImportResult.toContentSnapshot() = ReaderContentSnapshot(
    sourceId = sourceId,
    sourceName = sourceName,
    sourceLabel = "Verified Phone Sync archive · ${entries.size} items",
    entries = entries,
    issues = issues,
)

private fun FolderScanResult.toContentSnapshot() = ReaderContentSnapshot(
    sourceId = sourceId,
    sourceName = sourceName,
    sourceLabel = "External folder: $sourceName · ${entries.size} files",
    entries = entries,
    issues = issues,
)

private const val READER_SOURCE_PREFERENCES = "reader_content_source"
private const val READER_SOURCE_MODE_KEY = "source_mode"