package com.jerrywolff.phonesynctabletreader

import android.app.KeyguardManager
import android.content.Intent
import android.content.Context
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderFolderControlsInstrumentedTest {
    @Test
    fun folderReaderAlwaysShowsConnectAndResyncControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val keyguard = targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assumeFalse(
            "The device is behind a secure lock screen; unlock it to run the first-viewport assertion.",
            keyguard.isDeviceLocked && keyguard.isDeviceSecure,
        )
        instrumentation.uiAutomation.runShellCommand("input keyevent KEYCODE_WAKEUP")
        instrumentation.uiAutomation.runShellCommand("wm dismiss-keyguard")
        val intent = Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainActivity.EXTRA_SHOW_FOLDER_ACTIONS_ONLY, true)
        instrumentation.uiAutomation.executeAndWaitForEvent(
            { targetContext.startActivity(intent) },
            { event ->
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.packageName?.toString() == targetContext.packageName
            },
            5_000,
        )
        instrumentation.waitForIdleSync()

        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertNotNull("The Reader window did not become accessible.", root)
        val visibleTexts = root.visibleTexts()
        val windowDetail = "package=${root.packageName}, text=$visibleTexts"
        assertTrue("Connect folder is not visible; $windowDetail", "Connect folder" in visibleTexts)
        assertTrue("Resync folder is not visible; $windowDetail", "Resync folder" in visibleTexts)
        assertTrue("Open archive is not visible; $windowDetail", "Open archive" in visibleTexts)
        assertTrue("Refresh archive is not visible; $windowDetail", "Refresh archive" in visibleTexts)
    }

    private fun AccessibilityNodeInfo?.visibleTexts(): Set<String> {
        if (this == null) return emptySet()
        val values = linkedSetOf<String>()
        if (isVisibleToUser) text?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
        for (index in 0 until childCount) {
            values += getChild(index).visibleTexts()
        }
        return values
    }

    private fun UiAutomation.runShellCommand(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).use { it.readBytes() }
    }
}