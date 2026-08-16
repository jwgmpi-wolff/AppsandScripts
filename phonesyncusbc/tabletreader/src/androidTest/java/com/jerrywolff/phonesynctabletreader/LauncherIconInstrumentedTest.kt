package com.jerrywolff.phonesynctabletreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconInstrumentedTest {
    @Test
    fun readerAndCollectorLauncherIconsAreVisuallyDistinct() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = context.packageManager
        val reader = render(packageManager.getApplicationIcon(READER_PACKAGE))
        val readerPixels = IntArray(ICON_SIZE * ICON_SIZE).also {
            reader.getPixels(it, 0, ICON_SIZE, 0, 0, ICON_SIZE, ICON_SIZE)
        }
        val collector = runCatching { packageManager.getApplicationIcon(COLLECTOR_PACKAGE) }.getOrNull()
        if (collector != null) {
            val collectorPixels = IntArray(ICON_SIZE * ICON_SIZE).also {
                render(collector).getPixels(it, 0, ICON_SIZE, 0, 0, ICON_SIZE, ICON_SIZE)
            }
            val changedPixels = collectorPixels.indices.count { index ->
                colorDistance(collectorPixels[index], readerPixels[index]) >= MIN_COLOR_DISTANCE
            }
            assertTrue(
                "Reader and collector launcher icons are too similar: $changedPixels changed pixels.",
                changedPixels >= collectorPixels.size * MIN_CHANGED_PERCENT / 100,
            )
        } else {
            val greenPixels = readerPixels.count { pixel ->
                android.graphics.Color.green(pixel) >= android.graphics.Color.blue(pixel) + MIN_GREEN_ADVANTAGE &&
                    android.graphics.Color.green(pixel) >= android.graphics.Color.red(pixel) + MIN_GREEN_ADVANTAGE
            }
            assertTrue(
                "Reader launcher icon lost its distinct green identity: $greenPixels green pixels.",
                greenPixels >= readerPixels.size * MIN_GREEN_PERCENT / 100,
            )
        }
    }

    private fun render(drawable: android.graphics.drawable.Drawable): Bitmap {
        return Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            drawable.draw(Canvas(bitmap))
        }
    }

    private fun colorDistance(first: Int, second: Int): Int {
        return kotlin.math.abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
            kotlin.math.abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
            kotlin.math.abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))
    }

    private companion object {
        const val COLLECTOR_PACKAGE = "com.jerrywolff.phonesyncusbc"
        const val READER_PACKAGE = "com.jerrywolff.phonesynctabletreader"
        const val ICON_SIZE = 192
        const val MIN_COLOR_DISTANCE = 48
        const val MIN_CHANGED_PERCENT = 30
        const val MIN_GREEN_ADVANTAGE = 20
        const val MIN_GREEN_PERCENT = 8
    }
}