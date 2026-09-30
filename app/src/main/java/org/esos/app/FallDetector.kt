package org.esos.app

import kotlin.math.abs
import kotlin.math.sqrt

class FallDetector {
    private var freeFallAt: Long? = null
    private var impactAt: Long? = null
    private var stillAt: Long? = null
    private var previousMagnitude: Float? = null
    private var lastTriggerAt: Long? = null

    fun acceptSample(x: Float, y: Float, z: Float, now: Long, sensitivity: Int): Boolean {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val priorMagnitude = previousMagnitude
        previousMagnitude = magnitude

        if (lastTriggerAt?.let { now - it < COOLDOWN_MILLIS } == true) return false

        val impact = impactAt
        if (impact != null) {
            if (now - impact > IMPACT_CONFIRM_MILLIS) {
                clearEvent()
            } else {
                val steady = abs(magnitude - GRAVITY) <= GRAVITY * 0.25f &&
                    priorMagnitude?.let { abs(magnitude - it) <= GRAVITY * 0.15f } == true
                if (steady) {
                    val stillStart = stillAt ?: now.also { stillAt = it }
                    if (now - stillStart >= STILL_CONFIRM_MILLIS) {
                        lastTriggerAt = now
                        clearEvent()
                        return true
                    }
                } else {
                    stillAt = null
                }
                return false
            }
        }

        val freeFall = freeFallAt
        if (freeFall == null) {
            if (magnitude < GRAVITY * FREE_FALL_RATIO) freeFallAt = now
        } else if (now - freeFall > FREE_FALL_WINDOW_MILLIS) {
            freeFallAt = null
        } else if (magnitude >= impactThreshold(sensitivity)) {
            impactAt = now
            freeFallAt = null
            stillAt = null
        }
        return false
    }

    private fun clearEvent() {
        freeFallAt = null
        impactAt = null
        stillAt = null
    }

    private fun impactThreshold(sensitivity: Int): Float {
        val level = sensitivity.coerceIn(1, 5)
        val thresholdInG = 4.5f - (level - 1) * 0.625f
        return GRAVITY * thresholdInG
    }

    private companion object {
        const val GRAVITY = 9.81f
        const val FREE_FALL_RATIO = 0.6f
        const val FREE_FALL_WINDOW_MILLIS = 1_500L
        const val IMPACT_CONFIRM_MILLIS = 5_000L
        const val STILL_CONFIRM_MILLIS = 1_000L
        const val COOLDOWN_MILLIS = 30_000L
    }
}