package com.jerrywolff.phonesyncusbc

import android.Manifest
import android.app.PendingIntent
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jerrywolff.phonesyncusbc.data.AndroidPersonalDataCollector
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.DataExportManager
import com.jerrywolff.phonesyncusbc.data.DataImportManager
import com.jerrywolff.phonesyncusbc.data.displayName
import com.jerrywolff.phonesyncusbc.data.storageLocation
import com.jerrywolff.phonesyncusbc.data.StoredTrust
import com.jerrywolff.phonesyncusbc.data.TargetSelectionStore
import com.jerrywolff.phonesyncusbc.data.SafGrant
import com.jerrywolff.phonesyncusbc.data.TrustLoadResult
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.SourceCapabilityPolicy
import com.jerrywolff.phonesyncusbc.domain.SourceCapabilities
import com.jerrywolff.phonesyncusbc.domain.SourcePlatform
import com.jerrywolff.phonesyncusbc.domain.TrustContext
import com.jerrywolff.phonesyncusbc.domain.TrustDecision
import com.jerrywolff.phonesyncusbc.domain.TrustPolicy
import com.jerrywolff.phonesyncusbc.usb.AttachedSource
import com.jerrywolff.phonesyncusbc.usb.PeerIdentity
import com.jerrywolff.phonesyncusbc.data.SyncStatus
import com.jerrywolff.phonesyncusbc.sync.SyncProgress
import com.jerrywolff.phonesyncusbc.sync.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class CloudBackupTarget(
    val label: String,
    val packageName: String?,
)

@androidx.compose.material3.ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    private val usbPermissionAction = "com.jerrywolff.phonesyncusbc.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneSyncApp(
                onRequestUsbPermission = { source -> requestUsbPermission(source) },
            )
        }
    }

    private fun requestUsbPermission(source: AttachedSource) {
        val manager = getSystemService(UsbManager::class.java)
        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(usbPermissionAction).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = unregisterReceiver(this)
            },
            IntentFilter(usbPermissionAction),
            RECEIVER_NOT_EXPORTED,
        )
        manager.requestPermission(source.device, intent)
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
private fun PhoneSyncApp(onRequestUsbPermission: (AttachedSource) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as PhoneSyncApplication
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(application.usbSourceResolver.attachedSources()) }
    var selectedSource by remember { mutableStateOf<AttachedSource?>(sources.firstOrNull()) }
    var identity by remember { mutableStateOf<PeerIdentity?>(null) }
    var trust by remember { mutableStateOf<StoredTrust?>(null) }
    var capabilities by remember { mutableStateOf<SourceCapabilities?>(null) }
    var selectedCategories by remember { mutableStateOf(setOf(ConsentCategory.PHOTOS_AND_VIDEOS)) }
    var grants by remember { mutableStateOf(emptyList<SafGrant>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var liveProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var showLibrary by remember { mutableStateOf(false) }
    var libraryEntries by remember { mutableStateOf(emptyList<AuditEntry>()) }
    var previewEntry by remember { mutableStateOf<AuditEntry?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewVideoUri by remember { mutableStateOf<Uri?>(null) }
    var importCategory by remember { mutableStateOf<ConsentCategory?>(null) }
    var showBackupSelection by remember { mutableStateOf(false) }
    val initialBackupEntries = remember { application.auditLog.allCompletedTransfers() }
    var backupEntries by remember { mutableStateOf(initialBackupEntries) }
    var selectedBackupIds by remember {
        mutableStateOf<Set<Long>>(initialBackupEntries.mapTo(linkedSetOf()) { it.id })
    }
    var showTargetWizard by remember { mutableStateOf(false) }
    var targetUri by remember { mutableStateOf<Uri?>(null) }
    var targetName by remember { mutableStateOf<String?>(null) }
    var cloudTarget by remember { mutableStateOf<CloudBackupTarget?>(null) }
    var pendingTargetName by remember { mutableStateOf<String?>(null) }
    var awaitingCloudCompletion by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("Select data and a destination to begin.") }
    val existingPersonalExports = remember {
        application.auditLog.completedTransfers(LOCAL_ANDROID_PEER_ID)
    }
    var pendingPersonalCategories by remember { mutableStateOf(emptySet<ConsentCategory>()) }
    var collectNotificationsAfterPermissions by remember { mutableStateOf(false) }
    var personalDataStatus by remember {
        mutableStateOf(
            if (existingPersonalExports.isEmpty()) {
                "Android personal data has not been collected yet."
            } else {
                "Available in backup set: ${existingPersonalExports.map { it.category }.distinct().size} categories, " +
                    "${existingPersonalExports.size} export files (${formatBytes(existingPersonalExports.sumOf { it.bytesTransferred })})."
            },
        )
    }
    var collectingPersonalData by remember { mutableStateOf(false) }
    var notificationAccessEnabled by remember { mutableStateOf(isNotificationAccessEnabled(context)) }
    val targetSelectionStore = remember { TargetSelectionStore(context) }
    val personalDataCollector = remember {
        AndroidPersonalDataCollector(context, application.auditLog)
    }
    val savedTarget = remember { targetSelectionStore.load() }
    var targetRestored by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val smartSwitchAvailable = remember {
        context.packageManager.getLaunchIntentForPackage(SMART_SWITCH_PACKAGE) != null
    }

    LaunchedEffect(savedTarget, targetRestored) {
        if (!targetRestored) {
            targetUri = savedTarget?.uri
            targetName = savedTarget?.name
            if (savedTarget != null) {
                backupStatus = "${selectedBackupIds.size} items ready for ${savedTarget.name}."
            }
            targetRestored = true
        }
    }

    fun clearPreview() {
        previewEntry = null
        previewText = null
        previewBitmap = null
        previewVideoUri = null
    }

    val runPersonalCollection: (Set<ConsentCategory>) -> Unit = { categories ->
        if (categories.isNotEmpty() && !collectingPersonalData) {
            collectingPersonalData = true
            personalDataStatus = "Collecting ${categories.joinToString { it.label() }}..."
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    personalDataCollector.collect(categories) { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            personalDataStatus = progress
                        }
                    }
                }
                backupEntries = application.auditLog.allCompletedTransfers()
                selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                personalDataStatus = when {
                    result.failedCategories == 0 ->
                        "Collected ${result.records} records in ${result.exportedCategories} export files (${formatBytes(result.bytes)})."
                    result.exportedCategories > 0 ->
                        "Collected ${result.records} records; ${result.failedCategories} categories failed. ${result.firstError.orEmpty()}"
                    else -> "Collection failed: ${result.firstError ?: "No provider data was available."}"
                }
                message = personalDataStatus
                collectingPersonalData = false
            }
        }
    }

    val notificationAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        notificationAccessEnabled = isNotificationAccessEnabled(context)
        if (notificationAccessEnabled) {
            runPersonalCollection(setOf(ConsentCategory.NOTIFICATION_EXPORTS))
        } else {
            personalDataStatus = "Notification access was not enabled. Android requires approval on the system screen."
        }
    }

    val personalDataPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val grantedCategories = pendingPersonalCategories.filterTo(linkedSetOf()) { category ->
            AndroidPersonalDataCollector.requiredPermissions(setOf(category)).all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
        val deniedCategories = pendingPersonalCategories - grantedCategories
        if (grantedCategories.isNotEmpty()) runPersonalCollection(grantedCategories)
        if (deniedCategories.isNotEmpty()) {
            personalDataStatus = "Permission denied for: ${deniedCategories.joinToString { it.label() }}."
        }
        pendingPersonalCategories = emptySet()
        if (collectNotificationsAfterPermissions) {
            collectNotificationsAfterPermissions = false
            if (isNotificationAccessEnabled(context)) {
                notificationAccessEnabled = true
                runPersonalCollection(setOf(ConsentCategory.NOTIFICATION_EXPORTS))
            } else {
                notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    fun requestPersonalDataCollection(categories: Set<ConsentCategory>) {
        val wantsNotifications = ConsentCategory.NOTIFICATION_EXPORTS in categories
        val providerCategories = categories - ConsentCategory.NOTIFICATION_EXPORTS
        val permissions = AndroidPersonalDataCollector.requiredPermissions(providerCategories)
        val missingPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        val notificationAlreadyEnabled = wantsNotifications && isNotificationAccessEnabled(context)
        notificationAccessEnabled = notificationAlreadyEnabled || notificationAccessEnabled
        val categoriesReadyToCollect = providerCategories +
            if (notificationAlreadyEnabled) setOf(ConsentCategory.NOTIFICATION_EXPORTS) else emptySet()
        pendingPersonalCategories = categoriesReadyToCollect
        collectNotificationsAfterPermissions = wantsNotifications && !notificationAlreadyEnabled
        if (missingPermissions.isEmpty()) {
            if (categoriesReadyToCollect.isNotEmpty()) runPersonalCollection(categoriesReadyToCollect)
            pendingPersonalCategories = emptySet()
            if (collectNotificationsAfterPermissions) {
                collectNotificationsAfterPermissions = false
                notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        } else {
            personalDataPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    fun refreshSource() {
        sources = application.usbSourceResolver.attachedSources()
        selectedSource = sources.firstOrNull()
        identity = null
        trust = null
        capabilities = null
    }

    val source = selectedSource

    LaunchedEffect(refreshToken, selectedSource) {
        val source = selectedSource ?: return@LaunchedEffect
        if (!source.permissionGranted) return@LaunchedEffect
        val resolved = runCatching { application.usbSourceResolver.resolveIdentity(source) }.getOrNull()
            ?: return@LaunchedEffect
        identity = resolved
        val resolvedCapabilities = SourceCapabilityPolicy.forSource(source.detected)
        capabilities = resolvedCapabilities
        when (val loaded = application.trustStore.load(resolved.peerId, resolved.profileId)) {
            is TrustLoadResult.Found -> {
                val decision = TrustPolicy.evaluate(
                    loaded.trust.record,
                    TrustContext(resolved.peerId, loaded.trust.record.localDeviceId, application.keyManager.currentProof()),
                )
                trust = if (decision is TrustDecision.Approved) loaded.trust else null
                if (trust != null) {
                    selectedCategories = resolvedCapabilities.supportedCategories
                    if (trust!!.record.authorizedCategories != selectedCategories) {
                        trust = application.trustStore.updateCategories(trust!!, selectedCategories)
                    }
                    grants = application.safGrantStore.list(resolved.peerId)
                }
            }
            else -> {
                trust = null
                selectedCategories = resolvedCapabilities.supportedCategories
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val currentIdentity = identity ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val grantCategory = treeGrantCategory(uri)
            val grant = application.safGrantStore.add(
                currentIdentity.peerId,
                grantCategory,
                uri,
                if (grantCategory == ConsentCategory.CLOUD_ACCOUNTS) "Cloud provider" else "Selected folder",
            )
            grants = grants + grant
            selectedCategories = selectedCategories + grantCategory
            trust?.let { storedTrust ->
                trust = application.trustStore.updateCategories(storedTrust, selectedCategories)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && libraryEntries.isNotEmpty()) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DataExportManager(context).export(libraryEntries, uri)
                }
                message = "Exported ${result.exportedItems} items (${formatBytes(result.bytesExported)})." +
                    if (result.failedItems > 0) " ${result.failedItems} failed." else ""
            }
        }
    }

    val importExportsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val currentIdentity = identity
        val currentSource = source
        if (uris.isNotEmpty() && currentIdentity != null && currentSource != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DataImportManager(context).importExportedFiles(
                        peerId = currentIdentity.peerId,
                        sourceName = currentSource.detected.displayName,
                        files = uris,
                        auditLog = application.auditLog,
                        forcedCategory = importCategory,
                    )
                }
                message = "Imported ${result.importedItems} exported files (${formatBytes(result.bytesImported)})." +
                    if (result.failedItems > 0) " ${result.failedItems} failed." else ""
                libraryEntries = application.auditLog.allCompletedTransfers()
                refreshToken += 1
                importCategory = null
            }
        }
    }

    val allDataExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val providerName = pendingTargetName ?: "Selected destination"
            val folderName = DocumentFile.fromTreeUri(context, uri)?.name
            cloudTarget = null
            awaitingCloudCompletion = false
            targetUri = uri
            targetName = listOfNotNull(providerName, folderName)
                .distinctBy(String::lowercase)
                .joinToString(" / ")
            targetSelectionStore.save(uri, targetName!!)
            pendingTargetName = null
            showTargetWizard = false
            backupStatus = "${selectedBackupIds.size} items ready for ${targetName}."
            message = "Destination saved: ${targetName}."
            scope.launch { listState.animateScrollToItem(BACKUP_PANEL_INDEX) }
        } else {
            pendingTargetName = null
            message = "Folder selection canceled."
            scope.launch { listState.animateScrollToItem(BACKUP_PANEL_INDEX) }
        }
    }

    val cloudUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        backingUp = false
        awaitingCloudCompletion = true
        val label = cloudTarget?.label ?: "cloud provider"
        backupStatus = "If $label reported that the upload finished, tap Complete backup."
        message = "Returned from $label. Confirm completion in Phone Sync."
        scope.launch { listState.animateScrollToItem(BACKUP_PANEL_INDEX) }
    }

    fun launchTargetPicker(label: String) {
        pendingTargetName = label
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, "Choose backup folder")
        }
        val pickerInstruction = "Navigate to the $label folder, then tap USE THIS FOLDER at the bottom."
        message = pickerInstruction
        Toast.makeText(context, pickerInstruction, Toast.LENGTH_LONG).show()
        allDataExportLauncher.launch(picker)
    }

    fun cloudUploadIntent(packageName: String?): Intent? {
        val entries = backupEntries.filter { it.id in selectedBackupIds }
        val destinations = ArrayList(
            entries.mapNotNull { entry -> entry.destination?.let(Uri::parse) }.distinct(),
        )
        if (destinations.isEmpty()) return null
        val action = if (destinations.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        return Intent(action).apply {
            type = "*/*"
            packageName?.let(::setPackage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "Phone Sync backup", destinations.first()).apply {
                destinations.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            if (destinations.size == 1) {
                putExtra(Intent.EXTRA_STREAM, destinations.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, destinations)
            }
            putExtra(Intent.EXTRA_SUBJECT, "Phone Sync backup")
        }
    }

    fun selectCloudTarget(label: String, packageNames: List<String>) {
        if (cloudUploadIntent(null) == null) {
            message = "Select at least one available collected item first."
            showBackupSelection = true
            return
        }
        val packageName = packageNames.firstOrNull { candidate ->
            cloudUploadIntent(candidate)?.let { context.packageManager.resolveActivity(it, 0) } != null
        }
        if (packageNames.isNotEmpty() && packageName == null) {
            val storePackage = packageNames.first()
            message = "$label is not installed. Opening its Play Store page."
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$storePackage"))
            runCatching { context.startActivity(marketIntent) }
                .onFailure {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$storePackage"),
                        ),
                    )
                }
            return
        }
            targetSelectionStore.clear()
            targetUri = null
            targetName = label
            cloudTarget = CloudBackupTarget(label, packageName)
            awaitingCloudCompletion = false
            showTargetWizard = false
            backupStatus = "${selectedBackupIds.size} items ready for $label."
            message = "$label selected. Tap Start backup."
            scope.launch { listState.animateScrollToItem(BACKUP_PANEL_INDEX) }
        }

        fun startCloudBackup(target: CloudBackupTarget) {
            val upload = cloudUploadIntent(target.packageName)
            if (upload == null) {
                backingUp = false
                backupStatus = "Backup could not start because the selected items are unavailable."
                return
            }
            val launchIntent = if (target.packageName == null) {
                Intent.createChooser(upload, "Upload backup with")
            } else {
                upload
            }
            backingUp = true
            awaitingCloudCompletion = false
            backupStatus = "Opening ${target.label} to choose a folder and upload..."
            runCatching { cloudUploadLauncher.launch(launchIntent) }
                .onFailure { throwable ->
                    backingUp = false
                    backupStatus = "Could not open ${target.label}: ${throwable.message ?: throwable.javaClass.simpleName}"
                }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Sync USB-C") },
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_connection_logo),
                        contentDescription = "Connection",
                        tint = Color(0xFF1976D2),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(28.dp))
                Text("Android initiator", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bring your phone data into Android storage with one clear trust decision.",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item {
                OutlinedButton(onClick = ::refreshSource, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh connected source")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        libraryEntries = application.auditLog.allCompletedTransfers()
                        showLibrary = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View collected data")
                }
            }
            item {
                BackupPanel(
                    onSelectBackup = {
                        if (!backingUp) {
                            backupEntries = application.auditLog.allCompletedTransfers()
                            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                            showBackupSelection = true
                        }
                    },
                    onChooseTarget = {
                        backupEntries = application.auditLog.allCompletedTransfers()
                        if (selectedBackupIds.isEmpty()) {
                            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                        }
                        showTargetWizard = true
                    },
                    backingUp = backingUp,
                    selectedItemCount = selectedBackupIds.size,
                    targetName = targetName,
                    targetReady = targetUri != null || cloudTarget != null,
                    awaitingCloudCompletion = awaitingCloudCompletion,
                    backupStatus = backupStatus,
                    onExecuteBackup = {
                        val selectedEntries = backupEntries.filter { it.id in selectedBackupIds }
                        val selectedCloud = cloudTarget
                        val destination = targetUri
                        if (selectedEntries.isEmpty()) {
                            backupStatus = "Select at least one available item before starting backup."
                        } else if (selectedCloud != null) {
                            startCloudBackup(selectedCloud)
                        } else if (destination != null && !backingUp) {
                            backingUp = true
                            awaitingCloudCompletion = false
                            backupStatus = "Copying ${selectedEntries.size} items to ${targetName ?: "selected folder"}..."
                            message = "BACKUP: moving collected source data to $targetName..."
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        DataExportManager(context).export(
                                            selectedEntries,
                                            destination,
                                        )
                                    }
                                }.onSuccess { result ->
                                    backupStatus = if (result.failedItems == 0) {
                                        "Backup complete: ${result.exportedItems} items (${formatBytes(result.bytesExported)})."
                                    } else {
                                        "Backup finished: ${result.exportedItems} copied and ${result.failedItems} failed." +
                                            result.error?.let { " First error: $it" }.orEmpty()
                                    }
                                    message = backupStatus
                                }.onFailure { throwable ->
                                    backupStatus = "Backup failed: ${throwable.message ?: throwable.javaClass.simpleName}"
                                    message = backupStatus
                                }
                                backingUp = false
                            }
                        }
                    },
                    onCompleteCloudBackup = {
                        awaitingCloudCompletion = false
                        backupStatus = "Backup complete: ${selectedBackupIds.size} items confirmed in ${cloudTarget?.label ?: "cloud storage"}."
                        message = backupStatus
                    },
                )
            }
            item {
                AndroidPersonalDataPanel(
                    status = personalDataStatus,
                    collecting = collectingPersonalData,
                    notificationAccessEnabled = notificationAccessEnabled,
                    smartSwitchAvailable = smartSwitchAvailable,
                    onCollect = ::requestPersonalDataCollection,
                    onEnableNotificationAccess = {
                        notificationAccessLauncher.launch(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    },
                    onOpenIphoneTransfer = {
                        val launchIntent = context.packageManager
                            .getLaunchIntentForPackage(SMART_SWITCH_PACKAGE)
                        if (launchIntent == null) {
                            message = "Samsung Smart Switch is not installed."
                        } else {
                            message = "Complete the iPhone transfer in Smart Switch, then return and collect Android personal data."
                            context.startActivity(launchIntent)
                        }
                    },
                )
            }
            if (showBackupSelection) {
                item {
                    BackupSelectionView(
                        entries = backupEntries,
                        selectedIds = selectedBackupIds,
                        onSelectionChanged = { selectedBackupIds = it },
                        onCancel = { showBackupSelection = false },
                        onContinue = {
                            showBackupSelection = false
                            backupStatus = "${selectedBackupIds.size} items selected."
                            if (targetUri == null && cloudTarget == null) {
                                showTargetWizard = true
                            } else {
                                backupStatus = "${selectedBackupIds.size} items ready for ${targetName}."
                                scope.launch { listState.animateScrollToItem(BACKUP_PANEL_INDEX) }
                            }
                        },
                    )
                }
            }
            if (showTargetWizard) {
                item {
                    TargetMediaWizard(
                        onBack = { showTargetWizard = false },
                        onChooseFolder = { selectedTargetName ->
                            launchTargetPicker(selectedTargetName)
                        },
                        onUploadCloud = { label, packages ->
                            selectCloudTarget(label, packages)
                        },
                    )
                }
            }
            if (showLibrary) {
                item {
                    if (previewEntry != null && previewText != null) {
                        TextPreview(
                            entry = previewEntry!!,
                            text = previewText!!,
                            onBack = {
                                clearPreview()
                            },
                        )
                    } else if (previewEntry != null && previewBitmap != null) {
                        ImagePreview(
                            entry = previewEntry!!,
                            bitmap = previewBitmap!!,
                            onBack = { clearPreview() },
                        )
                    } else if (previewEntry != null && previewVideoUri != null) {
                        VideoPreview(
                            entry = previewEntry!!,
                            uri = previewVideoUri!!,
                            context = context,
                            onBack = { clearPreview() },
                        )
                    } else {
                        ImportedDataView(
                            entries = libraryEntries,
                            onBack = { showLibrary = false },
                            onOpen = { entry ->
                                val destination = entry.destination?.let(Uri::parse)
                                if (destination != null) {
                                    if (isTextLike(entry)) {
                                        scope.launch {
                                            val text = withContext(Dispatchers.IO) {
                                                readPreviewText(context, destination)
                                            }
                                            if (text != null) {
                                                previewEntry = entry
                                                previewText = text
                                            } else {
                                                message = "This collected item could not be read."
                                            }
                                        }
                                    } else if (isImageLike(entry)) {
                                        scope.launch {
                                            val bitmap = withContext(Dispatchers.IO) {
                                                readPreviewBitmap(context, destination)
                                            }
                                            if (bitmap != null) {
                                                previewEntry = entry
                                                previewBitmap = bitmap
                                            } else {
                                                message = "This collected image could not be read."
                                            }
                                        }
                                    } else if (isVideoLike(entry)) {
                                        previewEntry = entry
                                        previewVideoUri = destination
                                    } else {
                                        val intent = collectedItemIntent(
                                            destination = destination,
                                            mimeType = DataExportManager(context).mimeType(entry),
                                        )
                                        runCatching {
                                            context.startActivity(intent)
                                        }.onFailure {
                                            message = "No installed app can open this collected item type."
                                        }
                                    }
                                }
                            },
                            onExport = { exportLauncher.launch(null) },
                        )
                    }
                }
            } else if (source == null) {
                item { Text("Connect a source phone over USB or USB-C data, then refresh.") }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(source.detected.displayName, style = MaterialTheme.typography.titleLarge)
                            Text("${source.detected.family} · ${source.detected.platform}")
                            Text(if (source.permissionGranted) "USB permission granted" else "USB permission needed")
                            if (!source.permissionGranted) {
                                Button(onClick = { onRequestUsbPermission(source) }) { Text("Allow USB access") }
                            }
                        }
                    }
                }
                capabilities?.let { policy ->
                    item {
                        Text(policy.connectionMessage)
                        if (!policy.connectionSupported) Text("This connection cannot be used by Android USB host mode.")
                    }
                }
                if (trust == null && identity != null && capabilities?.connectionSupported == true) {
                    item {
                        TrustApproval(
                            capabilities = capabilities!!,
                            selected = selectedCategories,
                            onSelectedChange = { selectedCategories = it },
                            onApprove = {
                                val currentIdentity = identity!!
                                val now = System.currentTimeMillis()
                                val created = StoredTrust(
                                    record = com.jerrywolff.phonesyncusbc.domain.TrustRecord(
                                        peerDeviceId = currentIdentity.peerId,
                                        localDeviceId = DeviceIdentity.localId(context),
                                        encryptionKeyProof = application.keyManager.currentProof(),
                                        authorizedCategories = selectedCategories,
                                    ),
                                    profileId = currentIdentity.profileId,
                                    sourceName = source.detected.displayName,
                                    platform = source.detected.platform,
                                    family = source.detected.family,
                                    createdAtEpochMillis = now,
                                    updatedAtEpochMillis = now,
                                )
                                application.trustStore.save(created)
                                trust = created
                                message = "Device trusted. Future syncs use only these categories."
                            },
                        )
                    }
                } else if (trust != null && identity != null) {
                    item {
                        TrustedDashboard(
                                trust = trust!!,
                                capabilities = capabilities!!,
                                categories = selectedCategories,
                                grants = grants,
                                syncing = syncing,
                                liveProgress = liveProgress,
                                auditLog = application.auditLog,
                                onCategoriesChange = { categories ->
                                    selectedCategories = categories
                                    trust = application.trustStore.updateCategories(trust!!, categories)
                                },
                                onSelectFolder = { folderLauncher.launch(null) },
                                onImportExports = { category ->
                                    importCategory = category
                                    importExportsLauncher.launch(
                                        arrayOf(
                                            "text/*",
                                            "message/*",
                                            "application/json",
                                            "application/xml",
                                            "application/zip",
                                            "application/octet-stream",
                                        ),
                                    )
                                },
                                onViewLibrary = {
                                    libraryEntries = application.auditLog.completedTransfers(trust!!.record.peerDeviceId)
                                    showLibrary = true
                                },
                                onSync = sync@{
                                    val currentSource = source ?: return@sync
                                    val currentIdentity = identity ?: return@sync
                                    syncing = true
                                    liveProgress = SyncProgress(null, 0, 0, 0, 0)
                                    message = "LIVE: 0 transferred, 0 already audited, 0 failed."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            val publishProgress: (SyncProgress) -> Unit = { progress ->
                                                scope.launch(Dispatchers.Main.immediate) {
                                                    liveProgress = progress
                                                    message = buildLiveStatus(progress)
                                                }
                                            }
                                            val mtpResult = application.mtpSyncEngine.sync(
                                                source = currentSource,
                                                identity = currentIdentity,
                                                authorizedCategories = selectedCategories,
                                                onProgress = publishProgress,
                                            )
                                            val safResult = application.safSyncEngine.sync(
                                                peerId = currentIdentity.peerId,
                                                grants = grants,
                                                authorizedCategories = selectedCategories,
                                                onProgress = publishProgress,
                                            )
                                            combineSyncResults(mtpResult, safResult)
                                        }
                                        syncing = false
                                        liveProgress = SyncProgress(
                                            currentItem = null,
                                            transferredItems = result.transferredItems,
                                            skippedItems = result.skippedItems,
                                            failedItems = result.failedItems,
                                            bytesTransferred = result.bytesTransferred,
                                        )
                                        message = "${result.status}: ${result.transferredItems} transferred, ${result.skippedItems} already audited."
                                        refreshToken += 1
                                    }
                                },
                                onRevoke = {
                                    application.safGrantStore.clear(trust!!.record.peerDeviceId)
                                    trust = application.trustStore.revoke(trust!!)
                                    grants = emptyList()
                                    message = "Trust revoked. Re-approval is required before syncing."
                                },
                        )
                    }
                }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        }
    }

}

@androidx.compose.runtime.Composable
private fun TrustApproval(
    capabilities: SourceCapabilities,
    selected: Set<ConsentCategory>,
    onSelectedChange: (Set<ConsentCategory>) -> Unit,
    onApprove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trust This Device", style = MaterialTheme.typography.headlineSmall)
            Text("Choose the categories this source may sync automatically in future sessions.")
            CategoryChoices(capabilities, selected, onSelectedChange)
            Button(
                onClick = onApprove,
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Trust This Device") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CategoryChoices(
    capabilities: SourceCapabilities,
    selected: Set<ConsentCategory>,
    onSelectedChange: (Set<ConsentCategory>) -> Unit,
) {
    capabilities.categories.forEach { capability ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = capability.category in selected,
                onCheckedChange = { checked ->
                    onSelectedChange(
                        if (checked) selected + capability.category else selected - capability.category,
                    )
                },
            )
            Column {
                Text(capability.category.label())
                Text(capability.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TrustedDashboard(
    trust: StoredTrust,
    capabilities: SourceCapabilities,
    categories: Set<ConsentCategory>,
    grants: List<SafGrant>,
    syncing: Boolean,
    liveProgress: SyncProgress?,
    auditLog: com.jerrywolff.phonesyncusbc.data.AuditLog,
    onCategoriesChange: (Set<ConsentCategory>) -> Unit,
    onSelectFolder: () -> Unit,
    onImportExports: (ConsentCategory) -> Unit,
    onViewLibrary: () -> Unit,
    onSync: () -> Unit,
    onRevoke: () -> Unit,
) {
    val latest = remember(trust.updatedAtEpochMillis) { auditLog.latestSession(trust.record.peerDeviceId) }
    val audit = remember(trust.updatedAtEpochMillis) { auditLog.recentTransfers(trust.record.peerDeviceId, 10) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trusted source", style = MaterialTheme.typography.headlineSmall)
            Text("Authorized: ${categories.joinToString { it.label() }}")
            Text("Last sync: ${latest?.completedAtEpochMillis?.let(::formatTime) ?: "Never"}")
            liveProgress?.let { progress ->
                Text(
                    if (syncing) {
                        "Live transfer: ${progress.transferredItems} transferred, " +
                            "${progress.skippedItems} skipped, ${progress.failedItems} failed"
                    } else {
                        "Last transfer: ${progress.transferredItems} transferred, " +
                            "${progress.skippedItems} skipped, ${progress.failedItems} failed"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                progress.currentItem?.let { currentItem ->
                    Text("Pulling: ${currentItem.substringAfterLast('/')}" )
                }
                if (progress.currentItemTotal > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (progress.currentItemBytes.toFloat() / progress.currentItemTotal)
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Current file: ${formatBytes(progress.currentItemBytes)} / " +
                            formatBytes(progress.currentItemTotal),
                    )
                }
                Text("Transferred: ${formatBytes(progress.bytesTransferred)}")
            }
            CategoryChoices(capabilities, categories, onCategoriesChange)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSync, enabled = !syncing) {
                    Text(if (syncing) "Syncing..." else "Sync connected files")
                }
                OutlinedButton(onClick = onSelectFolder) { Text("Add folder/cloud") }
            }
            HorizontalDivider()
            Text("Import exports from the connected source", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { onImportExports(ConsentCategory.SMS_EXPORTS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import source SMS export file") }
            OutlinedButton(
                onClick = { onImportExports(ConsentCategory.CHAT_EXPORTS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import source chat export file") }
            OutlinedButton(
                onClick = { onImportExports(ConsentCategory.EMAIL_EXPORTS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import source email export file") }
            OutlinedButton(
                onClick = { onImportExports(ConsentCategory.NOTIFICATION_EXPORTS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import source notification export file") }
            OutlinedButton(onClick = onViewLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("View imported data")
            }
            if (grants.isNotEmpty()) Text("Authorized locations: ${grants.size}")
            Text(
                "The connected iPhone exposes media over PTP, not its private SMS, call, chat, mail, or notification databases. " +
                    "Use the source app's supported export for those records. No cloud client ID is required.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Recent transfers", style = MaterialTheme.typography.titleMedium)
            if (audit.isEmpty()) Text("No transferred items yet.")
            audit.forEach { entry -> Text("${entry.category.label()}: ${entry.sourceItem}") }
            OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) { Text("Revoke trust") }
        }
    }
}

private fun ConsentCategory.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun AuditEntry.displayName(): String {
    return sourceItem.substringAfterLast('/').ifBlank { "Collected item" }
}

private fun AuditEntry.storageLocation(): String {
    return destination ?: "Unavailable"
}

private fun isTextLike(entry: AuditEntry): Boolean {
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf(
        "txt", "text", "csv", "json", "xml", "html", "htm", "md", "log",
        "eml", "mbox", "vcf", "vcard", "ics", "ical", "yaml", "yml",
        "srt", "sub", "xml", "db-wal", "sql",
    ) || entry.category in setOf(
        ConsentCategory.SMS_EXPORTS,
        ConsentCategory.CHAT_EXPORTS,
        ConsentCategory.EMAIL_EXPORTS,
        ConsentCategory.NOTIFICATION_EXPORTS,
    )
}

private fun readPreviewText(context: Context, uri: Uri): String? {
    return runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val buffer = ByteArray(PREVIEW_MAX_BYTES)
            val count = stream.read(buffer)
            if (count <= 0) "(Empty collected item)"
            else String(buffer, 0, count, Charsets.UTF_8)
        }
    }.getOrNull()
}

private fun collectedItemIntent(destination: Uri, mimeType: String): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        data = destination
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, destination)
        clipData = ClipData.newRawUri("Collected item", destination)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

private fun formatTime(epochMillis: Long): String = DateFormat.getDateTimeInstance().format(Date(epochMillis))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_024 * 1_024 * 1_024 -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
    else -> "%.1f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
}

private fun buildLiveStatus(progress: SyncProgress): String {
    val current = if (progress.currentItemTotal > 0) {
        " Pulling ${progress.currentItemBytes}/${progress.currentItemTotal} bytes."
    } else {
        ""
    }
    return "LIVE: ${progress.transferredItems} transferred, " +
        "${progress.skippedItems} already audited, ${progress.failedItems} failed.$current"
}

@androidx.compose.runtime.Composable
private fun ImportedDataView(
    entries: List<AuditEntry>,
    onBack: () -> Unit,
    onOpen: (AuditEntry) -> Unit,
    onExport: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<ConsentCategory?>(null) }
    val categories = entries.map { it.category }.distinct()
    val visibleEntries = selectedCategory?.let { category ->
        entries.filter { it.category == category }
    } ?: entries
    val groupedEntries = visibleEntries.groupBy { it.category }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Imported data", style = MaterialTheme.typography.headlineSmall)
            Text("${entries.size} completed imports available in Android storage.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(onClick = onExport, enabled = entries.isNotEmpty()) { Text("Export all") }
            }
            Text("Collected source folders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Items open from their recorded Android storage location. Choose a type to browse its collected folder.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterButton(
                    label = "All",
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                )
                categories.forEach { category ->
                    FilterButton(
                        label = category.label(),
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
            }
            HorizontalDivider()
            if (visibleEntries.isEmpty()) {
                Text("No completed imports yet.")
            } else {
                groupedEntries.forEach { (category, categoryEntries) ->
                    Text(category.label(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        categoryDescription(category),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    categoryEntries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.displayName(), style = MaterialTheme.typography.titleMedium)
                            Text(entry.sourceItem, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${formatBytes(entry.bytesTransferred)} · ${formatTime(entry.transferredAtEpochMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { onOpen(entry) }) { Text("Open collected item") }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@androidx.compose.runtime.Composable
private fun AndroidPersonalDataPanel(
    status: String,
    collecting: Boolean,
    notificationAccessEnabled: Boolean,
    smartSwitchAvailable: Boolean,
    onCollect: (Set<ConsentCategory>) -> Unit,
    onEnableNotificationAccess: () -> Unit,
    onOpenIphoneTransfer: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Personal data backup", style = MaterialTheme.typography.titleLarge)
            Text(status, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onCollect(AndroidPersonalDataCollector.SUPPORTED_CATEGORIES) },
                enabled = !collecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (collecting) "Collecting personal data..." else "Collect all Android personal data") }
            OutlinedButton(
                onClick = { onCollect(setOf(ConsentCategory.SMS_EXPORTS)) },
                enabled = !collecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Collect SMS and MMS") }
            OutlinedButton(
                onClick = { onCollect(setOf(ConsentCategory.CALL_LOGS)) },
                enabled = !collecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Collect call history") }
            OutlinedButton(
                onClick = { onCollect(setOf(ConsentCategory.CONTACTS, ConsentCategory.CALENDAR)) },
                enabled = !collecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Collect contacts and calendar") }
            OutlinedButton(
                onClick = {
                    if (notificationAccessEnabled) {
                        onCollect(setOf(ConsentCategory.NOTIFICATION_EXPORTS))
                    } else {
                        onEnableNotificationAccess()
                    }
                },
                enabled = !collecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (notificationAccessEnabled) "Export captured notifications" else "Enable notification backup")
            }
            HorizontalDivider()
            Text("Transfer from iPhone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Smart Switch can migrate supported iPhone SMS, call history, contacts, and calendar records to this Samsung without a client ID.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onOpenIphoneTransfer,
                enabled = smartSwitchAvailable && !collecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Smart Switch for iPhone transfer") }
            Text(
                "After migration, collect all Android personal data to add those records to the backup set. Android requires owner approval for system permissions.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun BackupPanel(
    onSelectBackup: () -> Unit,
    onChooseTarget: () -> Unit,
    backingUp: Boolean,
    selectedItemCount: Int,
    targetName: String?,
    targetReady: Boolean,
    awaitingCloudCompletion: Boolean,
    backupStatus: String,
    onExecuteBackup: () -> Unit,
    onCompleteCloudBackup: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Collected source data -> target media", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose a local folder or send the selected data to a cloud app.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("$selectedItemCount items selected")
            if (targetReady) {
                Text("Destination: ${targetName ?: "selected folder"}")
                Text(backupStatus, style = MaterialTheme.typography.bodySmall)
                if (awaitingCloudCompletion) {
                    Button(
                        onClick = onCompleteCloudBackup,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Complete backup") }
                    OutlinedButton(
                        onClick = onExecuteBackup,
                        enabled = !backingUp,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Retry cloud upload") }
                } else {
                    Button(
                        onClick = onExecuteBackup,
                        enabled = !backingUp && selectedItemCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (backingUp) "Backing up..." else "Start backup")
                    }
                }
                OutlinedButton(
                    onClick = onChooseTarget,
                    enabled = !backingUp,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Change destination") }
            } else {
                Text(backupStatus, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = onChooseTarget,
                    enabled = !backingUp,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose backup destination") }
            }
            OutlinedButton(
                onClick = onSelectBackup,
                enabled = !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Select data to back up") }
            Text(
                "Target options: device folder, SD card, USB drive, OneDrive, Google Drive, Dropbox, or another upload app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun TargetMediaWizard(
    onBack: () -> Unit,
    onChooseFolder: (String) -> Unit,
    onUploadCloud: (String, List<String>) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select backup target media", style = MaterialTheme.typography.headlineSmall)
            Text("Choose a destination here, then return to the backup card and tap Start backup.")
            Button(
                onClick = { onChooseFolder("device, SD card, or USB drive") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Device / SD card / USB folder") }
            OutlinedButton(
                onClick = { onUploadCloud("OneDrive", listOf("com.microsoft.skydrive")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use OneDrive") }
            OutlinedButton(
                onClick = { onUploadCloud("Google Drive", listOf("com.google.android.apps.docs")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use Google Drive") }
            OutlinedButton(
                onClick = { onUploadCloud("Dropbox", listOf("com.dropbox.android")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use Dropbox") }
            OutlinedButton(
                onClick = { onUploadCloud("Cloud app", emptyList()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Other cloud app") }
            Text(
                "The cloud app must be installed and signed in. It controls the final folder choice and upload confirmation.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

private const val BACKUP_PANEL_INDEX = 3
private const val SMART_SWITCH_PACKAGE = "com.sec.android.easyMover"
private const val LOCAL_ANDROID_PEER_ID = "local-android"

@androidx.compose.runtime.Composable
private fun BackupSelectionView(
    entries: List<AuditEntry>,
    selectedIds: Set<Long>,
    onSelectionChanged: (Set<Long>) -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select collected backup", style = MaterialTheme.typography.headlineSmall)
            Text("Choose the collected source data to push to target media.")
            Text("${selectedIds.size} of ${entries.size} items selected")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelectionChanged(entries.mapTo(linkedSetOf()) { it.id }) }) {
                    Text("Select all")
                }
                OutlinedButton(onClick = { onSelectionChanged(emptySet()) }) {
                    Text("Clear")
                }
            }
            if (entries.isEmpty()) {
                Text("No collected data is available to back up.")
            } else {
                entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = entry.id in selectedIds,
                            onCheckedChange = { checked ->
                                onSelectionChanged(
                                    if (checked) selectedIds + entry.id else selectedIds - entry.id,
                                )
                            },
                        )
                        Column {
                            Text(entry.displayName())
                            Text(
                                "${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onContinue,
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue to target media") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

private fun categoryDescription(category: ConsentCategory): String = when (category) {
    ConsentCategory.PHOTOS_AND_VIDEOS -> "MediaStore Pictures and Movies collected from the source."
    ConsentCategory.DOCUMENTS -> "Documents collected into Android Downloads."
    ConsentCategory.CONTACTS -> "User-exported vCard contacts collected into Android Downloads."
    ConsentCategory.CALL_LOGS -> "Call history exported from this Android device."
    ConsentCategory.CALENDAR -> "Calendar events exported from this Android device."
    ConsentCategory.SELECTED_FOLDERS -> "Files collected from the selected source folder."
    ConsentCategory.CLOUD_ACCOUNTS -> "Files collected from the authorized cloud provider."
    ConsentCategory.SMS_EXPORTS -> "User-created SMS export files, not private SMS databases."
    ConsentCategory.CHAT_EXPORTS -> "User-created chat export files, not private chat databases."
    ConsentCategory.EMAIL_EXPORTS -> "User-created email export files, not private mail databases."
    ConsentCategory.NOTIFICATION_EXPORTS -> "Active and future Android notifications captured after special-access approval."
}

private fun combineSyncResults(first: SyncResult, second: SyncResult): SyncResult {
    val transferred = first.transferredItems + second.transferredItems
    val skipped = first.skippedItems + second.skippedItems
    val failed = first.failedItems + second.failedItems
    val bytes = first.bytesTransferred + second.bytesTransferred
    val status = when {
        failed == 0 -> SyncStatus.COMPLETED
        transferred > 0 -> SyncStatus.PARTIAL
        else -> SyncStatus.FAILED
    }
    return SyncResult(
        status = status,
        transferredItems = transferred,
        skippedItems = skipped,
        failedItems = failed,
        bytesTransferred = bytes,
        error = first.error ?: second.error,
    )
}

private fun treeGrantCategory(uri: Uri): ConsentCategory {
    return if (uri.authority == "com.android.externalstorage.documents" ||
        uri.authority == "com.android.providers.downloads.documents"
    ) {
        ConsentCategory.SELECTED_FOLDERS
    } else {
        ConsentCategory.CLOUD_ACCOUNTS
    }
}

private fun isNotificationAccessEnabled(context: Context): Boolean {
    return context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}

@androidx.compose.runtime.Composable
private fun TextPreview(
    entry: AuditEntry,
    text: String,
    onBack: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.displayName(), style = MaterialTheme.typography.headlineSmall)
            Text("${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}")
            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back to collected data") }
            }
            HorizontalDivider()
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun ImagePreview(
    entry: AuditEntry,
    bitmap: Bitmap,
    onBack: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.displayName(), style = MaterialTheme.typography.headlineSmall)
            Text("${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}")
            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBack) { Text("Back to collected data") }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.displayName(),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun VideoPreview(
    entry: AuditEntry,
    uri: Uri,
    context: Context,
    onBack: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.displayName(), style = MaterialTheme.typography.headlineSmall)
            Text("${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}")
            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBack) { Text("Back to collected data") }
            AndroidView(
                factory = {
                    VideoView(context).apply {
                        setMediaController(MediaController(context))
                        setVideoURI(uri)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        }
    }
}

private fun readPreviewBitmap(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) null else BitmapFactory.decodeStream(input)
        }
    }.getOrNull()
}

private fun isImageLike(entry: AuditEntry): Boolean {
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
}

private fun isVideoLike(entry: AuditEntry): Boolean {
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("3g2", "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")
}

private const val PREVIEW_MAX_BYTES = 128 * 1024