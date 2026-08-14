package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportResult(
    val exportedItems: Int,
    val failedItems: Int,
    val bytesExported: Long,
    val error: String? = null,
)

class DataExportManager(private val context: Context) {
    fun backupToDownloads(entries: List<AuditEntry>): ExportResult {
        if (entries.isEmpty()) return ExportResult(0, 0, 0, "No collected items are available to back up.")
        val folder = "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync Backups/" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var exported = 0
        var failed = 0
        var bytes = 0L
        var firstError: String? = null

        entries.forEach { entry ->
            val source = entry.destination?.let(Uri::parse)
            if (source == null) {
                failed += 1
                firstError = firstError ?: "A collected item has no stored location."
                return@forEach
            }
            val displayName = sanitizeName(entry.sourceItem.substringAfterLast('/'))
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName.ifBlank { "collected-item" })
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType(entry))
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$folder/${entry.category.name.lowercase()}")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = runCatching {
                context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                )
            }.getOrNull()
            if (target == null) {
                failed += 1
                firstError = firstError ?: "Could not create the backup destination for $displayName."
                return@forEach
            }
            runCatching { copy(source, target) }
                .onSuccess {
                    context.contentResolver.update(
                        target,
                        android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        },
                        null,
                        null,
                    )
                    exported += 1
                    bytes += it
                }
                .onFailure { throwable ->
                    context.contentResolver.delete(target, null, null)
                    failed += 1
                    firstError = firstError ?: (throwable.message ?: "Could not back up $displayName.")
                }
        }
        return ExportResult(exported, failed, bytes, firstError)
    }

    fun export(entries: List<AuditEntry>, destinationTree: Uri): ExportResult {
        val root = DocumentFile.fromTreeUri(context, destinationTree)
            ?: return ExportResult(0, entries.size, 0, "The selected destination is unavailable.")
        if (!root.canWrite()) {
            return ExportResult(0, entries.size, 0, "The selected destination is not writable.")
        }

        val folderName = "Phone Sync Export " +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val exportFolder = root.createDirectory(folderName)
            ?: return ExportResult(0, entries.size, 0, "Could not create the export folder.")

        var exported = 0
        var failed = 0
        var bytes = 0L
        var firstError: String? = null
        entries.forEach { entry ->
            val destination = entry.destination?.let(Uri::parse)
            val source = destination?.let { DocumentFile.fromSingleUri(context, it) }
            if (source == null || !source.canRead()) {
                failed += 1
                firstError = firstError ?: "An imported item is no longer available."
                return@forEach
            }

            val displayName = uniqueName(
                exportFolder,
                sanitizeName(source.name ?: entry.sourceItem.substringAfterLast('/')),
            )
            val target = exportFolder.createFile(
                source.type ?: mimeType(entry),
                displayName,
            )
            if (target == null) {
                failed += 1
                firstError = firstError ?: "Could not create $displayName."
                return@forEach
            }

            runCatching {
                copy(destination!!, target.uri)
            }.onSuccess { copied ->
                exported += 1
                bytes += copied
            }.onFailure { throwable ->
                context.contentResolver.delete(target.uri, null, null)
                failed += 1
                firstError = firstError ?: (throwable.message ?: "Could not export $displayName.")
            }
        }
        return ExportResult(exported, failed, bytes, firstError)
    }

    fun mimeType(entry: AuditEntry): String {
        if (entry.category == ConsentCategory.CONTACTS) return "text/vcard"
        val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun copy(source: Uri, target: Uri): Long {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Could not read the imported item.")
        val output = context.contentResolver.openOutputStream(target, "w")
            ?: error("Could not open the export destination.")
        return input.use { sourceStream ->
            output.use { targetStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = sourceStream.read(buffer)
                    if (count < 0) break
                    targetStream.write(buffer, 0, count)
                    total += count
                }
                total
            }
        }
    }

    private fun uniqueName(folder: DocumentFile, baseName: String): String {
        val safeBase = baseName.ifBlank { "imported-item" }
        val extension = safeBase.substringAfterLast('.', missingDelimiterValue = "")
        val stem = if (extension.isBlank()) safeBase else safeBase.removeSuffix(".$extension")
        var candidate = safeBase
        var index = 2
        while (folder.findFile(candidate) != null) {
            candidate = if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension"
            index += 1
        }
        return candidate
    }

    private fun sanitizeName(value: String): String {
        return value.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180)
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
    }
}