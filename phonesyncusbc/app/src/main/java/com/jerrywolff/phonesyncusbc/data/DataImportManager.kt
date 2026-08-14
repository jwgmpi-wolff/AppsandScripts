package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import java.security.MessageDigest

data class ImportResult(
    val importedItems: Int,
    val failedItems: Int,
    val bytesImported: Long,
    val error: String? = null,
)

class DataImportManager(private val context: Context) {
    fun importExportedFiles(
        peerId: String,
        sourceName: String,
        files: List<Uri>,
        auditLog: AuditLog,
        forcedCategory: ConsentCategory? = null,
    ): ImportResult {
        val sessionId = auditLog.beginSession(peerId)
        var imported = 0
        var failed = 0
        var bytes = 0L
        var firstError: String? = null

        files.forEach { sourceUri ->
            val source = DocumentFile.fromSingleUri(context, sourceUri)
            val name = source?.name ?: "exported-item"
            val category = forcedCategory ?: TransferClassifier.classify(name)
            val size = source?.length()?.coerceAtLeast(0) ?: 0
            val fingerprint = fingerprint(peerId, sourceUri, name, size)
            if (auditLog.wasTransferred(peerId, fingerprint)) return@forEach
            runCatching {
                copyToDownloads(sourceUri, sourceName, name, category, source?.type)
            }.onSuccess { destination ->
                imported += 1
                bytes += size
                auditLog.recordTransfer(
                    sessionId = sessionId,
                    peerId = peerId,
                    sourceFingerprint = fingerprint,
                    category = category,
                    sourceItem = name,
                    destination = destination.toString(),
                    bytesTransferred = size,
                    status = TransferStatus.COMPLETED,
                )
            }.onFailure { throwable ->
                failed += 1
                firstError = firstError ?: (throwable.message ?: "Could not import $name.")
                auditLog.recordTransfer(
                    sessionId = sessionId,
                    peerId = peerId,
                    sourceFingerprint = fingerprint,
                    category = category,
                    sourceItem = name,
                    destination = null,
                    bytesTransferred = 0,
                    status = TransferStatus.FAILED,
                    error = throwable.message,
                )
            }
        }

        val status = when {
            failed == 0 -> SyncStatus.COMPLETED
            imported > 0 -> SyncStatus.PARTIAL
            else -> SyncStatus.FAILED
        }
        auditLog.finishSession(sessionId, status, imported, bytes, firstError)
        return ImportResult(imported, failed, bytes, firstError)
    }

    private fun copyToDownloads(
        sourceUri: Uri,
        sourceName: String,
        displayName: String,
        category: ConsentCategory,
        sourceMimeType: String?,
    ): Uri {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_"))
            put(MediaStore.MediaColumns.MIME_TYPE, sourceMimeType ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Phone Sync/$sourceName/${category.name.lowercase()}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android could not create the imported export item.")
        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: error("The selected export could not be read.")
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: error("Android could not open the imported export item.")
            input.use { source -> output.use { target -> source.copyTo(target) } }
            context.contentResolver.update(
                destination,
                android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return destination
        } catch (throwable: Throwable) {
            context.contentResolver.delete(destination, null, null)
            throw throwable
        }
    }

    private fun fingerprint(peerId: String, uri: Uri, name: String, size: Long): String {
        return MessageDigest.getInstance("SHA-256")
            .digest("$peerId|$uri|$name|$size".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}