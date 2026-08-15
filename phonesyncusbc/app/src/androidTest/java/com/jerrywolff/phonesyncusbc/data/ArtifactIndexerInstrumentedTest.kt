package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtifactIndexerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: ArtifactIndexDatabase
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
        database = ArtifactIndexDatabase(context, TEST_DATABASE)
        testDirectory = File(context.cacheDir, "artifact-index-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
        testDirectory.deleteRecursively()
    }

    @Test
    fun sourceRebuildIsIndependentOfPreviousRecordOwnershipAndRepeatable() {
        database.replaceSourceArtifacts(SOURCE_ID, SOURCE_NAME) { writer ->
            writer.replaceArtifact(metadata(1, "Exports/A/messages.json")) { records ->
                records.insert("messages.json", record = record("shared"))
                ArtifactParseOutcome(ArtifactParseStatus.PARSED)
            }
            writer.replaceArtifact(metadata(2, "Exports/B/messages.json")) { records ->
                records.insert("messages.json", record = record("shared"))
                ArtifactParseOutcome(ArtifactParseStatus.PARSED)
            }
        }

        fun rebuildChangedSource() {
            database.replaceSourceArtifacts(SOURCE_ID, SOURCE_NAME) { writer ->
                writer.replaceArtifact(metadata(2, "Exports/B/messages.json")) { records ->
                    records.insert("messages.json", record = record("shared"))
                    ArtifactParseOutcome(ArtifactParseStatus.PARSED)
                }
                writer.replaceArtifact(metadata(1, "Exports/A/messages.json")) { records ->
                    records.insert("messages.json", record = record("changed"))
                    ArtifactParseOutcome(ArtifactParseStatus.PARSED)
                }
            }
        }

        rebuildChangedSource()
        val firstStats = database.stats()
        val firstSummaries = database.queryRecords(sourceId = SOURCE_ID, limit = 10).map { it.summary }.sorted()
        rebuildChangedSource()
        val secondStats = database.stats()

        assertEquals(2, firstStats.artifactCount)
        assertEquals(2, firstStats.recordCount)
        assertEquals(listOf("changed", "shared"), firstSummaries)
        assertEquals(firstStats, secondStats)
    }

    @Test
    fun singleArtifactReplacementRetainsSiblingArtifacts() {
        database.replaceArtifact(metadata(1, "Exports/A/messages.json"))
        database.replaceArtifact(metadata(2, "Exports/B/messages.json"))
        database.replaceArtifact(metadata(1, "Exports/A/messages.json"))

        assertEquals(2, database.stats().artifactCount)
    }

    @Test
    fun smsZipIndexesEveryNonSensitiveEntryAndRemainsIdempotent() {
        val zip = File(testDirectory, "sms-backup.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { archive ->
            archive.writeEntry("messages/messages.json", "[{\"sender\":\"Ada\",\"body\":\"From ZIP\"}]")
            archive.writeEntry("messages/sms.xml", "<smses><sms body=\"XML message\" /></smses>")
            archive.writeEntry("attachments/photo.jpg", "image-bytes")
            archive.writeEntry("attachments/raw.bin", "opaque-attachment")
            archive.writeEntry("databases/mmssms.db", "sqlite-bytes")
            archive.writeEntry("credentials/credentials.json", "{\"password\":\"excluded\"}")
        }
        val entry = auditEntry(zip)
        val collectorEntry = entry.copy(
            id = 11,
            sourceItem = "/Download/Phone Sync/This Android/sms_exports/collector.zip",
            peerId = SOURCE_ID,
            sourceFingerprint = "$SOURCE_ID|collector.zip|${zip.length()}|${zip.lastModified()}",
        )
        val otherPeerEntry = entry.copy(
            id = 12,
            peerId = "usb-other-peer",
            sourceFingerprint = "usb-other-peer|sms-backup.zip|${zip.length()}|${zip.lastModified()}",
        )
        val indexer = ArtifactIndexer(context, database)

        val first = indexer.rebuild(listOf(otherPeerEntry, entry, collectorEntry), SOURCE_ID, SOURCE_NAME)
        val firstStats = database.stats()
        val firstRows = database.queryRecords(sourceId = SOURCE_ID, limit = 20)
        val second = indexer.rebuild(listOf(otherPeerEntry, entry, collectorEntry), SOURCE_ID, SOURCE_NAME)
        val secondStats = database.stats()

        assertEquals(5, first.recordsIndexed)
        assertEquals(1, first.indexedArtifacts)
        assertEquals(5, firstStats.recordCount)
        assertEquals(first.recordsIndexed, second.recordsIndexed)
        assertEquals(firstStats, secondStats)
        assertTrue(firstRows.all { it.category == ConsentCategory.SMS_EXPORTS })
        assertFalse(firstRows.any { "credentials" in it.jsonSource.lowercase() })
        assertTrue(firstRows.any { it.jsonSource == "attachments/photo.jpg" && it.recordKind == ParsedRecordKind.MEDIA })
        assertTrue(firstRows.any { it.jsonSource == "databases/mmssms.db" && it.recordKind == ParsedRecordKind.APPLICATION })
        assertTrue(firstRows.any { it.jsonSource == "attachments/raw.bin" && it.recordType == "Archive item" })
    }

    private fun auditEntry(zip: File): AuditEntry = AuditEntry(
        id = 10,
        transferredAtEpochMillis = zip.lastModified(),
        category = ConsentCategory.SMS_EXPORTS,
        sourceItem = "/Backups/sms_exports/Family/sms-backup.zip",
        destination = Uri.fromFile(zip).toString(),
        bytesTransferred = zip.length(),
        status = TransferStatus.COMPLETED,
        error = null,
        contentSha256 = sha256(zip),
        peerId = SOURCE_ID,
        sourceFingerprint = "$SOURCE_ID|sms-backup.zip|${zip.length()}|${zip.lastModified()}",
    )

    private fun metadata(transferId: Long, path: String) = ArtifactIndexMetadata(
        transferId = transferId,
        sourceId = SOURCE_ID,
        sourceName = SOURCE_NAME,
        category = ConsentCategory.SMS_EXPORTS,
        sourcePath = path,
        destinationUri = "content://external/$transferId",
        mimeType = "application/json",
        bytes = 100,
        sha256 = "hash-$transferId",
        folderMetadata = deriveFolderMetadata(path, ConsentCategory.SMS_EXPORTS),
    )

    private fun record(value: String) = FlattenedJsonRecord(
        recordIndex = 0,
        recordType = "message",
        recordKind = ParsedRecordKind.MESSAGE,
        title = value,
        summary = value,
        timestamp = null,
        fields = listOf(FlattenedJsonField("body", "body", FlattenedValueType.STRING, value)),
    )

    private fun ZipOutputStream.writeEntry(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray())
        closeEntry()
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val TEST_DATABASE = "artifact_index_instrumented_test.sqlite"
        const val SOURCE_ID = "usb-peer-test"
        const val SOURCE_NAME = "Test phone"
    }
}