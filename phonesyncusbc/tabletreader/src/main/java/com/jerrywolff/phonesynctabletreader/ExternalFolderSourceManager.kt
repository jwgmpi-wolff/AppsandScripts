package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.RecoveryIssue
import com.jerrywolff.phonesyncusbc.data.RecoveryIssueReason
import com.jerrywolff.phonesyncusbc.data.TransferStatus
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class FolderScanProgress(
    val discoveredFiles: Int,
    val processedFiles: Int,
    val totalFiles: Int,
    val currentItem: String,
    val bytesRead: Long,
)

data class FolderScanResult(
    val treeUri: Uri,
    val sourceId: String,
    val sourceName: String,
    val entries: List<AuditEntry>,
    val issues: List<RecoveryIssue>,
    val scannedAtEpochMillis: Long,
    val error: String? = null,
)

data class ArchiveLocationCandidate(
    val uri: Uri,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
)

data class ArchiveLocationScanResult(
    val treeUri: Uri,
    val locationName: String,
    val candidates: List<ArchiveLocationCandidate>,
    val issues: List<RecoveryIssue>,
    val error: String? = null,
)

class ExternalFolderSourceManager(
    private val context: Context,
    private val stateFileName: String = STATE_FILE,
    private val preferencesName: String = PREFERENCES_NAME,
) {
    fun selectedTreeUri(): Uri? {
        val saved = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(SELECTED_TREE_URI_KEY, null)
        if (!saved.isNullOrBlank()) return Uri.parse(saved)
        val state = stateFile()
        return if (state.isFile) {
            runCatching { Uri.parse(JSONObject(state.readText()).getString("treeUri")) }.getOrNull()
        } else {
            null
        }
    }

    fun rememberTreeUri(treeUri: Uri) {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(SELECTED_TREE_URI_KEY, treeUri.toString())
            .apply()
    }

    fun hasPersistedReadAccess(treeUri: Uri): Boolean {
        if (treeUri.scheme == "file") return true
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission
        }
    }

    fun scan(
        treeUri: Uri,
        onProgress: (FolderScanProgress) -> Unit = {},
    ): FolderScanResult {
        rememberTreeUri(treeUri)
        val root = if (treeUri.scheme == "file") {
            treeUri.path?.let { path -> File(path) }?.let(DocumentFile::fromFile)
        } else {
            DocumentFile.fromTreeUri(context, treeUri)
        }
            ?: return failure(treeUri, "Android could not open the selected OneDrive or storage folder.")
        return scanRoot(treeUri, root, onProgress)
    }

    fun findRecoveryArchives(
        treeUri: Uri,
        onProgress: (FolderScanProgress) -> Unit = {},
    ): ArchiveLocationScanResult {
        rememberTreeUri(treeUri)
        val root = if (treeUri.scheme == "file") {
            treeUri.path?.let(::File)?.let(DocumentFile::fromFile)
        } else {
            DocumentFile.fromTreeUri(context, treeUri)
        } ?: return ArchiveLocationScanResult(
            treeUri = treeUri,
            locationName = "Archive location",
            candidates = emptyList(),
            issues = listOf(issue(treeUri.toString(), "Android could not open the selected archive location.")),
            error = "Android could not open the selected archive location.",
        )
        return findRecoveryArchivesRoot(treeUri, root, onProgress)
    }

    internal fun findRecoveryArchivesRoot(
        treeUri: Uri,
        root: DocumentFile,
        onProgress: (FolderScanProgress) -> Unit = {},
    ): ArchiveLocationScanResult {
        val locationName = root.name?.takeIf(String::isNotBlank) ?: "Archive location"
        val candidates = mutableListOf<ArchiveLocationCandidate>()
        val issues = mutableListOf<RecoveryIssue>()
        val pendingDirectories = ArrayDeque<FolderDocument>()
        val visitedDirectories = linkedSetOf<String>()
        var incompleteFolders = 0
        pendingDirectories.add(FolderDocument(root, ""))

        while (pendingDirectories.isNotEmpty()) {
            val current = pendingDirectories.removeFirst()
            if (!visitedDirectories.add(current.document.uri.toString())) continue
            val currentPath = current.relativePath.ifBlank { locationName }
            val listing = listChildren(treeUri, current.document, currentPath, candidates.size, onProgress)
            listing.incompleteReason?.let { detail ->
                incompleteFolders += 1
                issues += issue(
                    currentPath,
                    detail.replace("Resync folder", "Refresh archive location", ignoreCase = true),
                )
            }
            listing.documents
                .sortedWith(compareBy({ it.name.lowercase() }, { it.uri.toString() }))
                .forEachIndexed { index, child ->
                    val childName = child.name.takeIf(String::isNotBlank) ?: "unnamed-${index + 1}"
                    val childPath = listOf(current.relativePath, childName)
                        .filter(String::isNotBlank)
                        .joinToString("/")
                    when {
                        child.isDirectory -> pendingDirectories.add(FolderDocument(child.document, childPath))
                        child.isFile && child.isZipArchive() -> candidates += ArchiveLocationCandidate(
                            uri = child.uri,
                            relativePath = childPath,
                            displayName = childName,
                            sizeBytes = runCatching { child.document.length() }.getOrDefault(0L).coerceAtLeast(0L),
                            modifiedAtEpochMillis = runCatching { child.document.lastModified() }
                                .getOrDefault(0L)
                                .coerceAtLeast(0L),
                        )
                    }
                }
            onProgress(
                FolderScanProgress(
                    discoveredFiles = candidates.size,
                    processedFiles = visitedDirectories.size,
                    totalFiles = 0,
                    currentItem = currentPath,
                    bytesRead = 0,
                ),
            )
        }

        val sortedCandidates = candidates.sortedBy { it.relativePath.lowercase() }
        val error = when {
            incompleteFolders > 0 -> "OneDrive is still loading $incompleteFolders folder(s). " +
                "The archives shown are available now; tap Refresh archive location to check again."
            sortedCandidates.isEmpty() -> "No recovery ZIP files were found in $locationName or its subfolders."
            else -> null
        }
        return ArchiveLocationScanResult(treeUri, locationName, sortedCandidates, issues, error)
    }

    internal fun scanRoot(
        treeUri: Uri,
        root: DocumentFile,
        onProgress: (FolderScanProgress) -> Unit = {},
    ): FolderScanResult {
        val sourceId = sha256("owner-approved-external-folder|$treeUri")
        val sourceName = root.name?.takeIf(String::isNotBlank) ?: "External storage folder"
        val issues = mutableListOf<RecoveryIssue>()
        val files = mutableListOf<FolderDocument>()
        val pendingDirectories = ArrayDeque<FolderDocument>()
        val visitedDirectories = linkedSetOf<String>()
        val incompleteDirectories = linkedSetOf<String>()
        pendingDirectories.add(FolderDocument(root, ""))

        while (pendingDirectories.isNotEmpty()) {
            val current = pendingDirectories.removeFirst()
            if (!visitedDirectories.add(current.document.uri.toString())) continue
            val currentPath = current.relativePath.ifBlank { sourceName }
            val listing = listChildren(treeUri, current.document, currentPath, files.size, onProgress)
            listing.incompleteReason?.let { detail ->
                incompleteDirectories += currentPath
                issues += issue(currentPath, detail)
            }
            val children = listing.documents
                .sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.uri.toString() }))
            children.forEachIndexed { index, child ->
                val childName = child.name.takeIf(String::isNotBlank) ?: "unnamed-${index + 1}"
                val childPath = listOf(current.relativePath, childName)
                    .filter(String::isNotBlank)
                    .joinToString("/")
                when {
                    child.isDirectory -> pendingDirectories.add(FolderDocument(child.document, childPath))
                    child.isFile -> files += FolderDocument(child.document, childPath)
                    else -> issues += issue(
                        childPath,
                        "This document is neither a readable file nor directory. Restore provider access and tap Resync folder.",
                    )
                }
            }
            onProgress(
                FolderScanProgress(
                    discoveredFiles = files.size,
                    processedFiles = 0,
                    totalFiles = 0,
                    currentItem = current.relativePath.ifBlank { sourceName },
                    bytesRead = 0,
                ),
            )
        }

        val entries = mutableListOf<AuditEntry>()
        var totalBytesRead = 0L
        val scanTime = System.currentTimeMillis()
        files.forEachIndexed { index, file ->
            val advertisedBytes = runCatching { file.document.length() }.getOrDefault(0L).coerceAtLeast(0)
            val modifiedAt = runCatching { file.document.lastModified() }.getOrDefault(0L).coerceAtLeast(0)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            val readError = runCatching {
                context.contentResolver.openInputStream(file.document.uri).use { input ->
                    checkNotNull(input) { "The document provider returned no readable stream." }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        bytesRead += count
                        onProgress(
                            FolderScanProgress(
                                discoveredFiles = files.size,
                                processedFiles = index,
                                totalFiles = files.size,
                                currentItem = file.relativePath,
                                bytesRead = totalBytesRead + bytesRead,
                            ),
                        )
                    }
                }
            }.exceptionOrNull()
            if (readError != null) {
                issues += issue(
                    file.relativePath,
                    "Restore access to this file and tap Resync folder. ${readError.message.orEmpty()}",
                )
                return@forEachIndexed
            }

            val contentSha256 = digest.digest().toHex()
            val sourceItem = "/Owner-approved external storage folder/${encodeSourcePath(file.relativePath)}"
            val sourceFingerprint = sha256(
                "$sourceId|$sourceItem|$bytesRead|$modifiedAt|$contentSha256",
            )
            entries += AuditEntry(
                id = stableId(sourceFingerprint),
                transferredAtEpochMillis = scanTime,
                category = TransferClassifier.classify(file.relativePath),
                sourceItem = sourceItem,
                destination = file.document.uri.toString(),
                bytesTransferred = bytesRead,
                status = TransferStatus.COMPLETED,
                error = null,
                sourceSize = bytesRead,
                sourceModifiedAtEpochMillis = modifiedAt,
                contentSha256 = contentSha256,
                peerId = sourceId,
                sourceFingerprint = sourceFingerprint,
            )
            if (advertisedBytes > 0 && advertisedBytes != bytesRead) {
                issues += issue(
                    file.relativePath,
                    "The file changed while it was read (advertised $advertisedBytes bytes, read $bytesRead). " +
                        "The verified read is available; tap Resync folder after uploads finish to capture the final version.",
                )
            }
            totalBytesRead += bytesRead
            onProgress(
                FolderScanProgress(
                    discoveredFiles = files.size,
                    processedFiles = index + 1,
                    totalFiles = files.size,
                    currentItem = file.relativePath,
                    bytesRead = totalBytesRead,
                ),
            )
        }

        val result = FolderScanResult(
            treeUri = treeUri,
            sourceId = sourceId,
            sourceName = sourceName,
            entries = entries,
            issues = issues,
            scannedAtEpochMillis = scanTime,
            error = if (entries.isEmpty()) {
                "No readable files were found. Upload files into the selected folder, then tap Resync folder."
            } else {
                null
            },
        )
        if (incompleteDirectories.isNotEmpty()) {
            val incompleteDetail = "OneDrive or the cloud provider is still syncing " +
                "${incompleteDirectories.size} folder(s). The last complete snapshot was kept. " +
                "Keep the provider online, then tap Resync folder again."
            val previous = loadSnapshot()?.takeIf { saved ->
                saved.treeUri == treeUri && saved.entries.isNotEmpty()
            }
            return previous?.copy(
                issues = (previous.issues + issues).distinctBy { recoveryIssue ->
                    recoveryIssue.sourceItem to recoveryIssue.remediation
                },
                scannedAtEpochMillis = scanTime,
                error = incompleteDetail,
            ) ?: result.copy(error = incompleteDetail)
        }
        save(result)
        return result
    }

    private fun listChildren(
        treeUri: Uri,
        directory: DocumentFile,
        displayPath: String,
        discoveredFiles: Int,
        onProgress: (FolderScanProgress) -> Unit,
    ): ChildListing {
        if (treeUri.scheme != "content" || !DocumentsContract.isTreeUri(treeUri)) {
            return runCatching {
                ChildListing(
                    documents = directory.listFiles().map { child ->
                        ListedDocument(
                            document = child,
                            name = child.name.orEmpty(),
                            mimeType = child.type.orEmpty(),
                            isDirectory = child.isDirectory,
                            isFile = child.isFile,
                        )
                    },
                )
            }.getOrElse { throwable ->
                ChildListing(
                    documents = emptyList(),
                    incompleteReason = "Restore read permission for this directory and tap Resync folder. " +
                        throwable.message.orEmpty(),
                )
            }
        }

        val parentDocumentId = runCatching { DocumentsContract.getDocumentId(directory.uri) }
            .getOrElse { throwable ->
                return ChildListing(
                    documents = emptyList(),
                    incompleteReason = "The cloud provider returned an invalid folder identifier. " +
                        "Reconnect the folder and resync. ${throwable.message.orEmpty()}",
                )
            }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        var lastDocuments = emptyList<ListedDocument>()

        repeat(PROVIDER_QUERY_ATTEMPTS) { attempt ->
            val query = runCatching { queryProviderChildren(treeUri, childrenUri) }
                .getOrElse { throwable ->
                    return ChildListing(
                        documents = lastDocuments,
                        incompleteReason = "OneDrive or the cloud provider could not list this folder. " +
                            "Reconnect it and tap Resync folder. ${throwable.message.orEmpty()}",
                    )
                }
            lastDocuments = query.documents
            if (!query.loading) return ChildListing(lastDocuments)

            onProgress(
                FolderScanProgress(
                    discoveredFiles = discoveredFiles + lastDocuments.count(ListedDocument::isFile),
                    processedFiles = 0,
                    totalFiles = 0,
                    currentItem = "Waiting for OneDrive / cloud sync: $displayPath",
                    bytesRead = 0,
                ),
            )
            if (attempt < PROVIDER_QUERY_ATTEMPTS - 1) awaitProviderChange(childrenUri)
        }

        return ChildListing(
            documents = lastDocuments,
            incompleteReason = "OneDrive or the cloud provider is still loading this folder. " +
                "Keep it online and tap Resync folder again.",
        )
    }

    private fun queryProviderChildren(treeUri: Uri, childrenUri: Uri): ProviderQuery {
        val documents = mutableListOf<ListedDocument>()
        val cursor = context.contentResolver.query(
            childrenUri,
            PROVIDER_PROJECTION,
            null,
            null,
            null,
        ) ?: return ProviderQuery(emptyList(), loading = true)
        cursor.use {
            val loading = it.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                val documentId = it.getString(idColumn)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val document = DocumentFile.fromSingleUri(context, documentUri) ?: continue
                val mimeType = it.getString(mimeColumn).orEmpty()
                documents += ListedDocument(
                    document = document,
                    name = it.getString(nameColumn).orEmpty(),
                    mimeType = mimeType,
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    isFile = mimeType.isNotBlank() && mimeType != DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
            return ProviderQuery(documents, loading)
        }
    }

    private fun awaitProviderChange(uri: Uri) {
        val changed = CountDownLatch(1)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                changed.countDown()
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(uri, true, observer)
            try {
                changed.await(PROVIDER_QUERY_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } finally {
                context.contentResolver.unregisterContentObserver(observer)
            }
        }
    }

    fun loadSnapshot(): FolderScanResult? {
        val state = stateFile()
        if (!state.isFile) return null
        return runCatching {
            val root = JSONObject(state.readText())
            val treeUri = Uri.parse(root.getString("treeUri"))
            val sourceId = root.getString("sourceId")
            val sourceName = root.getString("sourceName")
            val entries = root.getJSONArray("entries").toObjects { item ->
                AuditEntry(
                    id = item.getLong("id"),
                    transferredAtEpochMillis = item.getLong("transferredAtEpochMillis"),
                    category = ConsentCategory.valueOf(item.getString("category")),
                    sourceItem = item.getString("sourceItem"),
                    destination = item.getString("destination"),
                    bytesTransferred = item.getLong("bytesTransferred"),
                    status = TransferStatus.COMPLETED,
                    error = null,
                    sourceSize = item.getLong("sourceSize"),
                    sourceModifiedAtEpochMillis = item.getLong("sourceModifiedAtEpochMillis"),
                    contentSha256 = item.getString("contentSha256"),
                    peerId = sourceId,
                    sourceFingerprint = item.getString("sourceFingerprint"),
                )
            }
            val issues = root.optJSONArray("issues")?.toObjects { item ->
                RecoveryIssue(
                    sourceItem = item.getString("sourceItem"),
                    reason = RecoveryIssueReason.valueOf(item.getString("reason")),
                    remediation = item.getString("remediation"),
                    retryable = item.getBoolean("retryable"),
                )
            }.orEmpty()
            FolderScanResult(
                treeUri = treeUri,
                sourceId = sourceId,
                sourceName = sourceName,
                entries = entries,
                issues = issues,
                scannedAtEpochMillis = root.getLong("scannedAtEpochMillis"),
                error = root.optString("error").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    private fun save(result: FolderScanResult) {
        val root = JSONObject()
            .put("treeUri", result.treeUri.toString())
            .put("sourceId", result.sourceId)
            .put("sourceName", result.sourceName)
            .put("scannedAtEpochMillis", result.scannedAtEpochMillis)
            .put("error", result.error.orEmpty())
            .put("entries", JSONArray().apply {
                result.entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("transferredAtEpochMillis", entry.transferredAtEpochMillis)
                            .put("category", entry.category.name)
                            .put("sourceItem", entry.sourceItem)
                            .put("destination", entry.destination)
                            .put("bytesTransferred", entry.bytesTransferred)
                            .put("sourceSize", entry.sourceSize)
                            .put("sourceModifiedAtEpochMillis", entry.sourceModifiedAtEpochMillis)
                            .put("contentSha256", entry.contentSha256)
                            .put("sourceFingerprint", entry.sourceFingerprint),
                    )
                }
            })
            .put("issues", JSONArray().apply {
                result.issues.forEach { recoveryIssue ->
                    put(
                        JSONObject()
                            .put("sourceItem", recoveryIssue.sourceItem)
                            .put("reason", recoveryIssue.reason.name)
                            .put("remediation", recoveryIssue.remediation)
                            .put("retryable", recoveryIssue.retryable),
                    )
                }
            })
        val atomicFile = AtomicFile(stateFile())
        val output = atomicFile.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw throwable
        }
    }

    private fun failure(treeUri: Uri, detail: String): FolderScanResult {
        return FolderScanResult(
            treeUri = treeUri,
            sourceId = sha256("owner-approved-external-folder|$treeUri"),
            sourceName = "External storage folder",
            entries = emptyList(),
            issues = listOf(issue(treeUri.toString(), detail)),
            scannedAtEpochMillis = System.currentTimeMillis(),
            error = detail,
        )
    }

    private fun issue(sourceItem: String, remediation: String) = RecoveryIssue(
        sourceItem = sourceItem,
        reason = RecoveryIssueReason.COPY_FAILED,
        remediation = remediation,
        retryable = true,
    )

    private fun encodeSourcePath(path: String): String = path
        .replace('\\', '/')
        .split('/')
        .filter(String::isNotBlank)
        .joinToString("/") { segment ->
            buildString {
                segment.toByteArray(Charsets.UTF_8).forEach { byte ->
                    val value = byte.toInt() and 0xff
                    val safe = value in 'A'.code..'Z'.code ||
                        value in 'a'.code..'z'.code ||
                        value in '0'.code..'9'.code ||
                        value == '.'.code || value == '_'.code
                    if (safe) append(value.toChar()) else append("%%%02X".format(value))
                }
            }
        }

    private fun stableId(material: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (digest[index].toLong() and 0xff)
        return (value and Long.MAX_VALUE).coerceAtLeast(1)
    }

    private fun sha256(material: String): String = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun <T> JSONArray.toObjects(transform: (JSONObject) -> T): List<T> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let { add(transform(it)) }
    }

    private fun stateFile() = File(context.filesDir, stateFileName)

    private data class FolderDocument(
        val document: DocumentFile,
        val relativePath: String,
    )

    private data class ListedDocument(
        val document: DocumentFile,
        val name: String,
        val mimeType: String,
        val isDirectory: Boolean,
        val isFile: Boolean,
    ) {
        val uri: Uri get() = document.uri

        fun isZipArchive(): Boolean {
            return name.endsWith(".zip", ignoreCase = true) || mimeType.lowercase() in ZIP_MIME_TYPES
        }
    }

    private data class ChildListing(
        val documents: List<ListedDocument>,
        val incompleteReason: String? = null,
    )

    private data class ProviderQuery(
        val documents: List<ListedDocument>,
        val loading: Boolean,
    )

    private companion object {
        val PROVIDER_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        const val PROVIDER_QUERY_ATTEMPTS = 8
        const val PROVIDER_QUERY_WAIT_MILLIS = 750L
        const val STATE_FILE = "reader-folder-source.json"
        const val PREFERENCES_NAME = "reader_content_source"
        const val SELECTED_TREE_URI_KEY = "selected_tree_uri"
        val ZIP_MIME_TYPES = setOf(
            "application/zip",
            "application/x-zip",
            "application/x-zip-compressed",
        )
    }
}