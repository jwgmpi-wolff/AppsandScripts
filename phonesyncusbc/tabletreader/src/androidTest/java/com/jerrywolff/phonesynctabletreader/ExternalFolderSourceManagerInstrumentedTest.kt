package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrywolff.phonesyncusbc.data.ArtifactFocus
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexDatabase
import com.jerrywolff.phonesyncusbc.data.ArtifactIndexer
import com.jerrywolff.phonesyncusbc.data.externalDeviceRecoveryEntries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExternalFolderSourceManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var manager: ExternalFolderSourceManager
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = ExternalFolderSourceManager(context, TEST_STATE_FILE, TEST_PREFERENCES)
        testDirectory = File(context.cacheDir, "reader-folder-refresh-test").apply {
            deleteRecursively()
            mkdirs()
        }
        context.deleteDatabase(INDEX_DATABASE)
        File(context.filesDir, TEST_STATE_FILE).delete()
        context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(INDEX_DATABASE)
        File(context.filesDir, TEST_STATE_FILE).delete()
        context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        testDirectory.deleteRecursively()
    }

    @Test
    fun recursivelyRefreshesAddedUpdatedAndDeletedExternalFolderFiles() {
        val chatFile = File(testDirectory, "Messages/WhatsApp Chat.json").apply {
            parentFile!!.mkdirs()
            writeText("""[{"sender":"Alex","message":"before refresh"}]""")
        }
        val photoFile = File(testDirectory, "Images/photo.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        File(testDirectory, "Phone Sync/This Android/legacy.txt").apply {
            parentFile!!.mkdirs()
            writeText("owner-selected external folder content")
        }
        val treeUri = Uri.fromFile(testDirectory)
        val root = DocumentFile.fromFile(testDirectory)
        val first = manager.scanRoot(treeUri, root)

        assertEquals(3, first.entries.size)
        assertTrue(first.issues.isEmpty())
        assertEquals(3, externalDeviceRecoveryEntries(first.entries, first.sourceId).size)
        assertTrue(first.entries.any { "%20" in it.sourceItem })
        val firstChatId = first.entries.single { it.sourceItem.endsWith("WhatsApp%20Chat.json") }.id

        val database = ArtifactIndexDatabase(context, INDEX_DATABASE)
        try {
            val firstIndex = ArtifactIndexer(context, database).rebuild(first.entries, first.sourceId, first.sourceName)
            assertTrue(firstIndex.recordsIndexed >= 3)
            assertTrue(
                database.queryRecords(
                    sourceId = first.sourceId,
                    focus = ArtifactFocus.MESSAGES,
                    search = "before refresh",
                    limit = 20,
                ).isNotEmpty(),
            )

            chatFile.writeText("""[{"sender":"Alex","message":"after refresh"}]""")
            assertTrue(photoFile.delete())
            File(testDirectory, "Voicemail/voicemail-new.m4a").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(9, 8, 7, 6, 5))
            }

            val refreshed = manager.scanRoot(treeUri, DocumentFile.fromFile(testDirectory))

            assertEquals(first.sourceId, refreshed.sourceId)
            assertEquals(3, refreshed.entries.size)
            assertFalse(refreshed.entries.any { it.sourceItem.endsWith("photo.jpg") })
            assertTrue(refreshed.entries.any { it.sourceItem.endsWith("voicemail%2Dnew.m4a") })
            val refreshedChatId = refreshed.entries.single { it.sourceItem.endsWith("WhatsApp%20Chat.json") }.id
            assertNotEquals(firstChatId, refreshedChatId)

            val refreshedIndex = ArtifactIndexer(context, database).rebuild(
                refreshed.entries,
                refreshed.sourceId,
                refreshed.sourceName,
            )
            assertTrue(refreshedIndex.recordsIndexed >= 3)
            assertEquals(
                refreshed.entries.mapTo(linkedSetOf()) { it.id },
                database.sourceTransferIds(refreshed.sourceId),
            )
            assertTrue(
                database.queryRecords(
                    sourceId = refreshed.sourceId,
                    focus = ArtifactFocus.MESSAGES,
                    search = "after refresh",
                    limit = 20,
                ).isNotEmpty(),
            )
            assertTrue(
                database.queryRecords(
                    sourceId = refreshed.sourceId,
                    focus = ArtifactFocus.MESSAGES,
                    search = "before refresh",
                    limit = 20,
                ).isEmpty(),
            )
            assertEquals(
                1,
                database.queryRecords(
                    sourceId = refreshed.sourceId,
                    focus = ArtifactFocus.VOICEMAILS,
                    limit = 20,
                ).size,
            )

            val persisted = manager.loadSnapshot()
            assertEquals(refreshed.sourceId, persisted?.sourceId)
            assertEquals(refreshed.entries.map { it.id }.toSet(), persisted?.entries?.map { it.id }?.toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun waitsForCloudProviderLoadingBeforeAcceptingFolderListing() {
        val progressItems = mutableListOf<String>()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            LoadingDocumentsProvider.AUTHORITY,
            LoadingDocumentsProvider.ROOT_ID,
        )

        val result = manager.scan(treeUri) { update -> progressItems += update.currentItem }

        assertEquals(2, result.entries.size)
        assertTrue(result.error.isNullOrBlank())
        assertTrue(result.entries.any { it.sourceItem.endsWith("first.txt") })
        assertTrue(result.entries.any { it.sourceItem.endsWith("uploaded%2Dlater.txt") })
        assertTrue(progressItems.any { it.startsWith("Waiting for OneDrive / cloud sync:") })
    }

    @Test
    fun recursivelyFindsRecoveryArchivesInSelectedLocation() {
        File(testDirectory, "Phone backups/Android/recovery-one.zip").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        File(testDirectory, "Phone backups/iPhone/Nested/recovery-two.ZIP").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6, 7))
        }
        File(testDirectory, "Phone backups/readme.txt").writeText("not an archive")
        val treeUri = Uri.fromFile(testDirectory)

        val result = manager.findRecoveryArchivesRoot(
            treeUri,
            DocumentFile.fromFile(testDirectory),
        )

        assertEquals(2, result.candidates.size)
        assertEquals(
            listOf(
                "Phone backups/Android/recovery-one.zip",
                "Phone backups/iPhone/Nested/recovery-two.ZIP",
            ),
            result.candidates.map { it.relativePath },
        )
        assertTrue(result.error.isNullOrBlank())
    }

    private companion object {
        const val INDEX_DATABASE = "external-folder-source-test.sqlite"
        const val TEST_STATE_FILE = "reader-folder-source-test.json"
        const val TEST_PREFERENCES = "reader_content_source_test"
    }
}