package com.jerrywolff.phonesyncusbc.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOwnershipTest {
    @Test
    fun `collector exports are not external source data`() {
        assertFalse(isExternalSourcePeer("local-android"))
    }

    @Test
    fun `USB peer records are external source data`() {
        assertTrue(isExternalSourcePeer("usb-peer-sha256"))
    }

    @Test
    fun `collector folder is rejected even when peer was mislabeled external`() {
        assertTrue(isCollectorOwnedSourceItem("/Download/Phone Sync/This Android/sms_exports/messages.json"))
        assertTrue(isCollectorOwnedSourceItem("PhoneSync/local-android/call_logs/calls.json"))
        assertFalse(
            isExternalSourceRecord(
                "usb-peer-sha256",
                "/Download/Phone Sync/This Android/sms_exports/messages.json",
            ),
        )
    }

    @Test
    fun `external device path remains eligible`() {
        assertFalse(isCollectorOwnedSourceItem("/DCIM/Camera/photo.jpg"))
        assertTrue(isExternalSourceRecord("usb-peer-sha256", "/Downloads/source-export/messages.json"))
    }

    @Test
    fun `external recovery entries collapse repeated pulls by content hash`() {
        val entries = listOf(
            auditEntry(1, "/DCIM/Camera/photo.jpg", "same-hash", "first-fingerprint"),
            auditEntry(2, "/DCIM/Camera/photo-copy.jpg", "same-hash", "second-fingerprint"),
            auditEntry(3, "/DCIM/Camera/other.jpg", "other-hash", "third-fingerprint"),
        )

        assertEquals(listOf(1L, 3L), externalDeviceRecoveryEntries(entries).map(AuditEntry::id))
    }

    private fun auditEntry(id: Long, path: String, hash: String?, fingerprint: String): AuditEntry {
        return AuditEntry(
            id = id,
            transferredAtEpochMillis = id,
            category = com.jerrywolff.phonesyncusbc.domain.ConsentCategory.DOCUMENTS,
            sourceItem = path,
            destination = "content://item/$id",
            bytesTransferred = 10,
            status = TransferStatus.COMPLETED,
            error = null,
            contentSha256 = hash,
            peerId = "usb-peer-sha256",
            sourceFingerprint = fingerprint,
        )
    }
}