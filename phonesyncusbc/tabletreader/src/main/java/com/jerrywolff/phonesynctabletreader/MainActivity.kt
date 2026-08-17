package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        val showFolderActionsOnly = intent.getBooleanExtra(EXTRA_SHOW_FOLDER_ACTIONS_ONLY, false) &&
            packageManager.checkSignatures(packageName, "$packageName.test") == PackageManager.SIGNATURE_MATCH
        setContent {
            if (showFolderActionsOnly) {
                MaterialTheme {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderFolderActions(
                            busy = false,
                            resyncing = false,
                            onConnect = {},
                            onResync = {},
                        )
                        ReaderArchiveActions(
                            busy = false,
                            refreshing = false,
                            onChooseLocation = {},
                            onChooseZip = {},
                            onRefreshLocation = {},
                        )
                    }
                }
            } else {
                TabletReaderApp()
            }
        }
    }

    companion object {
        internal const val EXTRA_SHOW_FOLDER_ACTIONS_ONLY =
            "com.jerrywolff.phonesynctabletreader.extra.SHOW_FOLDER_ACTIONS_ONLY"
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TabletReaderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { ArchiveImporter(context) }
    val folderSourceManager = remember { ExternalFolderSourceManager(context) }
    val archiveLocationManager = remember {
        ExternalFolderSourceManager(
            context = context,
            stateFileName = ARCHIVE_LOCATION_STATE_FILE,
            preferencesName = ARCHIVE_LOCATION_PREFERENCES,
        )
    }
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
    var archiveLocationScanning by remember { mutableStateOf(false) }
    var archiveLocationProgress by remember { mutableStateOf<FolderScanProgress?>(null) }
    var archiveLocationName by remember { mutableStateOf<String?>(null) }
    var archiveCandidates by remember { mutableStateOf(emptyList<ArchiveLocationCandidate>()) }
    var status by remember {
        mutableStateOf(
            when (initialMode) {
                ReaderContentMode.ARCHIVE -> initialArchive?.let { "Verified ${it.verifiedItemCount} archive item(s)." }
                ReaderContentMode.FOLDER -> initialFolder?.let { "Loaded ${it.entries.size} folder file(s)." }
            } ?: "Connect a OneDrive or storage folder, or open a Phone Sync backup archive.",
        )
    }

    fun selectMode(mode: ReaderContentMode) {
        sourceModeName = mode.name
        sourcePreferences.edit().putString(READER_SOURCE_MODE_KEY, mode.name).apply()
    }

    fun scanFolder(treeUri: Uri) {
        if (folderScanning || archiveLocationScanning || importing) return
        selectMode(ReaderContentMode.FOLDER)
        archiveCandidates = emptyList()
        archiveLocationName = null
        folderSourceManager.rememberTreeUri(treeUri)
        folderScanning = true
        folderProgress = null
        status = "Resyncing the selected OneDrive or storage folder recursively..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                folderSourceManager.scan(treeUri) { update ->
                    scope.launch(Dispatchers.Main.immediate) { folderProgress = update }
                }
            }
            folderScanning = false
            folderSource = result
            status = result.error ?: if (result.entries.isNotEmpty()) {
                "Resynced ${result.entries.size} file(s) from ${result.sourceName}."
            } else {
                "No readable files were found in the selected folder."
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

    fun resyncFolder() {
        val treeUri = folderSourceManager.selectedTreeUri()
        if (treeUri != null && folderSourceManager.hasPersistedReadAccess(treeUri)) {
            scanFolder(treeUri)
        } else {
            status = "Select the OneDrive or storage folder again to restore access and resync."
            folderPicker.launch(treeUri)
        }
    }

    fun importArchive(uri: Uri, refreshing: Boolean, accessPersisted: Boolean) {
        if (importing || folderScanning || archiveLocationScanning) return
        selectMode(ReaderContentMode.ARCHIVE)
        val previousArchive = archiveImport
        importing = true
        progress = null
        status = if (refreshing) {
            "Refreshing the selected recovery archive from its source..."
        } else {
            "Validating archive provenance and integrity..."
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    importer.import(uri) { update ->
                        scope.launch(Dispatchers.Main.immediate) { progress = update }
                    }
                }.getOrElse { throwable ->
                    ArchiveImportResult(
                        sourceId = previousArchive?.sourceId.orEmpty(),
                        sourceName = previousArchive?.sourceName ?: "Recovery archive",
                        entries = emptyList(),
                        issues = emptyList(),
                        error = throwable.message ?: throwable.javaClass.simpleName,
                        archiveUri = uri,
                    )
                }
            }
            importing = false
            if (result.entries.isNotEmpty()) {
                archiveImport = result
                status = if (refreshing) {
                    "Refreshed and verified ${result.verifiedItemCount} archive item(s)."
                } else {
                    "Verified ${result.verifiedItemCount} backup item(s)."
                }
                if (!accessPersisted) {
                    status += " Android granted temporary access; select this archive again for the next refresh."
                }
                showReader = true
            } else {
                status = "Archive refresh failed; the last verified content was kept. " +
                    (result.error ?: "No item passed verification.")
                showReader = archiveImport?.entries?.isNotEmpty() == true
            }
        }
    }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val permissionError = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.exceptionOrNull()
        val accessPersisted = permissionError == null && importer.hasPersistedReadAccess(uri)
        importArchive(uri, refreshing = archiveImport != null, accessPersisted = accessPersisted)
    }

    fun scanArchiveLocation(treeUri: Uri) {
        if (archiveLocationScanning || folderScanning || importing) return
        selectMode(ReaderContentMode.ARCHIVE)
        archiveLocationManager.rememberTreeUri(treeUri)
        archiveLocationScanning = true
        archiveLocationProgress = null
        status = "Scanning the selected archive location and its subfolders..."
        showReader = false
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                archiveLocationManager.findRecoveryArchives(treeUri) { update ->
                    scope.launch(Dispatchers.Main.immediate) { archiveLocationProgress = update }
                }
            }
            archiveLocationScanning = false
            archiveLocationName = result.locationName
            archiveCandidates = result.candidates
            status = result.error ?: "Found ${result.candidates.size} recovery archive(s) in ${result.locationName}."
        }
    }

    val archiveLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val permissionError = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.exceptionOrNull()
        if (permissionError != null || !archiveLocationManager.hasPersistedReadAccess(uri)) {
            status = "Android could not retain access to this archive location. " +
                "Choose it again and approve folder access. ${permissionError?.message.orEmpty()}"
            return@rememberLauncherForActivityResult
        }
        scanArchiveLocation(uri)
    }

    fun refreshArchiveLocation() {
        val treeUri = archiveLocationManager.selectedTreeUri()
        if (treeUri != null && archiveLocationManager.hasPersistedReadAccess(treeUri)) {
            scanArchiveLocation(treeUri)
        } else {
            status = "Choose the OneDrive or storage archive location again to refresh its contents."
            archiveLocationPicker.launch(treeUri)
        }
    }

    fun refreshArchive() {
        val archiveUri = importer.selectedArchiveUri()
        val archiveLocationUri = archiveLocationManager.selectedTreeUri()
        val locationAccess = archiveLocationUri != null &&
            archiveLocationManager.hasPersistedReadAccess(archiveLocationUri)
        if (archiveUri != null && (importer.hasPersistedReadAccess(archiveUri) || locationAccess)) {
            importArchive(archiveUri, refreshing = true, accessPersisted = true)
        } else {
            status = "Select the recovery archive again to restore access and refresh it."
            archivePicker.launch(ARCHIVE_MIME_TYPES)
        }
    }

    LaunchedEffect(Unit) {
        if (initialMode == ReaderContentMode.FOLDER) {
            folderSourceManager.selectedTreeUri()?.let { treeUri ->
                if (folderSourceManager.hasPersistedReadAccess(treeUri)) {
                    scanFolder(treeUri)
                } else {
                    showReader = false
                    status = "Folder access expired. Reconnect the OneDrive or storage folder to resync its content."
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
                    title = { Text("RecoverByBackup") },
                    navigationIcon = {
                        Image(
                            painter = painterResource(R.drawable.ic_reader_logo),
                            contentDescription = "RecoverByBackup data reader",
                            modifier = Modifier.padding(start = 12.dp).size(40.dp),
                        )
                    },
                    actions = {
                        if (sourceMode == ReaderContentMode.ARCHIVE && archiveImport != null) {
                            TextButton(
                                onClick = ::refreshArchive,
                                enabled = !importing && !folderScanning && !archiveLocationScanning,
                            ) {
                                Text(if (importing) "Refreshing..." else "Refresh archive")
                            }
                        }
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
                        contentRefreshing = folderScanning || importing,
                        onChooseContentSource = if (sourceMode == ReaderContentMode.FOLDER) {
                            { folderPicker.launch(folderSourceManager.selectedTreeUri()) }
                        } else {
                            null
                        },
                        onRefreshContent = if (sourceMode == ReaderContentMode.FOLDER) ::resyncFolder else null,
                    )
                }
                return@Scaffold
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("RecoverByBackup", style = MaterialTheme.typography.headlineMedium)
                Text("Choose a backup location to find RecoverByBackup and legacy Phone Sync archives, or browse an owner-approved storage folder directly.")
                ReaderFolderActions(
                    busy = folderScanning || archiveLocationScanning || importing,
                    resyncing = folderScanning,
                    onConnect = { folderPicker.launch(folderSourceManager.selectedTreeUri()) },
                    onResync = ::resyncFolder,
                )
                ReaderArchiveActions(
                    busy = folderScanning || archiveLocationScanning || importing,
                    refreshing = archiveLocationScanning,
                    onChooseLocation = { archiveLocationPicker.launch(archiveLocationManager.selectedTreeUri()) },
                    onChooseZip = { archivePicker.launch(ARCHIVE_MIME_TYPES) },
                    onRefreshLocation = ::refreshArchiveLocation,
                )
                if (archiveLocationScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    archiveLocationProgress?.let { current ->
                        Text(
                            "Scanning archive location · ${current.discoveredFiles} ZIP(s) found · ${current.currentItem}",
                        )
                    }
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
                archiveLocationName?.let { locationName ->
                    Text("Recovery archives in $locationName", style = MaterialTheme.typography.titleLarge)
                }
                archiveCandidates.forEach { candidate ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(candidate.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(candidate.relativePath, style = MaterialTheme.typography.bodySmall)
                                Text(formatBytes(candidate.sizeBytes), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = {
                                    val locationAccess = archiveLocationManager.selectedTreeUri()?.let { treeUri ->
                                        archiveLocationManager.hasPersistedReadAccess(treeUri)
                                    } == true
                                    importArchive(
                                        candidate.uri,
                                        refreshing = archiveImport != null,
                                        accessPersisted = locationAccess,
                                    )
                                },
                                enabled = !importing && !folderScanning && !archiveLocationScanning,
                            ) {
                                Text("Open")
                            }
                        }
                    }
                }
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

@Composable
internal fun ReaderFolderActions(
    busy: Boolean,
    resyncing: Boolean,
    onConnect: () -> Unit,
    onResync: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onConnect,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) {
            Text("Connect folder")
        }
        OutlinedButton(
            onClick = onResync,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (resyncing) "Resyncing..." else "Resync folder")
        }
    }
}

@Composable
internal fun ReaderArchiveActions(
    busy: Boolean,
    refreshing: Boolean,
    onChooseLocation: () -> Unit,
    onChooseZip: () -> Unit,
    onRefreshLocation: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onChooseLocation,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) {
            Text("Archive location")
        }
        OutlinedButton(
            onClick = onChooseZip,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) {
            Text("ZIP file")
        }
        OutlinedButton(
            onClick = onRefreshLocation,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (refreshing) "Scanning..." else "Refresh location")
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
    sourceLabel = "Verified backup archive · $verifiedItemCount item(s)",
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
private const val ARCHIVE_LOCATION_STATE_FILE = "reader-archive-location.json"
private const val ARCHIVE_LOCATION_PREFERENCES = "reader_archive_location"
private val ARCHIVE_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/x-zip",
    "application/octet-stream",
)