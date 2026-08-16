package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.net.Uri
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
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = File(context.cacheDir, "reader-folder-refresh-test").apply {
            deleteRecursively()
            mkdirs()
        }
        context.deleteDatabase(INDEX_DATABASE)
        File(context.filesDir, "reader-folder-source.json").delete()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(INDEX_DATABASE)
        File(context.filesDir, "reader-folder-source.json").delete()
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
        val manager = ExternalFolderSourceManager(context)

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

    private companion object {
        const val INDEX_DATABASE = "external-folder-source-test.sqlite"
    }
}