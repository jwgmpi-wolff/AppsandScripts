package com.jerrywolff.phonesynctabletreader

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
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexDatabase
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexer
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
    val database = remember { ArtifactIndexDatabase(context, "tablet_reader.sqlite") }
    val indexer = remember { ArtifactIndexer(context, database) }
    var imported by remember { mutableStateOf(importer.load()) }
    var showReader by remember { mutableStateOf(imported?.entries?.isNotEmpty() == true) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ArchiveImportProgress?>(null) }
    var status by remember { mutableStateOf("Open a Phone Sync backup archive.") }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
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
            imported = result
            if (result.entries.isNotEmpty()) {
                status = "Verified ${result.entries.size} external-source item(s)."
                showReader = true
            } else {
                status = "Import failed: ${result.error ?: "no item passed verification"}"
            }
        }
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
            val currentImport = imported
            if (showReader && currentImport != null && currentImport.entries.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (currentImport.issues.isNotEmpty()) {
                        Text(
                            "${currentImport.issues.size} archive item(s) need remediation; verified items remain available.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ArtifactDataReaderView(
                        entries = currentImport.entries,
                        initialSourceId = currentImport.sourceId,
                        initialSourceName = currentImport.sourceName,
                        database = database,
                        indexer = indexer,
                        onBack = { showReader = false },
                    )
                }
                return@Scaffold
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Tablet recovery archive reader", style = MaterialTheme.typography.headlineMedium)
                Text("Open a verified Phone Sync backup archive to build this app's local browsing database.")
                Button(
                    onClick = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (importing) "Importing archive..." else "Open recovery archive")
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
                currentImport?.takeIf { it.entries.isNotEmpty() }?.let { result ->
                    OutlinedButton(onClick = { showReader = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open verified reader database")
                    }
                }
                currentImport?.issues?.takeIf(List<*>::isNotEmpty)?.let { issues ->
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