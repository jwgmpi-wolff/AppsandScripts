package com.jerrywolff.phonesynctabletreader

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrywolff.phonesyncusbc.data.ArtifactFocus
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexDatabase
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexer
import com.jerrywolff.phonesyncusbc.data.RecoveryIssueReason
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ArchiveImporterInstrumentedTest {
    @Test
    fun importsVerifiedExternalEntryAndQuarantinesCollectorEntry() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val archive = File(context.cacheDir, "reader-import-test.zip")
        val importer = ArchiveImporter(
            context = context,
            stateFileName = TEST_STATE_FILE,
            activeDirectoryName = TEST_ACTIVE_DIRECTORY,
            preferencesName = TEST_PREFERENCES,
        )
        val validBytes = """[{"sender":"Alex","message":"Teams export recovered"}]"""
            .toByteArray(Charsets.UTF_8)
        val collectorBytes = "[{\"message\":\"collector\"}]".toByteArray(Charsets.UTF_8)
        val manifest = JSONObject()
            .put("externalPeerId", SOURCE_ID)
            .put("entries", JSONArray().apply {
                put(manifestEntry(
                    sourceItem = "/Downloads/Microsoft Teams/teams-export/messages.json",
                    archivePath = "chat_exports/messages.json",
                    fingerprint = "teams-fingerprint",
                    bytes = validBytes,
                ))
                put(manifestEntry(
                    sourceItem = "/Download/Phone Sync/Selected folder/local.json",
                    archivePath = "documents/local.json",
                    fingerprint = "collector-fingerprint",
                    bytes = collectorBytes,
                ))
            })
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("chat_exports/messages.json"))
            output.write(validBytes)
            output.closeEntry()
            output.putNextEntry(ZipEntry("documents/local.json"))
            output.write(collectorBytes)
            output.closeEntry()
            output.putNextEntry(ZipEntry("backup-manifest.json"))
            output.write(manifest.toString().toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }

        val archiveUri = Uri.fromFile(archive)
        val result = importer.import(archiveUri)

        assertEquals(1, result.entries.size)
        assertEquals(archiveUri, result.archiveUri)
        assertEquals(archiveUri, importer.selectedArchiveUri())
        assertEquals(archiveUri, importer.load()?.archiveUri)
        assertEquals(validBytes.size.toLong(), result.entries.single().bytesTransferred)
        assertTrue(result.issues.any { it.reason == RecoveryIssueReason.COLLECTOR_ORIGIN })
        val imported = File(Uri.parse(result.entries.single().destination).path!!)
        assertEquals(validBytes.toList(), imported.readBytes().toList())

        val databaseName = "tablet-reader-import-test.sqlite"
        context.deleteDatabase(databaseName)
        val database = ArtifactIndexDatabase(context, databaseName)
        try {
            val indexed = ArtifactIndexer(context, database).rebuild(result.entries, SOURCE_ID, "Test source")
            assertEquals(1, indexed.recordsIndexed)
            assertEquals(
                "Teams export recovered",
                database.queryRecords(sourceId = SOURCE_ID, focus = ArtifactFocus.MESSAGES).single().summary,
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            archive.delete()
            File(context.filesDir, TEST_STATE_FILE).delete()
            File(context.filesDir, TEST_ACTIVE_DIRECTORY).deleteRecursively()
            File(context.filesDir, "$TEST_ACTIVE_DIRECTORY-previous").deleteRecursively()
            File(context.filesDir, "$TEST_ACTIVE_DIRECTORY-staging").deleteRecursively()
            context.getSharedPreferences(TEST_PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun refreshesPersistedArchiveAndRetainsNewNestedFolderPath() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val archive = File(context.cacheDir, "reader-archive-refresh-test.zip")
        val invalidArchive = File(context.cacheDir, "reader-archive-refresh-invalid.zip")
        val importer = ArchiveImporter(
            context = context,
            stateFileName = REFRESH_STATE_FILE,
            activeDirectoryName = REFRESH_ACTIVE_DIRECTORY,
            preferencesName = REFRESH_PREFERENCES,
        )
        val firstItem = ArchiveTestItem(
            sourceItem = "/Messages/Existing/first.json",
            archivePath = "Messages/Existing/first.json",
            fingerprint = "first-refresh-fingerprint",
            bytes = """[{"message":"first archive version"}]""".toByteArray(),
        )
        val addedItem = ArchiveTestItem(
            sourceItem = "/Messages/New Folder/second.json",
            archivePath = "Messages/New Folder/second.json",
            fingerprint = "second-refresh-fingerprint",
            bytes = """[{"message":"added after refresh"}]""".toByteArray(),
        )

        try {
            writeArchive(archive, listOf(firstItem))
            val first = importer.import(Uri.fromFile(archive))
            assertEquals(1, first.entries.size)

            writeArchive(archive, listOf(firstItem, addedItem))
            val refreshed = importer.import(importer.selectedArchiveUri()!!)

            assertEquals(2, refreshed.entries.size)
            assertTrue(refreshed.entries.any { it.sourceItem == addedItem.sourceItem })
            assertEquals(2, importer.load()?.entries?.size)
            assertEquals(Uri.fromFile(archive), importer.load()?.archiveUri)

            invalidArchive.writeText("not a recovery archive")
            assertTrue(runCatching { importer.import(Uri.fromFile(invalidArchive)) }.isFailure)
            assertEquals(Uri.fromFile(archive), importer.selectedArchiveUri())
        } finally {
            archive.delete()
            invalidArchive.delete()
            File(context.filesDir, REFRESH_STATE_FILE).delete()
            File(context.filesDir, REFRESH_ACTIVE_DIRECTORY).deleteRecursively()
            File(context.filesDir, "$REFRESH_ACTIVE_DIRECTORY-previous").deleteRecursively()
            File(context.filesDir, "$REFRESH_ACTIVE_DIRECTORY-staging").deleteRecursively()
            context.getSharedPreferences(REFRESH_PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private fun writeArchive(archive: File, items: List<ArchiveTestItem>) {
        val manifest = JSONObject()
            .put("externalPeerId", SOURCE_ID)
            .put("entries", JSONArray().apply {
                items.forEach { item ->
                    put(manifestEntry(item.sourceItem, item.archivePath, item.fingerprint, item.bytes))
                }
            })
        ZipOutputStream(archive.outputStream()).use { output ->
            items.forEach { item ->
                output.putNextEntry(ZipEntry(item.archivePath))
                output.write(item.bytes)
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry("backup-manifest.json"))
            output.write(manifest.toString().toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }

    private fun manifestEntry(
        sourceItem: String,
        archivePath: String,
        fingerprint: String,
        bytes: ByteArray,
    ): JSONObject = JSONObject()
        .put("category", "CHAT_EXPORTS")
        .put("peerId", SOURCE_ID)
        .put("sourceFingerprint", fingerprint)
        .put("sourceItem", sourceItem)
        .put("sourceSize", bytes.size)
        .put("sourceModifiedAtEpochMillis", 1)
        .put("recoveredAtEpochMillis", 1)
        .put("archivePath", archivePath)
        .put("bytes", bytes.size)
        .put("sha256", sha256(bytes))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ArchiveTestItem(
        val sourceItem: String,
        val archivePath: String,
        val fingerprint: String,
        val bytes: ByteArray,
    )

    private companion object {
        const val SOURCE_ID = "external-test-peer"
        const val TEST_STATE_FILE = "reader-import-test.json"
        const val TEST_ACTIVE_DIRECTORY = "reader-import-test"
        const val TEST_PREFERENCES = "reader_archive_source_test"
        const val REFRESH_STATE_FILE = "reader-archive-refresh-test.json"
        const val REFRESH_ACTIVE_DIRECTORY = "reader-archive-refresh-test"
        const val REFRESH_PREFERENCES = "reader_archive_refresh_source_test"
    }
}