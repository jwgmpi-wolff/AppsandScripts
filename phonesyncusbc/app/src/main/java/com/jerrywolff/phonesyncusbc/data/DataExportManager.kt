package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportResult(
    val exportedItems: Int,
    val failedItems: Int,
    val bytesExported: Long,
    val error: String? = null,
)

data class ExportProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentItem: String,
    val bytesExported: Long,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
)

data class ArchiveProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentItem: String,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
    val sourceBytesArchived: Long = 0,
)

data class ArchiveResult(
    val uri: Uri? = null,
    val displayName: String? = null,
    val archivedItems: Int = 0,
    val sourceBytes: Long = 0,
    val archiveBytes: Long = 0,
    val error: String? = null,
)

class DataExportManager(private val context: Context) {
    fun cleanupInterruptedUploadArchives(): Int {
        return cleanupUploadArchives(pendingOnly = true)
    }

    fun cleanupObsoleteUploadArchives(): Int {
        return cleanupUploadArchives(pendingOnly = false)
    }

    private fun cleanupUploadArchives(pendingOnly: Boolean): Int {
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = buildString {
            append("${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ")
            append("${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            if (pendingOnly) {
                append(" AND ${android.provider.MediaStore.MediaColumns.IS_PENDING} = 1")
            }
        }
        val ids = buildList {
            context.contentResolver.query(
                collection,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                selection,
                arrayOf(
                    "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync Uploads%",
                    "PhoneSyncBackup-%",
                ),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        return ids.count { id ->
            context.contentResolver.delete(
                android.content.ContentUris.withAppendedId(collection, id),
                null,
                null,
            ) > 0
        }
    }

    fun backupToDownloads(
        entries: List<AuditEntry>,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        if (entries.isEmpty()) return ExportResult(0, 0, 0, "No recovered artifacts are available to preserve.")
        val folder = "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync Backups/" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var exported = 0
        var failed = 0
        var bytes = 0L
        var processed = 0
        var firstError: String? = null

        entries.forEach { entry ->
            val displayName = sanitizeName(entry.sourceItem.substringAfterLast('/'))
                .ifBlank { "recovered-artifact" }
            onProgress(ExportProgress(processed, entries.size, displayName, bytes))
            val source = entry.destination?.let(Uri::parse)
            if (source == null) {
                failed += 1
                processed += 1
                firstError = firstError ?: "A recovered artifact has no stored location."
                onProgress(ExportProgress(processed, entries.size, displayName, bytes))
                return@forEach
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
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
                processed += 1
                firstError = firstError ?: "Could not create the backup destination for $displayName."
                onProgress(ExportProgress(processed, entries.size, displayName, bytes))
                return@forEach
            }
            val itemTotal = sourceSize(source)
            var itemBytes = 0L
            runCatching {
                copy(source, target) { copied ->
                    itemBytes = copied
                    onProgress(
                        ExportProgress(
                            processed,
                            entries.size,
                            displayName,
                            bytes + copied,
                            copied,
                            itemTotal,
                        ),
                    )
                }
            }
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
            processed += 1
            onProgress(
                ExportProgress(processed, entries.size, displayName, bytes, itemBytes, itemTotal),
            )
        }
        return ExportResult(exported, failed, bytes, firstError)
    }

    fun export(
        entries: List<AuditEntry>,
        destinationTree: Uri,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
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
        var processed = 0
        var firstError: String? = null
        entries.forEach { entry ->
            val source = entry.destination?.let(Uri::parse)
            val sourceName = source?.let { sourceDisplayName(it, entry) }
                ?: entry.sourceItem.substringAfterLast('/').ifBlank { "recovered-artifact" }
            onProgress(ExportProgress(processed, entries.size, sourceName, bytes))
            if (source == null) {
                failed += 1
                processed += 1
                firstError = firstError ?: "A recovered artifact has no stored location."
                onProgress(ExportProgress(processed, entries.size, sourceName, bytes))
                return@forEach
            }

            val displayName = uniqueName(
                exportFolder,
                sanitizeName(sourceName),
            )
            val target = exportFolder.createFile(
                context.contentResolver.getType(source) ?: mimeType(entry),
                displayName,
            )
            if (target == null) {
                failed += 1
                processed += 1
                firstError = firstError ?: "Could not create $displayName."
                onProgress(ExportProgress(processed, entries.size, displayName, bytes))
                return@forEach
            }

            val itemTotal = sourceSize(source)
            var itemBytes = 0L
            runCatching {
                copy(source, target.uri) { copied ->
                    itemBytes = copied
                    onProgress(
                        ExportProgress(
                            processed,
                            entries.size,
                            displayName,
                            bytes + copied,
                            copied,
                            itemTotal,
                        ),
                    )
                }
            }.onSuccess { copied ->
                exported += 1
                bytes += copied
            }.onFailure { throwable ->
                context.contentResolver.delete(target.uri, null, null)
                failed += 1
                firstError = firstError ?: (throwable.message ?: "Could not export $displayName.")
            }
            processed += 1
            onProgress(
                ExportProgress(processed, entries.size, displayName, bytes, itemBytes, itemTotal),
            )
        }
        return ExportResult(exported, failed, bytes, firstError)
    }

    fun createUploadArchive(
        entries: List<AuditEntry>,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): ArchiveResult {
        if (entries.isEmpty()) return ArchiveResult(error = "No recovered artifacts are selected for preservation.")

        val displayName = "PhoneSyncBackup-${timestamp()}.zip"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync Uploads",
            )
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = context.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return ArchiveResult(error = "Android could not create the upload archive.")

        return try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: error("Android could not open the upload archive.")
            val countingOutput = CountingOutputStream(output)
            val manifestEntries = JSONArray()
            val usedPaths = mutableSetOf<String>()
            var sourceBytes = 0L

            ZipOutputStream(countingOutput).use { archive ->
                archive.setLevel(Deflater.NO_COMPRESSION)
                entries.forEachIndexed { index, entry ->
                    val source = entry.destination?.let(Uri::parse)
                        ?: error("${entry.sourceItem} has no stored location.")
                    val sourceName = sanitizeName(sourceDisplayName(source, entry))
                        .ifBlank { "recovered-artifact" }
                    val archivePath = uniqueArchivePath(
                        usedPaths,
                        "${entry.category.name.lowercase()}/$sourceName",
                    )
                    val itemTotal = sourceSize(source)
                    onProgress(
                        ArchiveProgress(
                            index,
                            entries.size,
                            sourceName,
                            currentItemTotal = itemTotal,
                            sourceBytesArchived = sourceBytes,
                        ),
                    )
                    archive.putNextEntry(
                        ZipEntry(archivePath).apply {
                            time = entry.transferredAtEpochMillis
                        },
                    )
                    val digest = MessageDigest.getInstance("SHA-256")
                    val input = context.contentResolver.openInputStream(source)
                        ?: error("Could not read $sourceName.")
                    val copied = input.use { sourceStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        var lastReported = 0L
                        while (true) {
                            val count = sourceStream.read(buffer)
                            if (count < 0) break
                            archive.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            total += count
                            if (total - lastReported >= PROGRESS_REPORT_BYTES) {
                                onProgress(
                                    ArchiveProgress(
                                        index,
                                        entries.size,
                                        sourceName,
                                        total,
                                        itemTotal,
                                        sourceBytes + total,
                                    ),
                                )
                                lastReported = total
                            }
                        }
                        total
                    }
                    archive.closeEntry()
                    val archiveSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    check(entry.bytesTransferred <= 0 || copied == entry.bytesTransferred) {
                        "Recovered artifact size changed before preservation: $sourceName."
                    }
                    check(entry.contentSha256 == null || archiveSha256.equals(entry.contentSha256, ignoreCase = true)) {
                        "Recovered artifact SHA-256 changed before preservation: $sourceName."
                    }
                    sourceBytes += copied
                    manifestEntries.put(
                        JSONObject()
                            .put("category", entry.category.name)
                            .put("sourceItem", entry.sourceItem)
                            .put("sourceSize", entry.sourceSize)
                            .put("sourceModifiedAtEpochMillis", entry.sourceModifiedAtEpochMillis)
                            .put("recoveredAtEpochMillis", entry.transferredAtEpochMillis)
                            .put("archivePath", archivePath)
                            .put("bytes", copied)
                            .put("sha256", archiveSha256)
                            .put("recoveryContentSha256", entry.contentSha256 ?: JSONObject.NULL)
                            .put("sensitive", entry.category == ConsentCategory.PASSWORD_EXPORTS),
                    )
                    onProgress(
                        ArchiveProgress(
                            index + 1,
                            entries.size,
                            sourceName,
                            copied,
                            itemTotal,
                            sourceBytes,
                        ),
                    )
                }

                val manifest = JSONObject()
                    .put("createdAtEpochMillis", System.currentTimeMillis())
                    .put("itemCount", entries.size)
                    .put("sourceBytes", sourceBytes)
                    .put("entries", manifestEntries)
                archive.putNextEntry(ZipEntry("backup-manifest.json"))
                archive.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
            context.contentResolver.update(
                destination,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            ArchiveResult(
                uri = destination,
                displayName = displayName,
                archivedItems = entries.size,
                sourceBytes = sourceBytes,
                archiveBytes = countingOutput.bytesWritten,
            )
        } catch (throwable: Throwable) {
            context.contentResolver.delete(destination, null, null)
            ArchiveResult(error = throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun sourceDisplayName(source: Uri, entry: AuditEntry): String {
        val queriedName = runCatching {
            context.contentResolver.query(
                source,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queriedName
            ?.takeIf { it.isNotBlank() }
            ?: entry.sourceItem.substringAfterLast('/').ifBlank { "recovered-artifact" }
    }

    fun mimeType(entry: AuditEntry): String {
        if (entry.category == ConsentCategory.CONTACTS) return "text/vcard"
        if (entry.category == ConsentCategory.PASSWORD_EXPORTS) return "application/octet-stream"
        val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun sourceSize(source: Uri): Long {
        return runCatching {
            context.contentResolver.query(
                source,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).coerceAtLeast(0) else 0
            } ?: 0
        }.getOrDefault(0)
    }

    private fun copy(
        source: Uri,
        target: Uri,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Could not read the recovered artifact.")
        val output = context.contentResolver.openOutputStream(target, "w")
            ?: error("Could not open the export destination.")
        return input.use { sourceStream ->
            output.use { targetStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                var lastReported = 0L
                while (true) {
                    val count = sourceStream.read(buffer)
                    if (count < 0) break
                    targetStream.write(buffer, 0, count)
                    total += count
                    if (total - lastReported >= PROGRESS_REPORT_BYTES) {
                        onProgress(total)
                        lastReported = total
                    }
                }
                if (total != lastReported) onProgress(total)
                total
            }
        }
    }

    private fun uniqueName(folder: DocumentFile, baseName: String): String {
        val safeBase = baseName.ifBlank { "recovered-artifact" }
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

    private fun uniqueArchivePath(usedPaths: MutableSet<String>, basePath: String): String {
        val extension = basePath.substringAfterLast('.', missingDelimiterValue = "")
        val stem = if (extension.isBlank()) basePath else basePath.removeSuffix(".$extension")
        var candidate = basePath
        var index = 2
        while (!usedPaths.add(candidate)) {
            candidate = if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension"
            index += 1
        }
        return candidate
    }

    private fun sanitizeName(value: String): String {
        return value.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var bytesWritten: Long = 0
            private set

        override fun write(value: Int) {
            out.write(value)
            bytesWritten += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_REPORT_BYTES = 1024 * 1024L
    }
}