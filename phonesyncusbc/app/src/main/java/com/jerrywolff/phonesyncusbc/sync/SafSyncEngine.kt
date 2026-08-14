package com.jerrywolff.phonesyncusbc.sync

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.jerrywolff.phonesyncusbc.data.AuditLog
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
import com.jerrywolff.phonesyncusbc.data.SafGrant
import com.jerrywolff.phonesyncusbc.data.SyncStatus
import com.jerrywolff.phonesyncusbc.data.TransferStatus
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory

class SafSyncEngine(
    private val context: Context,
    private val auditLog: AuditLog,
    private val targetMediaStore: TargetMediaStore = TargetMediaStore(context),
) {
    fun sync(
        peerId: String,
        grants: List<SafGrant>,
        authorizedCategories: Set<ConsentCategory>,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult {
        val applicableGrants = grants.filter { it.category in authorizedCategories }
        if (applicableGrants.isEmpty()) {
            return SyncResult(SyncStatus.COMPLETED, 0, 0, 0, 0)
        }

        val sessionId = auditLog.beginSession(peerId)
        var transferred = 0
        var skipped = 0
        var failed = 0
        var transferredBytes = 0L

        applicableGrants.forEach { grant ->
            val root = DocumentFile.fromTreeUri(context, grant.uri)
            if (root == null || !root.canRead()) {
                failed += 1
                return@forEach
            }
            walk(root, grant.displayName, mutableSetOf()) { item, path ->
                val fingerprint = DeviceIdentity.sha256(
                    "$peerId|${grant.id}|${item.uri}|${item.length()}|${item.lastModified()}",
                )
                if (auditLog.wasTransferred(peerId, fingerprint)) {
                    skipped += 1
                    publish(path, transferred, skipped, failed, transferredBytes, onProgress)
                    return@walk
                }

                runCatching {
                    targetMediaStore.copyFromProvider(
                        sourceUri = item.uri,
                        displayName = item.name ?: "provider-item",
                        mimeType = item.type,
                        category = grant.category,
                        sourceName = grant.displayName,
                        modifiedAtEpochMillis = item.lastModified(),
                    )
                }.onSuccess { result ->
                    transferred += 1
                    transferredBytes += result.bytesWritten
                    auditLog.recordTransfer(
                        sessionId = sessionId,
                        peerId = peerId,
                        sourceFingerprint = fingerprint,
                        category = grant.category,
                        sourceItem = path,
                        destination = result.uri.toString(),
                        bytesTransferred = result.bytesWritten,
                        status = TransferStatus.COMPLETED,
                    )
                }.onFailure { throwable ->
                    failed += 1
                    auditLog.recordTransfer(
                        sessionId = sessionId,
                        peerId = peerId,
                        sourceFingerprint = fingerprint,
                        category = grant.category,
                        sourceItem = path,
                        destination = null,
                        bytesTransferred = 0,
                        status = TransferStatus.FAILED,
                        error = throwable.message ?: throwable.javaClass.simpleName,
                    )
                }
                publish(path, transferred, skipped, failed, transferredBytes, onProgress)
            }
        }

        val status = when {
            failed == 0 -> SyncStatus.COMPLETED
            transferred > 0 -> SyncStatus.PARTIAL
            else -> SyncStatus.FAILED
        }
        auditLog.finishSession(sessionId, status, transferred, transferredBytes)
        return SyncResult(status, transferred, skipped, failed, transferredBytes)
    }

    private fun walk(
        parent: DocumentFile,
        parentPath: String,
        visited: MutableSet<String>,
        visit: (DocumentFile, String) -> Unit,
    ) {
        if (!visited.add(parent.uri.toString())) return
        runCatching(parent::listFiles).getOrDefault(emptyArray()).forEach { child ->
            val path = "$parentPath/${child.name ?: "provider-item"}"
            when {
                child.isDirectory -> walk(child, path, visited, visit)
                child.isFile && child.canRead() -> visit(child, path)
            }
        }
    }

    private fun publish(
        currentItem: String,
        transferred: Int,
        skipped: Int,
        failed: Int,
        bytesTransferred: Long,
        onProgress: (SyncProgress) -> Unit,
    ) {
        onProgress(SyncProgress(currentItem, transferred, skipped, failed, bytesTransferred))
    }
}