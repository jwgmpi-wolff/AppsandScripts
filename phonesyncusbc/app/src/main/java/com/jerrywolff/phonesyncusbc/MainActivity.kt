package com.jerrywolff.phonesyncusbc

import android.app.PendingIntent
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.jerrywolff.phonesyncusbc.data.BackupTargetType
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.DataExportManager
import com.jerrywolff.phonesyncusbc.data.displayName
import com.jerrywolff.phonesyncusbc.data.mergeSourceBackupSelection
import com.jerrywolff.phonesyncusbc.data.storageLocation
import com.jerrywolff.phonesyncusbc.data.StoredTrust
import com.jerrywolff.phonesyncusbc.data.TargetSelectionStore
import com.jerrywolff.phonesyncusbc.data.primaryActionLabel
import com.jerrywolff.phonesyncusbc.data.providerTarget
import com.jerrywolff.phonesyncusbc.data.TrustLoadResult
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.SourceCapabilityPolicy
import com.jerrywolff.phonesyncusbc.domain.SourceCapabilities
import com.jerrywolff.phonesyncusbc.domain.SourceExportRequirements
import com.jerrywolff.phonesyncusbc.domain.SourcePlatform
import com.jerrywolff.phonesyncusbc.domain.RecoveryDeviceType
import com.jerrywolff.phonesyncusbc.domain.RecoveryProfiles
import com.jerrywolff.phonesyncusbc.domain.LOGICAL_ACQUISITION_LIMIT
import com.jerrywolff.phonesyncusbc.domain.TrustContext
import com.jerrywolff.phonesyncusbc.domain.TrustDecision
import com.jerrywolff.phonesyncusbc.domain.TrustPolicy
import com.jerrywolff.phonesyncusbc.usb.AttachedSource
import com.jerrywolff.phonesyncusbc.usb.IdentityReadProgress
import com.jerrywolff.phonesyncusbc.usb.IdentityReadStage
import com.jerrywolff.phonesyncusbc.usb.PeerIdentity
import com.jerrywolff.phonesyncusbc.sync.SyncProgress
import com.jerrywolff.phonesyncusbc.sync.SyncPhase
import com.jerrywolff.phonesyncusbc.sync.SyncResult
import com.jerrywolff.phonesyncusbc.sync.MtpScanSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class AppSection(val label: String) {
    USB_SOURCE("USB Source"),
    BACKUP_ACTIVITY("Backup"),
}

private data class BackupActivityUi(
    val title: String = "Backup and export activity",
    val status: String = "No backup or export is running.",
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val currentItem: String? = null,
    val bytesProcessed: Long = 0,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
    val running: Boolean = false,
    val failed: Boolean = false,
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
                override fun onReceive(context: Context, intent: Intent) {
                    unregisterReceiver(this)
                    recreate()
                }
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
    var recoveryDeviceType by remember {
        mutableStateOf(RecoveryDeviceType.defaultFor(selectedSource?.detected))
    }
    var identity by remember { mutableStateOf<PeerIdentity?>(null) }
    var trust by remember { mutableStateOf<StoredTrust?>(null) }
    var capabilities by remember { mutableStateOf<SourceCapabilities?>(null) }
    var selectedCategories by remember { mutableStateOf(setOf(ConsentCategory.PHOTOS_AND_VIDEOS)) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf(AppSection.USB_SOURCE) }
    var messageSection by remember { mutableStateOf(AppSection.USB_SOURCE) }
    var backupWorkflowSection by remember { mutableStateOf(AppSection.BACKUP_ACTIVITY) }
    var backupActivity by remember { mutableStateOf(BackupActivityUi()) }
    var liveProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var mtpScanSummary by remember { mutableStateOf<MtpScanSummary?>(null) }
    var identityReadProgress by remember { mutableStateOf<IdentityReadProgress?>(null) }
    var identityReadError by remember { mutableStateOf<String?>(null) }
    var showLibrary by remember { mutableStateOf(false) }
    var libraryEntries by remember { mutableStateOf(emptyList<AuditEntry>()) }
    var previewEntry by remember { mutableStateOf<AuditEntry?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewVideoUri by remember { mutableStateOf<Uri?>(null) }
    var showBackupSelection by remember { mutableStateOf(false) }
    val initialBackupPeerId = remember { application.auditLog.latestExternalPeerId() }
    var backupPeerId by remember { mutableStateOf(initialBackupPeerId) }
    val initialBackupEntries = remember(initialBackupPeerId) {
        application.auditLog.completedExternalTransfers(initialBackupPeerId)
    }
    var backupEntries by remember { mutableStateOf(initialBackupEntries) }
    var selectedBackupIds by remember {
        mutableStateOf<Set<Long>>(initialBackupEntries.mapTo(linkedSetOf()) { it.id })
    }
    var selectedUsbBackupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedUsbPeerId by remember { mutableStateOf<String?>(null) }
    var knownUsbBackupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showTargetWizard by remember { mutableStateOf(false) }
    var targetType by remember { mutableStateOf(BackupTargetType.PHONE_DOWNLOADS) }
    var targetUri by remember { mutableStateOf<Uri?>(null) }
    var targetName by remember { mutableStateOf(DEFAULT_MOBILE_TARGET_NAME) }
    var pendingTargetName by remember { mutableStateOf<String?>(null) }
    var backupStatus by remember {
        mutableStateOf("${selectedBackupIds.size} items ready for $DEFAULT_MOBILE_TARGET_NAME.")
    }
    var usbBackupStatus by remember { mutableStateOf("No USB source data has been recovered yet.") }
    val targetSelectionStore = remember { TargetSelectionStore(context) }
    val savedTarget = remember { targetSelectionStore.load() }
    var targetRestored by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(savedTarget, targetRestored) {
        if (!targetRestored) {
            targetType = savedTarget?.type ?: BackupTargetType.PHONE_DOWNLOADS
            targetUri = savedTarget?.uri
            targetName = savedTarget?.name ?: DEFAULT_MOBILE_TARGET_NAME
            backupStatus = "${selectedBackupIds.size} items ready for $targetName."
            targetRestored = true
        }
    }

    fun clearPreview() {
        previewEntry = null
        previewText = null
        previewBitmap = null
        previewVideoUri = null
    }

    fun refreshSource() {
        sources = application.usbSourceResolver.attachedSources()
        selectedSource = sources.firstOrNull()
        recoveryDeviceType = RecoveryDeviceType.defaultFor(selectedSource?.detected)
        identity = null
        trust = null
        capabilities = null
        mtpScanSummary = null
        identityReadProgress = null
        identityReadError = null
    }

    val source = selectedSource
    val usbCollectedEntries = remember(identity?.peerId, refreshToken) {
        application.auditLog.completedExternalTransfers(identity?.peerId)
    }

    LaunchedEffect(identity?.peerId, refreshToken) {
        val peerId = identity?.peerId
        val currentIds = usbCollectedEntries.mapTo(linkedSetOf()) { it.id }
        selectedUsbBackupIds = mergeSourceBackupSelection(
            peerId = peerId,
            previousPeerId = selectedUsbPeerId,
            currentIds = currentIds,
            knownIds = knownUsbBackupIds,
            selectedIds = selectedUsbBackupIds,
        )
        selectedUsbPeerId = peerId
        knownUsbBackupIds = currentIds
        usbBackupStatus = if (usbCollectedEntries.isEmpty()) {
            "No USB source data has been recovered yet."
        } else {
            "${usbCollectedEntries.size} source items ready for $targetName."
        }
    }

    LaunchedEffect(refreshToken, selectedSource) {
        val source = selectedSource ?: return@LaunchedEffect
        if (!source.permissionGranted) return@LaunchedEffect
        identityReadError = null
        identityReadProgress = IdentityReadProgress(IdentityReadStage.CHECKING_PERMISSION, 0)
        val identityResult = withContext(Dispatchers.IO) {
            runCatching {
                application.usbSourceResolver.resolveIdentity(source) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        identityReadProgress = progress
                    }
                }
            }
        }
        val resolved = identityResult.getOrElse { throwable ->
            identityReadError = throwable.message ?: "The source did not return a readable USB identity."
            return@LaunchedEffect
        }
        identityReadProgress = null
        identity = resolved
        backupPeerId = resolved.peerId
        backupEntries = application.auditLog.completedExternalTransfers(resolved.peerId)
        selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
        backupStatus = "${backupEntries.size} external-source items ready for $targetName."
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
                }
            }
            else -> {
                trust = null
                selectedCategories = resolvedCapabilities.supportedCategories
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && libraryEntries.isNotEmpty()) {
            activeSection = AppSection.BACKUP_ACTIVITY
            backupActivity = BackupActivityUi(
                title = "Export recovered data",
                status = "Starting export...",
                totalItems = libraryEntries.size,
                running = true,
            )
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DataExportManager(context).export(libraryEntries, uri) { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            backupActivity = BackupActivityUi(
                                title = "Export recovered data",
                                status = "Exporting ${progress.completedItems} of ${progress.totalItems}",
                                completedItems = progress.completedItems,
                                totalItems = progress.totalItems,
                                currentItem = progress.currentItem,
                                bytesProcessed = progress.bytesExported,
                                currentItemBytes = progress.currentItemBytes,
                                currentItemTotal = progress.currentItemTotal,
                                running = true,
                            )
                        }
                    }
                }
                message = "Exported ${result.exportedItems} items (${formatBytes(result.bytesExported)})." +
                    if (result.failedItems > 0) " ${result.failedItems} failed." else ""
                messageSection = AppSection.BACKUP_ACTIVITY
                backupActivity = BackupActivityUi(
                    title = "Export recovered data",
                    status = message.orEmpty(),
                    completedItems = result.exportedItems + result.failedItems,
                    totalItems = libraryEntries.size,
                    bytesProcessed = result.bytesExported,
                    failed = result.failedItems > 0,
                )
            }
        }
    }

    fun updateBackupWorkflowStatus(section: AppSection, status: String) {
        if (section == AppSection.USB_SOURCE) {
            usbBackupStatus = status
        } else {
            backupStatus = status
        }
    }

    fun backupWorkflowSelectedCount(): Int {
        return if (backupWorkflowSection == AppSection.USB_SOURCE) {
            selectedUsbBackupIds.size
        } else {
            selectedBackupIds.size
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
            targetUri = uri
            targetType = BackupTargetType.DOCUMENT_TREE
            targetName = listOfNotNull(providerName, folderName)
                .distinctBy(String::lowercase)
                .joinToString(" / ")
            targetSelectionStore.saveFolder(uri, targetName!!)
            pendingTargetName = null
            showTargetWizard = false
            updateBackupWorkflowStatus(
                backupWorkflowSection,
                "${backupWorkflowSelectedCount()} items ready for ${targetName}.",
            )
            message = "Destination saved: ${targetName}."
            messageSection = backupWorkflowSection
        } else {
            pendingTargetName = null
            message = "Folder selection canceled."
            messageSection = backupWorkflowSection
        }
    }

    fun launchTargetPicker(label: String) {
        pendingTargetName = label
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, "Choose backup folder")
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(LOCAL_STORAGE_AUTHORITY, PRIMARY_STORAGE_ROOT_ID),
            )
        }
        val pickerInstruction = "Navigate to the $label folder, then tap USE THIS FOLDER at the bottom."
        message = pickerInstruction
        messageSection = backupWorkflowSection
        Toast.makeText(context, pickerInstruction, Toast.LENGTH_LONG).show()
        allDataExportLauncher.launch(picker)
    }

    fun useDefaultMobileTarget() {
        targetSelectionStore.clear()
        targetType = BackupTargetType.PHONE_DOWNLOADS
        targetUri = null
        targetName = DEFAULT_MOBILE_TARGET_NAME
        showTargetWizard = false
        updateBackupWorkflowStatus(
            backupWorkflowSection,
            "${backupWorkflowSelectedCount()} items ready for $targetName.",
        )
        message = "Backups will stay on this phone in Downloads."
        messageSection = backupWorkflowSection
    }

    fun selectProviderTarget(type: BackupTargetType, name: String) {
        targetType = type
        targetUri = null
        targetName = name
        targetSelectionStore.saveProvider(type, name)
        showTargetWizard = false
        updateBackupWorkflowStatus(
            backupWorkflowSection,
            "${backupWorkflowSelectedCount()} items ready to push to $name.",
        )
        message = "Destination selected: $name. Use the primary push button to continue."
        messageSection = backupWorkflowSection
    }

    fun providerUploadIntent(
        uris: List<Uri>,
        packageName: String?,
    ): Intent {
        val mimeTypes = uris.mapNotNull(context.contentResolver::getType).distinct()
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeTypes.singleOrNull() ?: "*/*"
            packageName?.let(::setPackage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("Phone Sync recovery", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            if (mimeTypes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, "Phone Sync recovery")
        }
    }

    fun launchProviderUpload(
        uris: List<Uri>,
        packageName: String?,
        label: String,
        itemCount: Int,
        ownerSection: AppSection,
    ): Boolean {
        val uploadIntent = providerUploadIntent(uris, packageName)
        val launchResult = runCatching {
            context.startActivity(
                if (packageName == null) {
                    Intent.createChooser(uploadIntent, "Upload backup to")
                } else {
                    uploadIntent
                },
            )
        }.onSuccess {
            val nextStep = if (packageName == null) {
                "Choose an app and destination, then confirm Upload. The selected app controls cloud progress."
            } else {
                "In $label, choose the destination folder and tap Upload. $label controls cloud progress."
            }
            message = "$itemCount recovered item(s) handed directly to $label. $nextStep"
            messageSection = ownerSection
            updateBackupWorkflowStatus(ownerSection, message.orEmpty())
            backupActivity = BackupActivityUi(
                title = "$label upload handoff",
                status = message.orEmpty(),
                running = false,
                failed = false,
            )
        }.onFailure { throwable ->
            message = "Could not open $label. Install/sign in to it or choose another destination. ${throwable.message.orEmpty()}"
            messageSection = ownerSection
            backupActivity = backupActivity.copy(
                title = "$label upload",
                status = message.orEmpty(),
                running = false,
                failed = true,
            )
        }
        return launchResult.isSuccess
    }

    fun uploadBackupEntries(
        entries: List<AuditEntry>,
        packageName: String?,
        label: String,
        ownerSection: AppSection,
    ) {
        backupWorkflowSection = ownerSection
        activeSection = ownerSection
        if (entries.isEmpty()) {
            updateBackupWorkflowStatus(ownerSection, "Select at least one available item before uploading.")
            return
        }
        val directUris = entries.mapNotNull { it.destination?.let(Uri::parse) }
        if (directUris.size != entries.size) {
            updateBackupWorkflowStatus(ownerSection, "One or more selected recovery items are no longer available.")
            return
        }
        val directIntent = providerUploadIntent(directUris, packageName)
        if (directIntent.resolveActivity(context.packageManager) != null) {
            backupActivity = BackupActivityUi(
                title = "$label upload handoff",
                status = "Opening $label with ${entries.size} verified recovery item(s)...",
                running = true,
            )
            if (launchProviderUpload(directUris, packageName, label, entries.size, ownerSection)) return
        }
        if (entries.size == 1) {
            updateBackupWorkflowStatus(ownerSection, "$label cannot accept the selected recovery item.")
            return
        }

        backingUp = true
        val stagingStatus =
            "$label cannot accept multiple files directly. Building a compatibility ZIP in " +
                "Downloads / Phone Sync Uploads. " +
                "$label upload has not started yet."
        updateBackupWorkflowStatus(ownerSection, stagingStatus)
        backupActivity = BackupActivityUi(
            title = "Local staging before $label",
            status = stagingStatus,
            totalItems = entries.size,
            running = true,
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DataExportManager(context).createUploadArchive(entries) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        val progressStatus =
                            "Local ZIP: ${progress.completedItems}/${progress.totalItems} · ${progress.currentItem}. " +
                                "$label upload starts after all items finish and you confirm Upload."
                        updateBackupWorkflowStatus(ownerSection, progressStatus)
                        backupActivity = BackupActivityUi(
                            title = "Local staging before $label",
                            status = progressStatus,
                            completedItems = progress.completedItems,
                            totalItems = progress.totalItems,
                            currentItem = progress.currentItem,
                            bytesProcessed = progress.sourceBytesArchived,
                            currentItemBytes = progress.currentItemBytes,
                            currentItemTotal = progress.currentItemTotal,
                            running = true,
                        )
                    }
                }
            }
            backingUp = false
            if (result.uri == null) {
                val failureStatus = "Could not prepare upload: ${result.error ?: "unknown error"}"
                updateBackupWorkflowStatus(ownerSection, failureStatus)
                message = failureStatus
                messageSection = ownerSection
                backupActivity = backupActivity.copy(
                    status = failureStatus,
                    running = false,
                    failed = true,
                )
            } else {
                val readyStatus =
                    "Local ZIP ready: ${result.archivedItems} items, ${formatBytes(result.archiveBytes)}. Opening $label."
                updateBackupWorkflowStatus(ownerSection, readyStatus)
                backupActivity = BackupActivityUi(
                    title = "Local staging complete",
                    status = readyStatus,
                    completedItems = result.archivedItems,
                    totalItems = result.archivedItems,
                    bytesProcessed = result.archiveBytes,
                )
                launchProviderUpload(
                    listOf(result.uri),
                    packageName,
                    label,
                    result.archivedItems,
                    ownerSection,
                )
            }
        }
    }

    fun backupEntriesToSelectedTarget(
        entries: List<AuditEntry>,
        ownerSection: AppSection,
    ) {
        val destination = targetUri
        val destinationName = targetName ?: DEFAULT_MOBILE_TARGET_NAME
        if (entries.isEmpty()) {
            updateBackupWorkflowStatus(
                ownerSection,
                "Select at least one available item before starting backup.",
            )
            return
        }
        if (backingUp) return

        backupWorkflowSection = ownerSection
        activeSection = ownerSection
        backingUp = true
        val startingStatus = "Copying ${entries.size} items to $destinationName..."
        updateBackupWorkflowStatus(ownerSection, startingStatus)
        message = "BACKUP: preserving recovered source data at $destinationName..."
        messageSection = ownerSection
        backupActivity = BackupActivityUi(
            title = "Backup to $destinationName",
            status = startingStatus,
            totalItems = entries.size,
            running = true,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val publishProgress: (com.jerrywolff.phonesyncusbc.data.ExportProgress) -> Unit = { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            val progressStatus =
                                "Copying ${progress.completedItems} of ${progress.totalItems}"
                            updateBackupWorkflowStatus(ownerSection, progressStatus)
                            backupActivity = BackupActivityUi(
                                title = "Backup to $destinationName",
                                status = progressStatus,
                                completedItems = progress.completedItems,
                                totalItems = progress.totalItems,
                                currentItem = progress.currentItem,
                                bytesProcessed = progress.bytesExported,
                                currentItemBytes = progress.currentItemBytes,
                                currentItemTotal = progress.currentItemTotal,
                                running = true,
                            )
                        }
                    }
                    if (destination == null) {
                        DataExportManager(context).backupToDownloads(entries, publishProgress)
                    } else {
                        DataExportManager(context).export(entries, destination, publishProgress)
                    }
                }
            }.onSuccess { result ->
                val completedStatus = if (result.failedItems == 0) {
                    "Backup complete: ${result.exportedItems} items (${formatBytes(result.bytesExported)})."
                } else {
                    "Backup finished: ${result.exportedItems} copied and ${result.failedItems} failed." +
                        result.error?.let { " First error: $it" }.orEmpty()
                }
                updateBackupWorkflowStatus(ownerSection, completedStatus)
                message = completedStatus
                messageSection = ownerSection
                backupActivity = BackupActivityUi(
                    title = "Backup to $destinationName",
                    status = completedStatus,
                    completedItems = result.exportedItems + result.failedItems,
                    totalItems = entries.size,
                    bytesProcessed = result.bytesExported,
                    failed = result.failedItems > 0,
                )
            }.onFailure { throwable ->
                val failureStatus =
                    "Backup failed: ${throwable.message ?: throwable.javaClass.simpleName}"
                updateBackupWorkflowStatus(ownerSection, failureStatus)
                message = failureStatus
                messageSection = ownerSection
                backupActivity = backupActivity.copy(
                    status = failureStatus,
                    running = false,
                    failed = true,
                )
            }
            backingUp = false
        }
    }

    fun executeBackupForSelectedTarget(
        entries: List<AuditEntry>,
        ownerSection: AppSection,
    ) {
        val providerTarget = targetType.providerTarget()
        if (providerTarget == null) {
            backupEntriesToSelectedTarget(entries, ownerSection)
        } else {
            uploadBackupEntries(
                entries,
                providerTarget.packageName,
                providerTarget.label,
                ownerSection,
            )
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = activeSection.ordinal) {
                AppSection.entries.forEach { section ->
                    Tab(
                        selected = activeSection == section,
                        onClick = {
                            activeSection = section
                            scope.launch { listState.scrollToItem(0) }
                        },
                        text = { Text(section.label) },
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            item {
                Spacer(Modifier.height(12.dp))
            }
            if (activeSection == AppSection.USB_SOURCE) item {
                SourceConnectionPanel(
                    source = source,
                    recoveryDeviceType = recoveryDeviceType,
                    onRecoveryDeviceTypeSelected = { recoveryDeviceType = it },
                    onRefresh = ::refreshSource,
                    onRequestUsbPermission = onRequestUsbPermission,
                    onContinue = {
                        scope.launch {
                            val lastItem = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(minOf(SOURCE_SYNC_SECTION_INDEX, lastItem))
                        }
                    },
                )
            }
            if (activeSection == AppSection.BACKUP_ACTIVITY) item {
                BackupActivityPanel(backupActivity)
            }
            if (activeSection == AppSection.BACKUP_ACTIVITY) item {
                BackupPanel(
                    title = "Preserve recovered source data",
                    description = if (source != null) {
                        "Only verified recovery results from ${source.detected.displayName}."
                    } else {
                        "Only verified results from the most recently recovered external source."
                    },
                    onSelectBackup = {
                        if (!backingUp) {
                            backupWorkflowSection = AppSection.BACKUP_ACTIVITY
                            val peerId = identity?.peerId ?: backupPeerId
                            backupEntries = application.auditLog.completedExternalTransfers(peerId)
                            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                            showTargetWizard = false
                            showBackupSelection = true
                        }
                    },
                    onChooseTarget = {
                        backupWorkflowSection = AppSection.BACKUP_ACTIVITY
                        val peerId = identity?.peerId ?: backupPeerId
                        backupEntries = application.auditLog.completedExternalTransfers(peerId)
                        if (selectedBackupIds.isEmpty()) {
                            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                        }
                        showBackupSelection = false
                        showTargetWizard = true
                    },
                    backingUp = backingUp,
                    selectedItemCount = selectedBackupIds.size,
                    selectedBytes = backupEntries
                        .filter { it.id in selectedBackupIds }
                        .sumOf { it.bytesTransferred },
                    passwordVaultItemCount = backupEntries.count {
                        it.id in selectedBackupIds && it.category == ConsentCategory.PASSWORD_EXPORTS
                    },
                    targetType = targetType,
                    targetName = targetName,
                    backupStatus = backupStatus,
                    onExecuteBackup = {
                        executeBackupForSelectedTarget(
                            backupEntries.filter { it.id in selectedBackupIds },
                            AppSection.BACKUP_ACTIVITY,
                        )
                    },
                )
            }
            if (
                activeSection == AppSection.BACKUP_ACTIVITY &&
                backupWorkflowSection == AppSection.BACKUP_ACTIVITY &&
                showBackupSelection
            ) {
                item {
                    val workflowEntries = if (backupWorkflowSection == AppSection.USB_SOURCE) {
                        usbCollectedEntries
                    } else {
                        backupEntries
                    }
                    val workflowSelectedIds = if (backupWorkflowSection == AppSection.USB_SOURCE) {
                        selectedUsbBackupIds
                    } else {
                        selectedBackupIds
                    }
                    BackupSelectionView(
                        entries = workflowEntries,
                        selectedIds = workflowSelectedIds,
                        onSelectionChanged = { selected ->
                            if (backupWorkflowSection == AppSection.USB_SOURCE) {
                                selectedUsbBackupIds = selected
                            } else {
                                selectedBackupIds = selected
                            }
                            updateBackupWorkflowStatus(
                                backupWorkflowSection,
                                "${selected.size} items ready for $targetName.",
                            )
                        },
                        onCancel = { showBackupSelection = false },
                        onContinue = {
                            showBackupSelection = false
                            updateBackupWorkflowStatus(
                                backupWorkflowSection,
                                "${backupWorkflowSelectedCount()} items ready for $targetName.",
                            )
                        },
                    )
                }
            }
            if (
                activeSection == AppSection.BACKUP_ACTIVITY &&
                backupWorkflowSection == AppSection.BACKUP_ACTIVITY &&
                showTargetWizard
            ) {
                item {
                    TargetMediaWizard(
                        onBack = { showTargetWizard = false },
                        onUsePhoneStorage = ::useDefaultMobileTarget,
                        onChooseFolder = { selectedTargetName ->
                            launchTargetPicker(selectedTargetName)
                        },
                        onUseOneDrive = {
                            selectProviderTarget(BackupTargetType.ONEDRIVE, "OneDrive")
                        },
                        onUseGoogleDrive = {
                            selectProviderTarget(BackupTargetType.GOOGLE_DRIVE, "Google Drive")
                        },
                        onUseOtherApp = {
                            selectProviderTarget(BackupTargetType.OTHER_APP, "Another app")
                        },
                    )
                }
            }
            if (activeSection == AppSection.USB_SOURCE && showLibrary) {
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
                                                message = "This recovered artifact could not be read."
                                                messageSection = AppSection.USB_SOURCE
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
                                                message = "This recovered image could not be read."
                                                messageSection = AppSection.USB_SOURCE
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
                                            message = "No installed app can open this recovered artifact type."
                                            messageSection = AppSection.USB_SOURCE
                                        }
                                    }
                                }
                            },
                            onExport = { exportLauncher.launch(null) },
                        )
                    }
                }
            }
            if (activeSection == AppSection.USB_SOURCE && source == null) {
                item { Text("Connect a source phone using a data-capable USB cable.") }
            } else if (activeSection == AppSection.USB_SOURCE && source != null) {
                if (capabilities == null || identity == null) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Reading USB source identity", style = MaterialTheme.typography.titleMedium)
                                identityReadProgress?.let { progress ->
                                    val percentage = if (progress.totalSteps > 0) {
                                        progress.completedSteps * 100 / progress.totalSteps
                                    } else {
                                        0
                                    }
                                    Text(identityReadStageLabel(progress.stage))
                                    LinearProgressIndicator(
                                        progress = {
                                            (progress.completedSteps.toFloat() / progress.totalSteps)
                                                .coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        "$percentage% · ${progress.completedSteps} of ${progress.totalSteps} checks complete",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                identityReadError?.let { error ->
                                    Text("Source identity read failed: $error")
                                    OutlinedButton(onClick = ::refreshSource, modifier = Modifier.fillMaxWidth()) {
                                        Text("Retry source read")
                                    }
                                }
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
                            onApprove = {
                                val currentIdentity = identity!!
                                val authorizedCategories = capabilities!!.supportedCategories
                                selectedCategories = authorizedCategories
                                val now = System.currentTimeMillis()
                                val created = StoredTrust(
                                    record = com.jerrywolff.phonesyncusbc.domain.TrustRecord(
                                        peerDeviceId = currentIdentity.peerId,
                                        localDeviceId = DeviceIdentity.localId(context),
                                        encryptionKeyProof = application.keyManager.currentProof(),
                                        authorizedCategories = authorizedCategories,
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
                                message = "USB source authorized for every available data category."
                                messageSection = AppSection.USB_SOURCE
                            },
                        )
                    }
                } else if (trust != null && identity != null) {
                    item {
                        TrustedDashboard(
                                trust = trust!!,
                                categories = selectedCategories,
                                syncing = syncing,
                                liveProgress = liveProgress,
                                mtpScanSummary = mtpScanSummary,
                                sourcePlatform = source.detected.platform,
                                auditLog = application.auditLog,
                                onViewLibrary = {
                                    libraryEntries = application.auditLog.completedExternalTransfers(
                                        trust!!.record.peerDeviceId,
                                    )
                                    showLibrary = true
                                },
                                onSync = sync@{
                                    val currentSource = source ?: return@sync
                                    val currentIdentity = identity ?: return@sync
                                    syncing = true
                                    liveProgress = SyncProgress(
                                        currentItem = null,
                                        transferredItems = 0,
                                        skippedItems = 0,
                                        failedItems = 0,
                                        bytesTransferred = 0,
                                        phase = SyncPhase.DISCOVERING,
                                    )
                                    mtpScanSummary = null
                                    message = "LIVE: 0 recovered, 0 already verified, 0 failed."
                                    messageSection = AppSection.USB_SOURCE
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            val publishProgress: (SyncProgress) -> Unit = { progress ->
                                                scope.launch(Dispatchers.Main.immediate) {
                                                    liveProgress = progress
                                                    message = buildLiveStatus(progress)
                                                    messageSection = AppSection.USB_SOURCE
                                                }
                                            }
                                            application.mtpSyncEngine.sync(
                                                source = currentSource,
                                                identity = currentIdentity,
                                                recoveryDeviceType = recoveryDeviceType,
                                                authorizedCategories = selectedCategories,
                                                onProgress = publishProgress,
                                            )
                                        }
                                        syncing = false
                                        val scan = result.mtpScan
                                        liveProgress = SyncProgress(
                                            currentItem = null,
                                            transferredItems = result.transferredItems,
                                            skippedItems = result.skippedItems,
                                            failedItems = result.failedItems,
                                            bytesTransferred = result.bytesTransferred,
                                            phase = SyncPhase.COMPLETE,
                                            discoveredItems = scan?.scannedItems ?: 0,
                                            processedItems = scan?.processedItems ?: 0,
                                            totalItems = scan?.scannedItems ?: 0,
                                            advertisedBytes = scan?.advertisedBytes ?: 0,
                                        )
                                        mtpScanSummary = result.mtpScan
                                        message = buildSyncCompletionStatus(result)
                                        messageSection = AppSection.USB_SOURCE
                                        backupPeerId = currentIdentity.peerId
                                        backupEntries = application.auditLog.completedExternalTransfers(
                                            currentIdentity.peerId,
                                        )
                                        selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                                        refreshToken += 1
                                    }
                                },
                                onRevoke = {
                                    trust = application.trustStore.revoke(trust!!)
                                    message = "Trust revoked. Re-approval is required before another acquisition."
                                    messageSection = AppSection.USB_SOURCE
                                },
                        )
                    }
                    if (usbCollectedEntries.isNotEmpty()) {
                        if (
                            backupWorkflowSection == AppSection.USB_SOURCE &&
                            (backupActivity.running || backupActivity.totalItems > 0 || backupActivity.failed)
                        ) {
                            item { BackupActivityPanel(backupActivity) }
                        }
                        item {
                            BackupPanel(
                                title = "Preserve this recovery set",
                                description =
                                    "Send only verified artifacts recovered from ${source.detected.displayName} to preservation storage.",
                                onSelectBackup = {
                                    if (!backingUp) {
                                        backupWorkflowSection = AppSection.USB_SOURCE
                                        showTargetWizard = false
                                        showBackupSelection = true
                                    }
                                },
                                onChooseTarget = {
                                    backupWorkflowSection = AppSection.USB_SOURCE
                                    showBackupSelection = false
                                    showTargetWizard = true
                                },
                                backingUp = backingUp,
                                selectedItemCount = selectedUsbBackupIds.size,
                                selectedBytes = usbCollectedEntries
                                    .filter { it.id in selectedUsbBackupIds }
                                    .sumOf { it.bytesTransferred },
                                passwordVaultItemCount = usbCollectedEntries.count {
                                    it.id in selectedUsbBackupIds &&
                                        it.category == ConsentCategory.PASSWORD_EXPORTS
                                },
                                targetType = targetType,
                                targetName = targetName,
                                backupStatus = usbBackupStatus,
                                onExecuteBackup = {
                                    executeBackupForSelectedTarget(
                                        usbCollectedEntries.filter { it.id in selectedUsbBackupIds },
                                        AppSection.USB_SOURCE,
                                    )
                                },
                            )
                        }
                        if (
                            backupWorkflowSection == AppSection.USB_SOURCE &&
                            showBackupSelection
                        ) {
                            item {
                                BackupSelectionView(
                                    entries = usbCollectedEntries,
                                    selectedIds = selectedUsbBackupIds,
                                    onSelectionChanged = { selected ->
                                        selectedUsbBackupIds = selected
                                        usbBackupStatus =
                                            "${selected.size} items ready for $targetName."
                                    },
                                    onCancel = { showBackupSelection = false },
                                    onContinue = {
                                        showBackupSelection = false
                                        usbBackupStatus =
                                            "${selectedUsbBackupIds.size} items ready for $targetName."
                                    },
                                )
                            }
                        }
                        if (
                            backupWorkflowSection == AppSection.USB_SOURCE &&
                            showTargetWizard
                        ) {
                            item {
                                TargetMediaWizard(
                                    onBack = { showTargetWizard = false },
                                    onUsePhoneStorage = ::useDefaultMobileTarget,
                                    onChooseFolder = { selectedTargetName ->
                                        launchTargetPicker(selectedTargetName)
                                    },
                                    onUseOneDrive = {
                                        selectProviderTarget(BackupTargetType.ONEDRIVE, "OneDrive")
                                    },
                                    onUseGoogleDrive = {
                                        selectProviderTarget(BackupTargetType.GOOGLE_DRIVE, "Google Drive")
                                    },
                                    onUseOtherApp = {
                                        selectProviderTarget(BackupTargetType.OTHER_APP, "Another app")
                                    },
                                )
                            }
                        }
                    }
                }
            }
            message?.takeIf { messageSection == activeSection }?.let {
                item { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            }
        }
    }

}

@androidx.compose.runtime.Composable
private fun TrustApproval(
    capabilities: SourceCapabilities,
    onApprove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trust External Source", style = MaterialTheme.typography.headlineSmall)
            Text("Authorize every data category this source exposes over USB or as an export file.")
            CapabilityList(capabilities)
            Button(
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Authorize all available data") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CapabilityList(
    capabilities: SourceCapabilities,
) {
    capabilities.categories.forEach { capability ->
        Column {
            Text(capability.category.label())
            Text(capability.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@androidx.compose.runtime.Composable
private fun TrustedDashboard(
    trust: StoredTrust,
    categories: Set<ConsentCategory>,
    syncing: Boolean,
    liveProgress: SyncProgress?,
    mtpScanSummary: MtpScanSummary?,
    sourcePlatform: SourcePlatform,
    auditLog: com.jerrywolff.phonesyncusbc.data.AuditLog,
    onViewLibrary: () -> Unit,
    onSync: () -> Unit,
    onRevoke: () -> Unit,
) {
    val latest = remember(trust.updatedAtEpochMillis) { auditLog.latestSession(trust.record.peerDeviceId) }
    val audit = remember(trust.updatedAtEpochMillis) { auditLog.recentTransfers(trust.record.peerDeviceId, 10) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Read-only logical recovery", style = MaterialTheme.typography.titleMedium)
            Text("All available categories authorized: ${categories.joinToString { it.label() }}")
            Text("Last acquisition: ${latest?.completedAtEpochMillis?.let(::formatTime) ?: "Never"}")
            liveProgress?.let { progress ->
                Text(
                    when {
                        syncing && progress.phase == SyncPhase.DISCOVERING -> "Discovering USB-visible files"
                        syncing && progress.phase == SyncPhase.VERIFYING -> "Verifying recovered copy"
                        syncing -> "Reading USB source"
                        else -> "Last USB read"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                progress.currentItem?.let { currentItem ->
                    val action = when (progress.phase) {
                        SyncPhase.DISCOVERING -> "Inspecting"
                        SyncPhase.VERIFYING -> "Hashing"
                        else -> "Reading"
                    }
                    Text("$action: ${currentItem.substringAfterLast('/')}")
                }
                if (syncing && progress.phase == SyncPhase.DISCOVERING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "${progress.discoveredItems} files found · " +
                            "${formatBytes(progress.advertisedBytes)} advertised",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (progress.phase != SyncPhase.DISCOVERING && progress.totalItems > 0) {
                    val overallPercentage =
                        (progress.processedItems * 100 / progress.totalItems).coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = {
                            (progress.processedItems.toFloat() / progress.totalItems)
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$overallPercentage% overall · ${progress.processedItems} of " +
                            "${progress.totalItems} files processed",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (progress.phase != SyncPhase.DISCOVERING && progress.currentItemTotal > 0) {
                    val currentPercentage =
                        (progress.currentItemBytes * 100 / progress.currentItemTotal).coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = {
                            (progress.currentItemBytes.toFloat() / progress.currentItemTotal)
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$currentPercentage% ${if (progress.phase == SyncPhase.VERIFYING) "verified" else "of current file"} · " +
                            "${formatBytes(progress.currentItemBytes)} / ${formatBytes(progress.currentItemTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (progress.phase != SyncPhase.DISCOVERING && progress.advertisedBytes > 0) {
                    Text(
                        "USB-visible source: ${progress.discoveredItems} files · " +
                            formatBytes(progress.advertisedBytes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Copied ${formatBytes(progress.bytesTransferred)} · " +
                        "${progress.transferredItems} new · ${progress.skippedItems} skipped · " +
                        "${progress.failedItems} failed",
                )
            }
            mtpScanSummary?.let { scan ->
                SourceExportReadiness(sourcePlatform, scan)
            }
            Button(
                onClick = onSync,
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (syncing) "Recovering and verifying..." else "Recover all USB-visible data")
            }
            HorizontalDivider()
            OutlinedButton(onClick = onViewLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("View recovered artifacts")
            }
            Text(
                "Every recovered item must be advertised by the active tethered device over MTP/PTP. " +
                    "Password vaults, voicemails, and app records must first be exported on that device into USB-visible storage.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when (sourcePlatform) {
                    SourcePlatform.ANDROID ->
                        "Android MTP cannot read app-private SMS, call, or mail databases. Prepare USB-visible exports " +
                            "on the source phone first."
                    SourcePlatform.IOS ->
                        "iPhone PTP exposes media, not its private SMS, call, chat, mail, or notification databases. " +
                            "Use supported source-app export files for those records."
                    SourcePlatform.WINDOWS_PHONE ->
                        "Windows Phone MTP exposes shared media files, not its private SMS, call, or email stores. " +
                            "Phone Sync requests every object that the source publishes, but USB cannot force those stores public."
                    else ->
                        "USB can recover files exposed by the source. Private app databases require supported exports."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Recent recovery records", style = MaterialTheme.typography.titleMedium)
            if (audit.isEmpty()) Text("No recovered artifacts yet.")
            audit.forEach { entry -> Text("${entry.category.label()}: ${entry.sourceItem}") }
            OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) { Text("Revoke trust") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SourceExportReadiness(
    sourcePlatform: SourcePlatform,
    scan: MtpScanSummary,
) {
    val found = SourceExportRequirements.categories.filter { it in scan.visibleCategories }
    val missing = SourceExportRequirements.missingFrom(scan.visibleCategories)

    HorizontalDivider()
    Text("USB export readiness", style = MaterialTheme.typography.titleMedium)
    Text("${scan.scannedItems} USB-visible files scanned.")
    Text(
        "Downloads visible: ${if (scan.downloadDirectoryVisible) "Yes" else "No"} · " +
            "Phone Sync exports visible: ${if (scan.phoneSyncDirectoryVisible) "Yes" else "No"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        when {
            scan.partialObject64Supported == true ->
                "USB request mode: 64-bit chunked, standard chunked, then full-file retry."
            scan.partialObjectSupported == true ->
                "USB request mode: standard chunked transfer with full-file retry."
            scan.fullObjectSupported == true ->
                "USB request mode: full-file transfer."
            else ->
                "USB request mode: best-effort full-file compatibility transfer."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (sourcePlatform == SourcePlatform.IOS) {
        HorizontalDivider()
        Text("iPhone photo coverage", style = MaterialTheme.typography.titleMedium)
        Text(
            "PTP exposed ${scan.mediaItemsVisible} media files this scan: " +
                "${scan.mediaItemsTransferred} new, " +
                "${scan.mediaItemsAlreadyCollected} already recovered, " +
                "${scan.mediaItemsNotAuthorized} not authorized, " +
                "${scan.mediaItemsFailed} failed.",
        )
        if (scan.mediaItemsFailed > 0) {
            Text(
                "Keep the iPhone unlocked and connected, then retry. Phone Sync now tries both iOS PTP chunked modes " +
                    "before a full-file request.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Photos stored only in iCloud are not included in the PTP count. On the iPhone, choose " +
                "iCloud Photos > Download and Keep Originals, wait for originals to finish downloading, " +
                "set Photos > Transfer to Mac or PC to Keep Originals, then scan again.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (found.isNotEmpty()) {
        Text("Found: ${found.joinToString { sourceExportLabel(it) }}")
    }
    if (missing.isNotEmpty()) {
        Text(
            "Not exposed by source: ${missing.joinToString { sourceExportLabel(it) }}",
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            when (sourcePlatform) {
                SourcePlatform.ANDROID ->
                    "On the external source phone, use each source app's supported export and save the result in " +
                        "shared USB-visible storage. Reconnect using File transfer / Android Auto mode and recover again."
                SourcePlatform.IOS ->
                    "iPhone PTP exposes downloaded photos and videos, not private SMS, call, voicemail, mail, or password databases. " +
                        "Only files that iOS itself advertises over PTP can be recovered."
                SourcePlatform.WINDOWS_PHONE ->
                    "Phone Sync already requested every MTP-visible Lumia object. Windows Phone provides no USB consent " +
                        "request for private SMS, call history, or email. Restore SMS through the phone's Microsoft account " +
                        "backup when available, and export email from its mail provider; call history has no standard USB export."
                else ->
                    "Create SMS, call, and email export files on the source, expose their folder over MTP, then recover again."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text("SMS, call, and email exports are exposed and eligible for recovery.")
    }
}

private fun ConsentCategory.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun AuditEntry.displayName(): String {
    return sourceItem.substringAfterLast('/').ifBlank { "Recovered artifact" }
}

private fun AuditEntry.storageLocation(): String {
    return destination ?: "Unavailable"
}

private fun isTextLike(entry: AuditEntry): Boolean {
    if (
        entry.category == ConsentCategory.PASSWORD_EXPORTS ||
        entry.category == ConsentCategory.VOICEMAIL_EXPORTS
    ) return false
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
            if (count <= 0) "(Empty recovered artifact)"
            else String(buffer, 0, count, Charsets.UTF_8)
        }
    }.getOrNull()
}

private fun collectedItemIntent(destination: Uri, mimeType: String): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        data = destination
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, destination)
        clipData = ClipData.newRawUri("Recovered artifact", destination)
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

private fun identityReadStageLabel(stage: IdentityReadStage): String = when (stage) {
    IdentityReadStage.CHECKING_PERMISSION -> "Checking Android USB authorization"
    IdentityReadStage.READING_USB_SERIAL -> "Reading the USB device serial"
    IdentityReadStage.OPENING_MEDIA_SESSION -> "Opening the MTP/PTP media session"
    IdentityReadStage.READING_DEVICE_INFO -> "Reading source device information"
    IdentityReadStage.CREATING_IDENTITY -> "Creating a stable source identity"
    IdentityReadStage.COMPLETE -> "Source identity ready"
}

private fun buildLiveStatus(progress: SyncProgress): String {
    if (progress.phase == SyncPhase.DISCOVERING) {
        return "LIVE: ${progress.discoveredItems} USB-visible files found, " +
            "${formatBytes(progress.advertisedBytes)} advertised."
    }
    val processed = if (progress.totalItems > 0) {
        " ${progress.processedItems}/${progress.totalItems} files processed."
    } else {
        ""
    }
    val current = if (progress.currentItemTotal > 0) {
        " Current file ${formatBytes(progress.currentItemBytes)}/${formatBytes(progress.currentItemTotal)}."
    } else {
        ""
    }
    return "LIVE:${processed} ${progress.transferredItems} transferred, " +
        "${progress.skippedItems} skipped, ${progress.failedItems} failed.$current"
}

private fun buildSyncCompletionStatus(result: SyncResult): String {
    val missingExports = result.mtpScan
        ?.let { SourceExportRequirements.missingFrom(it.visibleCategories) }
        .orEmpty()
    val base = "${result.status}: ${result.transferredItems} transferred, " +
        "${result.skippedItems} already verified, ${result.failedItems} failed."
    val inventory = result.recoveryInventory
    val inventoryStatus = when {
        inventory?.uri != null -> {
            val passwordStatus = if (inventory.passwordArtifactCount > 0) {
                " ${inventory.recoveredPasswordArtifactCount}/${inventory.passwordArtifactCount} " +
                    "password artifacts accounted for as opaque files."
            } else {
                " No USB-visible password artifacts were found."
            }
            " Inventory saved as ${inventory.displayName}.$passwordStatus"
        }
        inventory?.error != null -> " Inventory generation failed: ${inventory.error}."
        else -> ""
    }
    return if (missingExports.isEmpty()) {
        base + inventoryStatus
    } else {
        "$base Source did not expose: ${missingExports.joinToString { sourceExportLabel(it) }}.$inventoryStatus"
    }
}

private fun sourceExportLabel(category: ConsentCategory): String = when (category) {
    ConsentCategory.SMS_EXPORTS -> "SMS/MMS"
    ConsentCategory.CALL_LOGS -> "call logs"
    ConsentCategory.EMAIL_EXPORTS -> "email exports"
    else -> category.label()
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
            Text("Recovered artifacts", style = MaterialTheme.typography.headlineSmall)
            Text("${entries.size} verified recovery results available in Android storage.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(onClick = onExport, enabled = entries.isNotEmpty()) { Text("Export all") }
            }
            Text("Recovered source folders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Items open from their recorded Android storage location. Choose a type to browse its recovery folder.",
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
                Text("No verified recovery results yet.")
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
                            if (entry.sourceSize > 0) {
                                Text("Source size: ${formatBytes(entry.sourceSize)}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.sourceModifiedAtEpochMillis > 0) {
                                Text(
                                    "Source modified: ${formatTime(entry.sourceModifiedAtEpochMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            entry.contentSha256?.let { sha256 ->
                                Text("SHA-256: $sha256", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = { onOpen(entry) },
                                enabled = entry.category != ConsentCategory.PASSWORD_EXPORTS,
                            ) {
                                Text(
                                    if (entry.category == ConsentCategory.PASSWORD_EXPORTS) {
                                        "Sensitive artifact opening disabled"
                                    } else {
                                        "Open recovered artifact"
                                    },
                                )
                            }
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
private fun SourceConnectionPanel(
    source: AttachedSource?,
    recoveryDeviceType: RecoveryDeviceType,
    onRecoveryDeviceTypeSelected: (RecoveryDeviceType) -> Unit,
    onRefresh: () -> Unit,
    onRequestUsbPermission: (AttachedSource) -> Unit,
    onContinue: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect an owned recovery source", style = MaterialTheme.typography.titleMedium)
            if (source == null) {
                Text("Connect an Android phone, iPhone, or MTP/PTP device with a data-capable USB cable.")
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Find USB source")
                }
            } else {
                Text(source.detected.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${source.detected.platform} · ${source.detected.family} · ${source.detected.physicalConnection}")
                Text("Recovery device type", style = MaterialTheme.typography.labelLarge)
                RecoveryDeviceType.entries.chunked(2).forEach { types ->
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        types.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = recoveryDeviceType == type,
                                onClick = { onRecoveryDeviceTypeSelected(type) },
                                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                            ) {
                                Text(type.label)
                            }
                        }
                    }
                }
                Text(
                    RecoveryProfiles.forDevice(recoveryDeviceType).passwordTarget +
                        " are copied intact without decryption.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Purpose: ${RecoveryProfiles.forDevice(recoveryDeviceType).purposes.joinToString { it.label }}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(LOGICAL_ACQUISITION_LIMIT, style = MaterialTheme.typography.bodySmall)
                if (source.permissionGranted) {
                    Text("USB data access authorized")
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue to recovery acquisition")
                    }
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("Refresh source")
                    }
                } else {
                    Text("Android must approve access to this USB device.")
                    Button(
                        onClick = { onRequestUsbPermission(source) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Authorize USB data access") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun BackupActivityPanel(activity: BackupActivityUi) {
    val progress = if (activity.totalItems > 0) {
        (activity.completedItems.toFloat() / activity.totalItems).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup and export activity", style = MaterialTheme.typography.titleMedium)
            if (activity.title != "Backup and export activity") Text(activity.title)
            if (activity.totalItems > 0 || activity.running) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${activity.completedItems} of ${activity.totalItems} items",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            activity.currentItem?.let { currentItem ->
                Text("Current: $currentItem", style = MaterialTheme.typography.bodySmall)
            }
            if (activity.running && activity.currentItemTotal > 0) {
                LinearProgressIndicator(
                    progress = {
                        (activity.currentItemBytes.toFloat() / activity.currentItemTotal)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Current file: ${formatBytes(activity.currentItemBytes)} / " +
                        formatBytes(activity.currentItemTotal),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (activity.running && activity.currentItem != null && activity.currentItemTotal <= 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Current file size is unavailable.", style = MaterialTheme.typography.bodySmall)
            }
            if (activity.bytesProcessed > 0) {
                Text("Processed: ${formatBytes(activity.bytesProcessed)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                activity.status,
                color = if (activity.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (activity.running && activity.totalItems <= 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun BackupPanel(
    title: String = "Preserve recovered data",
    description: String = "Preserve on this phone, an SD card, or an attached USB drive.",
    onSelectBackup: () -> Unit,
    onChooseTarget: () -> Unit,
    backingUp: Boolean,
    selectedItemCount: Int,
    selectedBytes: Long,
    passwordVaultItemCount: Int,
    targetType: BackupTargetType,
    targetName: String?,
    backupStatus: String,
    onExecuteBackup: () -> Unit,
) {
    val primaryActionLabel = targetType.primaryActionLabel()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("1. Data", style = MaterialTheme.typography.titleSmall)
            Text("$selectedItemCount items selected (${formatBytes(selectedBytes)})")
            if (passwordVaultItemCount > 0) {
                Text(
                    "$passwordVaultItemCount sensitive password artifact(s) selected. Protect the destination account. " +
                        "Phone Sync copies them intact and does not preview, parse, or decrypt credentials.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onSelectBackup,
                enabled = !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change selected data") }
            HorizontalDivider()
            Text("2. Destination", style = MaterialTheme.typography.titleSmall)
            Text(targetName ?: DEFAULT_MOBILE_TARGET_NAME)
            Text(
                when (targetType) {
                    BackupTargetType.PHONE_DOWNLOADS ->
                        "The backup will be written directly to this phone's Downloads folder."
                    BackupTargetType.DOCUMENT_TREE ->
                        "The backup will be written directly to the selected folder."
                    BackupTargetType.ONEDRIVE ->
                        "Phone Sync hands verified files directly to OneDrive. Choose the OneDrive folder and tap Upload; " +
                            "OneDrive then controls transfer progress. A local ZIP is used only if OneDrive rejects multi-file sharing."
                    BackupTargetType.GOOGLE_DRIVE ->
                        "Phone Sync hands verified files directly to Google Drive. Choose the folder and confirm Upload; " +
                            "a local ZIP is used only if Drive rejects multi-file sharing."
                    BackupTargetType.OTHER_APP ->
                        "Phone Sync first tries a direct multi-file handoff. It builds a local compatibility ZIP only when " +
                            "the selected app cannot accept multiple files."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onChooseTarget,
                enabled = !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose destination") }
            HorizontalDivider()
            Text("3. Preserve", style = MaterialTheme.typography.titleSmall)
            Text(backupStatus, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onExecuteBackup,
                enabled = !backingUp && selectedItemCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (backingUp) "Preparing preservation..." else primaryActionLabel)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TargetMediaWizard(
    onBack: () -> Unit,
    onUsePhoneStorage: () -> Unit,
    onChooseFolder: (String) -> Unit,
    onUseOneDrive: () -> Unit,
    onUseGoogleDrive: () -> Unit,
    onUseOtherApp: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose destination", style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = onUsePhoneStorage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use this phone's Downloads") }
            OutlinedButton(
                onClick = { onChooseFolder("local, SD, USB, or document-provider folder") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose folder from Android picker") }
            OutlinedButton(onClick = onUseOneDrive, modifier = Modifier.fillMaxWidth()) {
                Text("OneDrive")
            }
            OutlinedButton(onClick = onUseGoogleDrive, modifier = Modifier.fillMaxWidth()) {
                Text("Google Drive")
            }
            OutlinedButton(onClick = onUseOtherApp, modifier = Modifier.fillMaxWidth()) {
                Text("Another installed app")
            }
            Text(
                "Folder targets write directly. App targets prepare one package, open the selected app, then let you choose its folder and confirm Upload.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

private const val SOURCE_SYNC_SECTION_INDEX = 3
private const val DEFAULT_MOBILE_TARGET_NAME = "This phone / Downloads / Phone Sync Backups"
private const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_STORAGE_ROOT_ID = "primary"

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
            Text("Select recovery artifacts", style = MaterialTheme.typography.headlineSmall)
            Text("Choose verified recovery results to preserve on target media.")
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
                Text("No recovered data is available to preserve.")
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
    ConsentCategory.PHOTOS_AND_VIDEOS -> "Photos and videos recovered from the external source."
    ConsentCategory.DOCUMENTS -> "Documents recovered from the external source."
    ConsentCategory.APPLICATION_DATA ->
        "Application data files that the external source exposes through normal USB access."
    ConsentCategory.CONFIGURATION -> "Configuration and settings files exposed by the external source."
    ConsentCategory.LOGS -> "Logs, diagnostics, and crash reports exposed by the external source."
    ConsentCategory.SYSTEM_INFORMATION -> "System-information reports exposed by the external source."
    ConsentCategory.CONTACTS -> "vCard contacts exported by the external source."
    ConsentCategory.CALL_LOGS -> "Call history exported by the external source."
    ConsentCategory.CALENDAR -> "Calendar events exported by the external source."
    ConsentCategory.SELECTED_FOLDERS -> "Legacy selected-folder data retained from an earlier app version."
    ConsentCategory.CLOUD_ACCOUNTS -> "Legacy cloud-provider data retained from an earlier app version."
    ConsentCategory.SMS_EXPORTS -> "User-created SMS export files, not private SMS databases."
    ConsentCategory.CHAT_EXPORTS -> "User-created chat export files, not private chat databases."
    ConsentCategory.EMAIL_EXPORTS -> "User-created email export files, not private mail databases."
    ConsentCategory.NOTIFICATION_EXPORTS -> "Notification records exported by the external source."
    ConsentCategory.PASSWORD_EXPORTS ->
        "Sensitive password vaults, browser credential stores, and credential backups copied without decryption."
    ConsentCategory.VOICEMAIL_EXPORTS ->
        "Voicemail audio or visual-voicemail files explicitly exported from the source phone or carrier app."
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
                OutlinedButton(onClick = onBack) { Text("Back to recovered artifacts") }
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
            OutlinedButton(onClick = onBack) { Text("Back to recovered artifacts") }
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
            OutlinedButton(onClick = onBack) { Text("Back to recovered artifacts") }
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
    if (entry.category == ConsentCategory.PASSWORD_EXPORTS) return false
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
}

private fun isVideoLike(entry: AuditEntry): Boolean {
    if (entry.category == ConsentCategory.PASSWORD_EXPORTS) return false
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("3g2", "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")
}

private const val PREVIEW_MAX_BYTES = 128 * 1024