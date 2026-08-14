package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferClassifierTest {
    @Test
    fun `classifies camera media`() {
        assertEquals(
            ConsentCategory.PHOTOS_AND_VIDEOS,
            TransferClassifier.classify("/Internal storage/DCIM/Camera/IMG_1001.HEIC"),
        )
        assertEquals(
            ConsentCategory.PHOTOS_AND_VIDEOS,
            TransferClassifier.classify("/Phone/DCIM/Camera/video.mp4"),
        )
    }

    @Test
    fun `classifies contacts only from vCard files`() {
        assertEquals(
            ConsentCategory.CONTACTS,
            TransferClassifier.classify("/Downloads/contacts.vcf"),
        )
    }

    @Test
    fun `classifies sensitive portable exports only from explicit folders`() {
        assertEquals(
            ConsentCategory.SMS_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/SMS/messages.xml"),
        )
        assertEquals(
            ConsentCategory.CHAT_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/Chat/conversation.zip"),
        )
        assertEquals(
            ConsentCategory.EMAIL_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/Email/archive.mbox"),
        )
        assertEquals(
            ConsentCategory.EMAIL_EXPORTS,
            TransferClassifier.classify("/Downloads/archive.mbox"),
        )
        assertEquals(
            ConsentCategory.NOTIFICATION_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/Notifications/history.json"),
        )
        assertEquals(
            ConsentCategory.SMS_EXPORTS,
            TransferClassifier.classify("/Download/Phone Sync/This Android/sms_exports/sms-mms-20260814.zip"),
        )
        assertEquals(
            ConsentCategory.CALL_LOGS,
            TransferClassifier.classify("/Download/Phone Sync/This Android/call_logs/call-log-20260814.json"),
        )
        assertEquals(
            ConsentCategory.EMAIL_EXPORTS,
            TransferClassifier.classify("/Download/Phone Sync/This Android/email_exports/account-mail.mbox"),
        )
        assertEquals(
            ConsentCategory.CALENDAR,
            TransferClassifier.classify("/Download/Phone Sync/This Android/calendar/calendar-20260814.json"),
        )
    }

    @Test
    fun `does not infer protected data from arbitrary files`() {
        assertEquals(
            ConsentCategory.DOCUMENTS,
            TransferClassifier.classify("/Android/data/chat.application/private.db"),
        )
        assertTrue(
            TransferClassifier.isProtectedPrivateDatabase(
                "/Android/data/chat.application/private.db",
            ),
        )
        assertFalse(
            TransferClassifier.isProtectedPrivateDatabase(
                "/PhoneSync/Exports/Chat/conversation.zip",
            ),
        )
    }
}