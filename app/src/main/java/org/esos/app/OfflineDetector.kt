package org.esos.app

enum class DetectionEvent { NONE, ALARM, CANCEL }

interface OfflineDetector : AutoCloseable {
    fun acceptPcm16(samples: ShortArray, count: Int): DetectionEvent
    fun reset()
}

object WakePhrases {
    val DEFAULTS = listOf("Help mij", "Ik heb hulp nodig", "Help Help", "Help me")
    val CANCEL_DEFAULTS = listOf("Annuleer alarm", "Vals alarm", "Alles is goed")

    fun normalize(phrase: String): String = phrase.lowercase().trim()
        .replace(Regex("[.,!?]+"), "")
        .replace(Regex("\\s+"), " ")

    fun unique(phrases: Iterable<String>): List<String> {
        val seen = mutableSetOf<String>()
        return phrases.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() && normalize(it).isNotEmpty() && seen.add(normalize(it)) }
    }

    fun normalized(phrases: Iterable<String>): Set<String> = unique(phrases)
        .mapTo(mutableSetOf(), ::normalize)
}

class PhraseMatcher(
    private val debounceMillis: Long = 30_000L,
    phrases: List<String> = WakePhrases.DEFAULTS
) {
    private var lastHit = Long.MIN_VALUE
    private val phrases = WakePhrases.normalized(phrases)

    fun matches(transcript: String, now: Long): Boolean {
        val normalized = WakePhrases.normalize(transcript)
        if (normalized !in phrases || (lastHit != Long.MIN_VALUE && now - lastHit < debounceMillis)) return false
        lastHit = now
        return true
    }
}