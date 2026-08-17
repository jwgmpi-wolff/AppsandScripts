package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
    val archiveUri: Uri? = null,
    val verifiedItemCount: Int = entries.size,
)

class ArchiveImporter(
    private val context: Context,
    private val stateFileName: String = STATE_FILE,
    private val activeDirectoryName: String = ACTIVE_DIRECTORY,
    private val preferencesName: String = PREFERENCES_NAME,
) {
    fun selectedArchiveUri(): Uri? {
        val saved = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(SELECTED_ARCHIVE_URI_KEY, null)
        if (!saved.isNullOrBlank()) return Uri.parse(saved)
        val state = stateFile()
        return if (state.isFile) {
            runCatching {
                JSONObject(state.readText()).optString("archiveUri")
                    .takeIf(String::isNotBlank)
                    ?.let(Uri::parse)
            }.getOrNull()
        } else {
            null
        }
    }

    fun rememberArchiveUri(archiveUri: Uri) {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(SELECTED_ARCHIVE_URI_KEY, archiveUri.toString())
            .apply()
    }

    fun hasPersistedReadAccess(archiveUri: Uri): Boolean {
        if (archiveUri.scheme == "file") return true
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == archiveUri && permission.isReadPermission
        }
    }

    fun import(
        archiveUri: Uri,
        onProgress: (ArchiveImportProgress) -> Unit = {},
    ): ArchiveImportResult {
        val manifestDocument = readManifest(archiveUri)
        if (manifestDocument.path == RECOVER_BY_BACKUP_MANIFEST_PATH) {
            return importRecoverByBackup(archiveUri, manifestDocument.content, onProgress)
        }
        val manifest = manifestDocument.content
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

        val staging = File(context.filesDir, "$activeDirectoryName-staging").apply {
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
            val result = ArchiveImportResult(
                sourceId = sourceId,
                sourceName = sourceName,
                entries = entries,
                issues = issues,
                archiveUri = archiveUri,
            )
            rememberArchiveUri(archiveUri)
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
                archiveUri = archiveUri,
            )
        }
    }

    private fun importRecoverByBackup(
        archiveUri: Uri,
        manifest: JSONObject,
        onProgress: (ArchiveImportProgress) -> Unit,
    ): ArchiveImportResult {
        require(manifest.optString("format") == RECOVER_BY_BACKUP_FORMAT) {
            "The RecoverByBackup manifest has an unsupported format."
        }
        require(manifest.optInt("schemaVersion", 0) == RECOVER_BY_BACKUP_SCHEMA_VERSION) {
            "The RecoverByBackup manifest schema is not supported."
        }
        val sourceId = manifest.optString("externalPeerId").trim()
        require(isExternalSourcePeer(sourceId)) {
            "The RecoverByBackup archive does not identify an external backup source."
        }
        val sourceName = manifest.optString("sourceName").trim().ifBlank {
            "RecoverByBackup ${sourceId.takeLast(12)}"
        }
        val manifestEntries = manifest.optJSONArray("entries") ?: JSONArray()
        val declaredItemCount = manifest.optInt("itemCount", -1)
        require(declaredItemCount in 1..MAX_RECOVER_BY_BACKUP_ITEMS) {
            "The RecoverByBackup archive declares an unsupported file count."
        }
        require(manifestEntries.length() == declaredItemCount) {
            "The RecoverByBackup archive item count does not match its manifest."
        }
        val coverage = manifest.optJSONObject("coverage")
            ?: error("The RecoverByBackup archive has no coverage declaration.")
        require(coverage.optString("basis") == "OWNER_SUPPLIED_FILES_ONLY") {
            "The RecoverByBackup coverage basis is not supported."
        }
        require(!coverage.optBoolean("completeDeviceImage", true)) {
            "RecoverByBackup archives cannot claim to be complete physical device images."
        }
        require(!coverage.optBoolean("protectedDataBypassAttempted", true)) {
            "RecoverByBackup archives must not claim protected-data bypass."
        }
        val sourceRoots = manifest.optJSONArray("sourceRoots")
            ?: error("The RecoverByBackup archive has no source-root declaration.")
        val sourceRootNames = buildSet {
            for (index in 0 until sourceRoots.length()) {
                val name = sourceRoots.optJSONObject(index)?.optString("name").orEmpty()
                require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "..") {
                    "The RecoverByBackup source-root declaration is invalid."
                }
                require(add(name)) { "The RecoverByBackup source-root declaration contains duplicates." }
            }
        }
        require(sourceRootNames.isNotEmpty()) { "The RecoverByBackup archive declares no source roots." }
        val issues = mutableListOf<RecoveryIssue>()
        val declared = parseManifestEntries(manifestEntries, sourceId, issues)
        require(declared.isNotEmpty()) { "The RecoverByBackup archive manifest contains no files." }
        require(issues.isEmpty()) {
            "The RecoverByBackup manifest contains ${issues.size} invalid item(s); recreate the backup."
        }
        require(declared.size == declaredItemCount) {
            "The RecoverByBackup archive contains invalid or duplicate manifest paths."
        }
        require(declared.keys.all { path -> sourceRootNames.any { root -> path.startsWith("payload/$root/") } }) {
            "A RecoverByBackup file is not associated with a declared source root."
        }

        val verifiedPaths = linkedSetOf<String>()
        var verifiedBytes = 0L
        context.contentResolver.openInputStream(archiveUri).use { input ->
            checkNotNull(input) { "Android could not open the RecoverByBackup archive." }
            ZipInputStream(input.buffered()).use { archive ->
                while (true) {
                    val zipEntry = archive.nextEntry ?: break
                    val archivePath = normalizedArchivePath(zipEntry.name)
                    if (zipEntry.isDirectory || archivePath == RECOVER_BY_BACKUP_MANIFEST_PATH) {
                        archive.closeEntry()
                        continue
                    }
                    val item = declared[archivePath]
                        ?: error("The RecoverByBackup manifest does not account for $archivePath.")
                    check(verifiedPaths.add(archivePath)) {
                        "The RecoverByBackup archive contains a duplicate path: $archivePath."
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var itemBytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = archive.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        itemBytes += count
                        verifiedBytes += count
                        check(itemBytes <= MAX_RECOVER_BY_BACKUP_ITEM_BYTES) {
                            "$archivePath exceeds the supported per-file verification limit."
                        }
                        check(verifiedBytes <= MAX_RECOVER_BY_BACKUP_TOTAL_BYTES) {
                            "The RecoverByBackup archive exceeds the supported verification limit."
                        }
                        check(item.expectedBytes < 0 || itemBytes <= item.expectedBytes) {
                            "$archivePath exceeds its manifest size."
                        }
                        digest.update(buffer, 0, count)
                        onProgress(
                            ArchiveImportProgress(
                                completedItems = verifiedPaths.size - 1,
                                totalItems = declared.size,
                                currentItem = item.sourceItem,
                                bytesRead = verifiedBytes,
                            ),
                        )
                    }
                    val contentSha256 = digest.digest().toHex()
                    check(item.expectedBytes < 0 || itemBytes == item.expectedBytes) {
                        "$archivePath does not match its manifest size."
                    }
                    check(contentSha256.equals(item.sha256, ignoreCase = true)) {
                        "$archivePath does not match its manifest SHA-256."
                    }
                    onProgress(
                        ArchiveImportProgress(
                            completedItems = verifiedPaths.size,
                            totalItems = declared.size,
                            currentItem = item.sourceItem,
                            bytesRead = verifiedBytes,
                        ),
                    )
                    archive.closeEntry()
                }
            }
        }
        val missingPaths = declared.keys - verifiedPaths
        require(missingPaths.isEmpty()) {
            "The RecoverByBackup archive is missing ${missingPaths.size} declared file(s)."
        }
        val declaredBytes = manifest.optLong("sourceBytes", -1L)
        require(declaredBytes < 0 || declaredBytes == verifiedBytes) {
            "The RecoverByBackup archive total does not match its manifest."
        }

        val archiveName = archiveDisplayName(archiveUri)
        val archiveSize = archiveSize(archiveUri).takeIf { it >= 0 } ?: verifiedBytes
        val createdAt = manifest.optLong("createdAtEpochMillis", System.currentTimeMillis())
        val manifestFingerprint = sha256(
            listOf(
                manifest.optString("format"),
                manifest.optInt("schemaVersion").toString(),
                sourceId,
                sourceName,
                manifest.optString("deviceType"),
                manifest.optLong("createdAtEpochMillis").toString(),
                coverage.toString(),
            ).joinToString("|") + "\n" + declared.values
                .sortedBy(ManifestArtifact::archivePath)
                .joinToString("\n") { item ->
                    "${item.archivePath}|${item.expectedBytes}|${item.sha256.lowercase()}|${item.sourceFingerprint}"
                },
        )
        val sourceFingerprint = sha256("$sourceId|$manifestFingerprint")
        val entry = AuditEntry(
            id = stableArchiveTransferId("recoverbybackup|$sourceFingerprint"),
            transferredAtEpochMillis = createdAt,
            category = ConsentCategory.DOCUMENTS,
            sourceItem = "/RecoverByBackup/$sourceName/$archiveName",
            destination = archiveUri.toString(),
            bytesTransferred = archiveSize,
            status = TransferStatus.COMPLETED,
            error = null,
            sourceSize = archiveSize,
            sourceModifiedAtEpochMillis = createdAt,
            contentSha256 = null,
            peerId = sourceId,
            sourceFingerprint = sourceFingerprint,
        )
        val result = ArchiveImportResult(
            sourceId = sourceId,
            sourceName = sourceName,
            entries = listOf(entry),
            issues = emptyList(),
            archiveUri = archiveUri,
            verifiedItemCount = declared.size,
        )
        rememberArchiveUri(archiveUri)
        save(result)
        return result
    }

    fun load(): ArchiveImportResult? {
        val stateFile = stateFile()
        if (!stateFile.isFile) return null
        return runCatching {
            val root = JSONObject(stateFile.readText())
            val sourceId = root.getString("sourceId")
            val sourceName = root.getString("sourceName")
            val archiveUri = root.optString("archiveUri")
                .takeIf(String::isNotBlank)
                ?.let(Uri::parse)
                ?: selectedArchiveUri()
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
                    contentSha256 = item.optString("contentSha256")
                        .takeIf { value -> value.isNotBlank() && value != "null" },
                    peerId = sourceId,
                    sourceFingerprint = item.getString("sourceFingerprint"),
                )
            }.filter(::destinationAvailable)
            if (entries.isEmpty()) return null
            val issues = root.optJSONArray("issues")?.toObjects { item ->
                RecoveryIssue(
                    sourceItem = item.getString("sourceItem"),
                    reason = RecoveryIssueReason.valueOf(item.getString("reason")),
                    remediation = item.getString("remediation"),
                    retryable = item.getBoolean("retryable"),
                )
            }.orEmpty()
            ArchiveImportResult(
                sourceId = sourceId,
                sourceName = sourceName,
                entries = entries,
                issues = issues,
                archiveUri = archiveUri,
                verifiedItemCount = root.optInt("verifiedItemCount", entries.size),
            )
        }.getOrNull()
    }

    private fun readManifest(archiveUri: Uri): ArchiveManifestDocument {
        var found: ArchiveManifestDocument? = null
        context.contentResolver.openInputStream(archiveUri).use { input ->
            checkNotNull(input) { "Android could not open the selected archive." }
            ZipInputStream(input.buffered()).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val path = normalizedArchivePath(entry.name)
                    if (!entry.isDirectory && path in SUPPORTED_MANIFEST_PATHS) {
                        require(found == null) {
                            "The archive contains multiple supported backup manifests."
                        }
                        val bytes = readLimited(archive, MAX_MANIFEST_BYTES)
                        found = ArchiveManifestDocument(path, JSONObject(String(bytes, Charsets.UTF_8)))
                    }
                    archive.closeEntry()
                }
            }
        }
        return found ?: error(
            "No supported backup manifest was found. Select a RecoverByBackup or Phone Sync recovery ZIP.",
        )
    }

    private fun destinationAvailable(entry: AuditEntry): Boolean {
        val uri = entry.destination?.let(Uri::parse) ?: return false
        if (uri.scheme == "file") return uri.path?.let(::File)?.isFile == true
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r").use { descriptor -> descriptor != null }
        }.getOrDefault(false)
    }

    private fun archiveDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.path?.let(::File)?.name ?: "RecoverByBackup.zip"
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { "RecoverByBackup.zip" }
    }

    private fun archiveSize(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let(::File)?.length() ?: -1L
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
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
        val active = File(context.filesDir, activeDirectoryName)
        val previous = File(context.filesDir, "$activeDirectoryName-previous")
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
            .put("archiveUri", result.archiveUri?.toString().orEmpty())
            .put("sourceId", result.sourceId)
            .put("sourceName", result.sourceName)
            .put("verifiedItemCount", result.verifiedItemCount)
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
                            .put("contentSha256", entry.contentSha256 ?: JSONObject.NULL)
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
        stateFile().writeText(root.toString())
    }

    private fun stateFile() = File(context.filesDir, stateFileName)

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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    private fun <T> JSONArray.toObjects(transform: (JSONObject) -> T): List<T> {
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let { add(transform(it)) }
        }
    }

    private companion object {
        const val MANIFEST_PATH = "backup-manifest.json"
        const val RECOVER_BY_BACKUP_MANIFEST_PATH = "recoverbybackup-manifest.json"
        const val RECOVER_BY_BACKUP_FORMAT = "RecoverByBackup"
        const val RECOVER_BY_BACKUP_SCHEMA_VERSION = 1
        const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
        const val MAX_RECOVER_BY_BACKUP_ITEMS = 100_000
        const val MAX_RECOVER_BY_BACKUP_ITEM_BYTES = 128L * 1024 * 1024 * 1024
        const val MAX_RECOVER_BY_BACKUP_TOTAL_BYTES = 4L * 1024 * 1024 * 1024 * 1024
        const val ACTIVE_DIRECTORY = "reader-import"
        const val STATE_FILE = "reader-import.json"
        const val PREFERENCES_NAME = "reader_archive_source"
        const val SELECTED_ARCHIVE_URI_KEY = "selected_archive_uri"
        val SUPPORTED_MANIFEST_PATHS = setOf(MANIFEST_PATH, RECOVER_BY_BACKUP_MANIFEST_PATH)
    }

    private data class ArchiveManifestDocument(
        val path: String,
        val content: JSONObject,
    )
}

private fun stableArchiveTransferId(material: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    var value = 0L
    for (index in 0 until 8) value = (value shl 8) or (digest[index].toLong() and 0xff)
    return (value and Long.MAX_VALUE).coerceAtLeast(1)
}