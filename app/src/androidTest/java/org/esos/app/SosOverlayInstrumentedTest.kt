package org.esos.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SosOverlayInstrumentedTest {
    @Test fun borderAppearsOverHomeAndIsRemovedWhenSosIsCancelled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = AppSettings(context)
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")).use { it.readBytes() }
        try {
            instrumentation.runOnMainSync {
                settings.smsEnabled = false
                settings.callEnabled = false
                settings.sosModeActive = true
                SosOverlay.start(context)
            }
            instrumentation.waitForIdleSync()
            assertTrue(SosOverlay.visible)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val borderSize = (8 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            fun isRed(pixel: Int) = Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90
            assertTrue(isRed(screenshot.getPixel(borderSize / 2, screenshot.height / 2)))
            assertTrue(isRed(screenshot.getPixel(screenshot.width - borderSize / 2 - 1, screenshot.height / 2)))
            screenshot.recycle()
            instrumentation.runOnMainSync { assertTrue(AlertRuntime.cancel(context)) }
            assertFalse(settings.sosModeActive)
            assertFalse(SosOverlay.visible)
        } finally {
            instrumentation.runOnMainSync {
                settings.sosModeActive = false
                SosOverlay.stop(context)
                SosCallScheduler.cancel(context)
            }
        }
    }

    @Test fun cancellingClearsPersistedFutureCallsAndRejectsStaleAttempts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = AppSettings(context)
        val prefs = context.getSharedPreferences("sos_call_round", Context.MODE_PRIVATE)
        instrumentation.runOnMainSync {
            settings.smsEnabled = false
            settings.callEnabled = true
            settings.sosModeActive = true
            prefs.edit().putLong("incident", 123L).putInt("index", 1)
                .putLong("deadline", System.currentTimeMillis() + 3_600_000L).commit()
            SosCallScheduler.restore(context)
            assertTrue(prefs.contains("index"))
            AlertRuntime.cancel(context)
            assertFalse(prefs.contains("index"))
            SosCallScheduler.attempt(context, 123L, 1)
            assertFalse(prefs.contains("index"))
            settings.callEnabled = false
        }
    }
}