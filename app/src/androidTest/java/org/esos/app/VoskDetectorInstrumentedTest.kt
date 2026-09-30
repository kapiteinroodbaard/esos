package org.esos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoskDetectorInstrumentedTest {
    @Test fun bundledModelsLoadAndRecognizeSilenceWithoutAlarm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dutch = ModelAssets.unpack(context, "vosk-model-small-nl-0.22") { true }
        val english = ModelAssets.unpack(context, "vosk-model-small-en-us-0.15") { true }
        VoskDetector(dutch, english).use { detector ->
            repeat(5) { assertFalse(detector.acceptPcm16(ShortArray(3_200), 3_200)) }
        }
    }
}