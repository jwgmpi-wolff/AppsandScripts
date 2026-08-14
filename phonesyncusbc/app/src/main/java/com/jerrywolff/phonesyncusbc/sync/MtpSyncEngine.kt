package com.jerrywolff.phonesyncusbc.sync

import android.content.Context
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import com.jerrywolff.phonesyncusbc.data.AuditLog
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
import com.jerrywolff.phonesyncusbc.data.SyncStatus
import com.jerrywolff.phonesyncusbc.data.TransferStatus
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import com.jerrywolff.phonesyncusbc.usb.AttachedSource
import com.jerrywolff.phonesyncusbc.usb.PeerIdentity
import com.jerrywolff.phonesyncusbc.usb.UsbSourceResolver

data class SyncProgress(
    val currentItem: String?,
    val transferredItems: Int,
    val skippedItems: Int,
    val failedItems: Int,
    val bytesTransferred: Long,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
)

data class SyncResult(
    val status: SyncStatus,
    val transferredItems: Int,
    val skippedItems: Int,
    val failedItems: Int,
    val bytesTransferred: Long,
    val error: String? = null,
)

private data class MtpCandidate(
    val handle: Int,
    val path: String,
    val size: Long,
    val modifiedAtEpochMillis: Long,
)

class MtpSyncEngine(
    context: Context,
    private val auditLog: AuditLog,
    private val sourceResolver: UsbSourceResolver,
    private val targetMediaStore: TargetMediaStore = TargetMediaStore(context),
) {
    fun sync(
        source: AttachedSource,
        identity: PeerIdentity,
        authorizedCategories: Set<ConsentCategory>,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult {
        val sessionId = auditLog.beginSession(identity.peerId)
        var transferred = 0
        var skipped = 0
        var failed = 0
        var transferredBytes = 0L

        val mtpSession = sourceResolver.openMtp(source.device)
        if (mtpSession == null) {
            val error = "The source is not exposing an MTP/PTP data connection."
            auditLog.finishSession(sessionId, SyncStatus.FAILED, 0, 0, error)
            return SyncResult(SyncStatus.FAILED, 0, 0, 0, 0, error)
        }

        return try {
            mtpSession.use { session ->
                walkObjects(session.device) { candidate ->
                    if (TransferClassifier.isProtectedPrivateDatabase(candidate.path)) {
                        skipped += 1
                        return@walkObjects
                    }
                    val category = TransferClassifier.classify(candidate.path)
                    if (category !in authorizedCategories) return@walkObjects

                    val fingerprint = DeviceIdentity.sha256(
                        "${identity.peerId}|${candidate.path}|${candidate.size}|${candidate.modifiedAtEpochMillis}",
                    )
                    if (auditLog.wasTransferred(identity.peerId, fingerprint)) {
                        skipped += 1
                        publishProgress(
                            currentItem = candidate.path,
                            transferred = transferred,
                            skipped = skipped,
                            failed = failed,
                            transferredBytes = transferredBytes,
                            onProgress = onProgress,
                        )
                        return@walkObjects
                    }

                    val transfer = runCatching {
                        targetMediaStore.importMtpObject(
                            mtpDevice = session.device,
                            objectHandle = candidate.handle,
                            displayName = candidate.path.substringAfterLast('/').ifBlank { "imported-item" },
                            category = category,
                            sourceName = source.detected.displayName,
                            modifiedAtEpochMillis = candidate.modifiedAtEpochMillis,
                            expectedBytes = candidate.size,
                            onBytesTransferred = { currentBytes ->
                                publishProgress(
                                    currentItem = candidate.path,
                                    transferred = transferred,
                                    skipped = skipped,
                                    failed = failed,
                                    transferredBytes = transferredBytes + currentBytes,
                                    currentItemBytes = currentBytes,
                                    currentItemTotal = candidate.size,
                                    onProgress = onProgress,
                                )
                            },
                        )
                    }
                    transfer.onSuccess { result ->
                        transferred += 1
                        transferredBytes += result.bytesWritten
                        auditLog.recordTransfer(
                            sessionId = sessionId,
                            peerId = identity.peerId,
                            sourceFingerprint = fingerprint,
                            category = category,
                            sourceItem = candidate.path,
                            destination = result.uri.toString(),
                            bytesTransferred = result.bytesWritten,
                            status = TransferStatus.COMPLETED,
                        )
                    }.onFailure { throwable ->
                        failed += 1
                        auditLog.recordTransfer(
                            sessionId = sessionId,
                            peerId = identity.peerId,
                            sourceFingerprint = fingerprint,
                            category = category,
                            sourceItem = candidate.path,
                            destination = null,
                            bytesTransferred = 0,
                            status = TransferStatus.FAILED,
                            error = throwable.message ?: throwable.javaClass.simpleName,
                        )
                    }
                    publishProgress(
                        currentItem = candidate.path,
                        transferred = transferred,
                        skipped = skipped,
                        failed = failed,
                        transferredBytes = transferredBytes,
                        currentItemBytes = candidate.size,
                        currentItemTotal = candidate.size,
                        onProgress = onProgress,
                    )
                }
            }
            val status = if (failed == 0) SyncStatus.COMPLETED else SyncStatus.PARTIAL
            auditLog.finishSession(sessionId, status, transferred, transferredBytes)
            SyncResult(status, transferred, skipped, failed, transferredBytes)
        } catch (throwable: Throwable) {
            val error = throwable.message ?: throwable.javaClass.simpleName
            auditLog.finishSession(
                sessionId,
                if (transferred > 0) SyncStatus.PARTIAL else SyncStatus.FAILED,
                transferred,
                transferredBytes,
                error,
            )
            SyncResult(
                status = if (transferred > 0) SyncStatus.PARTIAL else SyncStatus.FAILED,
                transferredItems = transferred,
                skippedItems = skipped,
                failedItems = failed + 1,
                bytesTransferred = transferredBytes,
                error = error,
            )
        }
    }

    private fun walkObjects(mtpDevice: MtpDevice, visit: (MtpCandidate) -> Unit) {
        val visited = mutableSetOf<Int>()
        (mtpDevice.storageIds ?: intArrayOf()).forEach { storageId ->
            walkChildren(mtpDevice, storageId, ROOT_OBJECT_HANDLE, "", visited, visit)
        }
    }

    private fun walkChildren(
        mtpDevice: MtpDevice,
        storageId: Int,
        parentHandle: Int,
        parentPath: String,
        visited: MutableSet<Int>,
        visit: (MtpCandidate) -> Unit,
    ) {
        (mtpDevice.getObjectHandles(storageId, 0, parentHandle) ?: intArrayOf()).forEach { handle ->
            if (!visited.add(handle)) return@forEach
            val objectInfo = mtpDevice.getObjectInfo(handle) ?: return@forEach
            val name = objectInfo.name?.takeIf(String::isNotBlank) ?: "object-$handle"
            val path = "$parentPath/${sanitizeSourcePathSegment(name)}"
            if (objectInfo.format == MtpConstants.FORMAT_ASSOCIATION) {
                walkChildren(mtpDevice, storageId, handle, path, visited, visit)
            } else {
                visit(objectInfo.toCandidate(handle, path))
            }
        }
    }

    private fun MtpObjectInfo.toCandidate(handle: Int, path: String): MtpCandidate {
        return MtpCandidate(
            handle = handle,
            path = path,
            size = compressedSizeLong.coerceAtLeast(0),
            modifiedAtEpochMillis = dateModified.coerceAtLeast(0),
        )
    }

    private fun publishProgress(
        currentItem: String,
        transferred: Int,
        skipped: Int,
        failed: Int,
        transferredBytes: Long,
        currentItemBytes: Long = 0,
        currentItemTotal: Long = 0,
        onProgress: (SyncProgress) -> Unit,
    ) {
        onProgress(
            SyncProgress(
                currentItem = currentItem,
                transferredItems = transferred,
                skippedItems = skipped,
                failedItems = failed,
                bytesTransferred = transferredBytes,
                currentItemBytes = currentItemBytes,
                currentItemTotal = currentItemTotal,
            ),
        )
    }

    private fun sanitizeSourcePathSegment(value: String): String {
        return value.replace('/', '_').replace('\\', '_').take(255).ifBlank { "source-item" }
    }

    private companion object {
        const val ROOT_OBJECT_HANDLE = 0
    }
}