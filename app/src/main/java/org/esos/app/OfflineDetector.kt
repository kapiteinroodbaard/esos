package org.esos.app

interface OfflineDetector : AutoCloseable {
    fun acceptPcm16(samples: ShortArray, count: Int): Boolean
    fun reset()
}

class PhraseMatcher(private val debounceMillis: Long = 30_000L) {
    private var lastHit = Long.MIN_VALUE
    private val phrases = setOf("help help", "help mij", "ik heb hulp nodig", "help me", "i need help")

    fun matches(transcript: String, now: Long): Boolean {
        val normalized = transcript.lowercase().trim().replace(Regex("[.,!?]+"), "")
            .replace(Regex("\\s+"), " ")
        if (normalized !in phrases || (lastHit != Long.MIN_VALUE && now - lastHit < debounceMillis)) return false
        lastHit = now
        return true
    }
}