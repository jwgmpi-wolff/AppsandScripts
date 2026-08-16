package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.net.Uri
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

class ExternalFolderSourceManager(private val context: Context) {
    fun selectedTreeUri(): Uri? {
        val saved = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
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
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
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
            ?: return failure(treeUri, "Android could not open the selected external-storage folder.")
        return scanRoot(treeUri, root, onProgress)
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
        pendingDirectories.add(FolderDocument(root, ""))

        while (pendingDirectories.isNotEmpty()) {
            val current = pendingDirectories.removeFirst()
            if (!visitedDirectories.add(current.document.uri.toString())) continue
            val children = runCatching { current.document.listFiles().toList() }
                .getOrElse { throwable ->
                    issues += issue(
                        current.relativePath.ifBlank { sourceName },
                        "Restore read permission for this directory and tap Refresh folder. ${throwable.message.orEmpty()}",
                    )
                    emptyList()
                }
                .sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.uri.toString() }))
            children.forEachIndexed { index, child ->
                val childName = child.name?.takeIf(String::isNotBlank) ?: "unnamed-${index + 1}"
                val childPath = listOf(current.relativePath, childName)
                    .filter(String::isNotBlank)
                    .joinToString("/")
                when {
                    child.isDirectory -> pendingDirectories.add(FolderDocument(child, childPath))
                    child.isFile -> files += FolderDocument(child, childPath)
                    else -> issues += issue(
                        childPath,
                        "This document is neither a readable file nor directory. Restore provider access and tap Refresh folder.",
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
                    "Restore access to this file and tap Refresh folder. ${readError.message.orEmpty()}",
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
                        "The verified read is available; tap Refresh folder after uploads finish to capture the final version.",
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
                "No readable files were found. Upload files into the selected folder, then tap Refresh folder."
            } else {
                null
            },
        )
        save(result)
        return result
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

    private fun stateFile() = File(context.filesDir, STATE_FILE)

    private data class FolderDocument(
        val document: DocumentFile,
        val relativePath: String,
    )

    private companion object {
        const val STATE_FILE = "reader-folder-source.json"
        const val PREFERENCES_NAME = "reader_content_source"
        const val SELECTED_TREE_URI_KEY = "selected_tree_uri"
    }
}