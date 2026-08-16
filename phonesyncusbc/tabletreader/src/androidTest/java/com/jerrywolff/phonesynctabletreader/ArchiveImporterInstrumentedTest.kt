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

        val result = ArchiveImporter(context).import(Uri.fromFile(archive))

        assertEquals(1, result.entries.size)
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
            File(context.filesDir, "reader-import.json").delete()
            File(context.filesDir, "reader-import").deleteRecursively()
            File(context.filesDir, "reader-import-previous").deleteRecursively()
            File(context.filesDir, "reader-import-staging").deleteRecursively()
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

    private companion object {
        const val SOURCE_ID = "external-test-peer"
    }
}