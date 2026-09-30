package org.esos.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallDetectorTest {
    @Test fun detectsFreeFallImpactAndSettling() {
        val detector = FallDetector()
        assertFalse(detector.acceptSample(0f, 0f, 3f, 0L, 3))
        assertFalse(detector.acceptSample(0f, 0f, 40f, 500L, 3))
        assertFalse(detector.acceptSample(0f, 0f, 9.81f, 600L, 3))
        for (time in 800L..1_600L step 200L) {
            assertFalse(detector.acceptSample(0f, 0f, 9.81f, time, 3))
        }
        assertTrue(detector.acceptSample(0f, 0f, 9.81f, 1_800L, 3))
    }

    @Test fun sensitivityChangesImpactThresholdAndImpactAloneIsIgnored() {
        val sensitive = FallDetector()
        val insensitive = FallDetector()
        assertFalse(sensitive.acceptSample(0f, 0f, 3f, 0L, 5))
        assertFalse(insensitive.acceptSample(0f, 0f, 3f, 0L, 1))
        assertFalse(sensitive.acceptSample(0f, 0f, 40f, 500L, 5))
        assertFalse(insensitive.acceptSample(0f, 0f, 40f, 500L, 1))
        assertFalse(sensitive.acceptSample(0f, 0f, 9.81f, 600L, 5))
        assertFalse(insensitive.acceptSample(0f, 0f, 9.81f, 600L, 1))
        var detected = false
        for (time in 800L..1_800L step 200L) {
            detected = sensitive.acceptSample(0f, 0f, 9.81f, time, 5)
        }
        assertTrue(detected)
        assertFalse(insensitive.acceptSample(0f, 0f, 9.81f, 1_800L, 1))

        val impactOnly = FallDetector()
        assertFalse(impactOnly.acceptSample(0f, 0f, 35f, 0L, 5))
    }
}