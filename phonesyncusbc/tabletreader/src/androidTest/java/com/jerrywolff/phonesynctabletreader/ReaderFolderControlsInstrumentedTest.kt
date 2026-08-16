package com.jerrywolff.phonesynctabletreader

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderFolderControlsInstrumentedTest {
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = File(context.cacheDir, "reader-folder-controls-test").apply {
            deleteRecursively()
            mkdirs()
        }
        File(testDirectory, "messages.json").writeText("""[{"message":"visible control test"}]""")
        val manager = ExternalFolderSourceManager(context)
        val treeUri = Uri.fromFile(testDirectory)
        manager.rememberTreeUri(treeUri)
        manager.scanRoot(treeUri, DocumentFile.fromFile(testDirectory))
        context.getSharedPreferences("reader_content_source", Context.MODE_PRIVATE)
            .edit()
            .putString("source_mode", "FOLDER")
            .commit()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "reader-folder-source.json").delete()
        context.getSharedPreferences("reader_content_source", Context.MODE_PRIVATE).edit().clear().commit()
        testDirectory.deleteRecursively()
    }

    @Test
    fun folderReaderShowsChooseAndRefreshContentControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Choose folder", substring = true).fetchSemanticsNode()
            composeTestRule.onNodeWithText("Refresh folder", substring = true).fetchSemanticsNode()
        }
    }
}