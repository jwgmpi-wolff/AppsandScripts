package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.net.Uri
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.RecoveryIssue
import com.jerrywolff.phonesyncusbc.data.RecoveryIssueReason
import com.jerrywolff.phonesyncusbc.data.TransferStatus
import com.jerrywolff.phonesyncusbc.data.isCollectorOwnedSourceItem
import com.jerrywolff.phonesyncusbc.data.isExternalSourcePeer
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class ArchiveImportProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentItem: String,
    val bytesRead: Long,
)

data class ArchiveImportResult(
    val sourceId: String,
    val sourceName: String,
    val entries: List<AuditEntry>,
    val issues: List<RecoveryIssue>,
    val error: String? = null,
)

class ArchiveImporter(private val context: Context) {
    fun import(
        archiveUri: Uri,
        onProgress: (ArchiveImportProgress) -> Unit = {},
    ): ArchiveImportResult {
        val manifest = readManifest(archiveUri)
        val sourceId = manifest.optString("externalPeerId").trim()
        require(isExternalSourcePeer(sourceId)) {
            "The archive does not identify a valid external source device."
        }
        val sourceName = "External source ${sourceId.take(12)}"
        val issues = mutableListOf<RecoveryIssue>()
        val declared = parseManifestEntries(manifest.optJSONArray("entries") ?: JSONArray(), sourceId, issues)
        require(declared.isNotEmpty()) {
            "The archive contains no eligible external-source items. Review its provenance manifest."
        }

        val staging = File(context.filesDir, "reader-import-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val extracted = linkedMapOf<String, ExtractedArtifact>()
        var totalBytes = 0L
        try {
            context.contentResolver.openInputStream(archiveUri).use { input ->
                checkNotNull(input) { "Android could not open the selected archive." }
                ZipInputStream(input.buffered()).use { archive ->
                    while (true) {
                        val zipEntry = archive.nextEntry ?: break
                        val archivePath = normalizedArchivePath(zipEntry.name)
                        val item = declared[archivePath]
                        if (!zipEntry.isDirectory && item != null && archivePath !in extracted) {
                            val localName = localArtifactName(item)
                            val outputFile = File(staging, localName)
                            val digest = MessageDigest.getInstance("SHA-256")
                            var itemBytes = 0L
                            outputFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = archive.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    itemBytes += count
                                    check(item.expectedBytes < 0 || itemBytes <= item.expectedBytes) {
                                        "${item.sourceItem} exceeds its manifest size."
                                    }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    onProgress(
                                        ArchiveImportProgress(
                                            extracted.size,
                                            declared.size,
                                            item.sourceItem,
                                            totalBytes + itemBytes,
                                        ),
                                    )
                                }
                            }
                            val sha256 = digest.digest().toHex()
                            val valid = (item.expectedBytes < 0 || itemBytes == item.expectedBytes) &&
                                sha256.equals(item.sha256, ignoreCase = true)
                            if (valid) {
                                extracted[archivePath] = ExtractedArtifact(item, localName, itemBytes, sha256)
                                totalBytes += itemBytes
                                onProgress(
                                    ArchiveImportProgress(
                                        extracted.size,
                                        declared.size,
                                        item.sourceItem,
                                        totalBytes,
                                    ),
                                )
                            } else {
                                outputFile.delete()
                                issues += recoveryIssue(
                                    item.sourceItem,
                                    RecoveryIssueReason.COPY_FAILED,
                                    "Recreate the Phone Sync backup archive and import it again; size or SHA-256 verification failed.",
                                )
                            }
                        }
                        archive.closeEntry()
                    }
                }
            }
            declared.values
                .filter { it.archivePath !in extracted }
                .forEach { item ->
                    issues += recoveryIssue(
                        item.sourceItem,
                        RecoveryIssueReason.MISSING_RECOVERED_COPY,
                        "Recreate the backup archive; its manifest declares this item but the ZIP does not contain it.",
                    )
                }
            check(extracted.isNotEmpty()) { "No archive item passed provenance and integrity verification." }

            val activeDirectory = activate(staging)
            val entries = extracted.values.map { artifact ->
                artifact.toAuditEntry(sourceId, activeDirectory)
            }
            val result = ArchiveImportResult(sourceId, sourceName, entries, issues)
            save(result)
            return result
        } catch (throwable: Throwable) {
            staging.deleteRecursively()
            return ArchiveImportResult(
                sourceId = sourceId,
                sourceName = sourceName,
                entries = emptyList(),
                issues = issues,
                error = throwable.message ?: throwable.javaClass.simpleName,
            )
        }
    }

    fun load(): ArchiveImportResult? {
        val stateFile = File(context.filesDir, STATE_FILE)
        if (!stateFile.isFile) return null
        return runCatching {
            val root = JSONObject(stateFile.readText())
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
            }.filter { entry ->
                entry.destination?.let(Uri::parse)?.path?.let(::File)?.isFile == true
            }
            if (entries.isEmpty()) return null
            val issues = root.optJSONArray("issues")?.toObjects { item ->
                RecoveryIssue(
                    sourceItem = item.getString("sourceItem"),
                    reason = RecoveryIssueReason.valueOf(item.getString("reason")),
                    remediation = item.getString("remediation"),
                    retryable = item.getBoolean("retryable"),
                )
            }.orEmpty()
            ArchiveImportResult(sourceId, sourceName, entries, issues)
        }.getOrNull()
    }

    private fun readManifest(archiveUri: Uri): JSONObject {
        context.contentResolver.openInputStream(archiveUri).use { input ->
            checkNotNull(input) { "Android could not open the selected archive." }
            ZipInputStream(input.buffered()).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (!entry.isDirectory && normalizedArchivePath(entry.name) == MANIFEST_PATH) {
                        val bytes = readLimited(archive, MAX_MANIFEST_BYTES)
                        return JSONObject(String(bytes, Charsets.UTF_8))
                    }
                    archive.closeEntry()
                }
            }
        }
        error("backup-manifest.json was not found. Select a Phone Sync backup ZIP.")
    }

    private fun parseManifestEntries(
        entries: JSONArray,
        expectedPeerId: String,
        issues: MutableList<RecoveryIssue>,
    ): Map<String, ManifestArtifact> {
        val accepted = linkedMapOf<String, ManifestArtifact>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val sourceItem = item.optString("sourceItem").ifBlank { "Manifest item ${index + 1}" }
            val peerId = item.optString("peerId").trim()
            val fingerprint = item.optString("sourceFingerprint").trim()
            val archivePath = runCatching { normalizedArchivePath(item.getString("archivePath")) }.getOrNull()
            val category = runCatching { ConsentCategory.valueOf(item.getString("category")) }.getOrNull()
            val issue = when {
                isCollectorOwnedSourceItem(sourceItem) || peerId == "local-android" -> recoveryIssue(
                    sourceItem,
                    RecoveryIssueReason.COLLECTOR_ORIGIN,
                    "Reacquire this item from its external source before importing it into the external-device reader.",
                )
                peerId.isBlank() -> recoveryIssue(
                    sourceItem,
                    RecoveryIssueReason.MISSING_SOURCE_PEER,
                    "Recreate the archive from a selected external source so the item receives a peer identity.",
                )
                peerId != expectedPeerId -> recoveryIssue(
                    sourceItem,
                    RecoveryIssueReason.DIFFERENT_SOURCE_PEER,
                    "Import this item in a separate archive while its owning external source is selected.",
                    retryable = false,
                )
                fingerprint.isBlank() -> recoveryIssue(
                    sourceItem,
                    RecoveryIssueReason.MISSING_SOURCE_FINGERPRINT,
                    "Reacquire and archive this item so its source fingerprint is recorded.",
                )
                archivePath == null || category == null || item.optString("sha256").isBlank() -> recoveryIssue(
                    sourceItem,
                    RecoveryIssueReason.MISSING_RECOVERED_COPY,
                    "Recreate the archive; this manifest entry is incomplete.",
                )
                archivePath in accepted -> recoveryIssue(
                    sourceItem,
                    RecoveryIssueReason.COPY_FAILED,
                    "Recreate the archive; duplicate ZIP paths cannot be verified independently.",
                )
                else -> null
            }
            if (issue != null) {
                issues += issue
                continue
            }
            accepted[archivePath!!] = ManifestArtifact(
                archivePath = archivePath,
                category = category!!,
                sourceItem = sourceItem,
                sourceFingerprint = fingerprint,
                sourceSize = item.optLong("sourceSize", item.optLong("bytes", -1)),
                sourceModifiedAtEpochMillis = item.optLong("sourceModifiedAtEpochMillis", 0),
                recoveredAtEpochMillis = item.optLong("recoveredAtEpochMillis", System.currentTimeMillis()),
                expectedBytes = item.optLong("bytes", -1),
                sha256 = item.getString("sha256"),
            )
        }
        return accepted
    }

    private fun activate(staging: File): File {
        val active = File(context.filesDir, ACTIVE_DIRECTORY)
        val previous = File(context.filesDir, "$ACTIVE_DIRECTORY-previous")
        previous.deleteRecursively()
        if (active.exists()) check(active.renameTo(previous)) { "Could not rotate the previous reader import." }
        if (!staging.renameTo(active)) {
            previous.renameTo(active)
            error("Could not activate the verified reader import.")
        }
        previous.deleteRecursively()
        return active
    }

    private fun save(result: ArchiveImportResult) {
        val root = JSONObject()
            .put("sourceId", result.sourceId)
            .put("sourceName", result.sourceName)
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
                result.issues.forEach { issue ->
                    put(
                        JSONObject()
                            .put("sourceItem", issue.sourceItem)
                            .put("reason", issue.reason.name)
                            .put("remediation", issue.remediation)
                            .put("retryable", issue.retryable),
                    )
                }
            })
        File(context.filesDir, STATE_FILE).writeText(root.toString())
    }

    private data class ManifestArtifact(
        val archivePath: String,
        val category: ConsentCategory,
        val sourceItem: String,
        val sourceFingerprint: String,
        val sourceSize: Long,
        val sourceModifiedAtEpochMillis: Long,
        val recoveredAtEpochMillis: Long,
        val expectedBytes: Long,
        val sha256: String,
    )

    private data class ExtractedArtifact(
        val manifest: ManifestArtifact,
        val localName: String,
        val bytes: Long,
        val sha256: String,
    ) {
        fun toAuditEntry(sourceId: String, activeDirectory: File): AuditEntry {
            return AuditEntry(
                id = stableArchiveTransferId("$sourceId|${manifest.sourceFingerprint}|${manifest.archivePath}"),
                transferredAtEpochMillis = manifest.recoveredAtEpochMillis,
                category = manifest.category,
                sourceItem = manifest.sourceItem,
                destination = Uri.fromFile(File(activeDirectory, localName)).toString(),
                bytesTransferred = bytes,
                status = TransferStatus.COMPLETED,
                error = null,
                sourceSize = manifest.sourceSize.takeIf { it >= 0 } ?: bytes,
                sourceModifiedAtEpochMillis = manifest.sourceModifiedAtEpochMillis,
                contentSha256 = sha256,
                peerId = sourceId,
                sourceFingerprint = manifest.sourceFingerprint,
            )
        }
    }

    private fun localArtifactName(item: ManifestArtifact): String {
        val extension = item.archivePath.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
            ?.let { ".$it" }
            .orEmpty()
        return MessageDigest.getInstance("SHA-256")
            .digest(item.archivePath.toByteArray(Charsets.UTF_8))
            .toHex()
            .take(32) + extension
    }

    private fun recoveryIssue(
        sourceItem: String,
        reason: RecoveryIssueReason,
        remediation: String,
        retryable: Boolean = true,
    ) = RecoveryIssue(sourceItem, reason, remediation, retryable)

    private fun normalizedArchivePath(path: String): String {
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Absolute ZIP paths are not allowed." }
        val normalized = path.replace('\\', '/').trim('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it == ".." }) {
            "Unsafe ZIP path: $path"
        }
        return normalized
    }

    private fun readLimited(input: java.io.InputStream, maximumBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximumBytes) { "The archive manifest exceeds $maximumBytes bytes." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun <T> JSONArray.toObjects(transform: (JSONObject) -> T): List<T> {
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let { add(transform(it)) }
        }
    }

    private companion object {
        const val MANIFEST_PATH = "backup-manifest.json"
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val ACTIVE_DIRECTORY = "reader-import"
        const val STATE_FILE = "reader-import.json"
    }
}

private fun stableArchiveTransferId(material: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    var value = 0L
    for (index in 0 until 8) value = (value shl 8) or (digest[index].toLong() and 0xff)
    return (value and Long.MAX_VALUE).coerceAtLeast(1)
}