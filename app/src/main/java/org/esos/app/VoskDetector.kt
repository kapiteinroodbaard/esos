package org.esos.app

import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class VoskDetector(
    dutchDirectory: File,
    wakePhrases: List<String> = WakePhrases.DEFAULTS,
    cancelPhrases: List<String> = WakePhrases.CANCEL_DEFAULTS,
    private val matcher: PhraseMatcher = PhraseMatcher(phrases = wakePhrases),
    private val cancelMatcher: PhraseMatcher = PhraseMatcher(debounceMillis = 0L, phrases = cancelPhrases),
    private val now: () -> Long = System::currentTimeMillis
) : OfflineDetector {
    private val dutch: Model
    private val dutchRecognizer: Recognizer

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        dutch = Model(dutchDirectory.absolutePath)
        try {
            val grammar = JSONArray()
            WakePhrases.unique(wakePhrases + cancelPhrases).forEach { grammar.put(WakePhrases.normalize(it)) }
            grammar.put("[unk]")
            dutchRecognizer = Recognizer(dutch, 16_000f, grammar.toString())
        } catch (error: Exception) {
            dutch.close()
            throw error
        }
    }

    override fun acceptPcm16(samples: ShortArray, count: Int): DetectionEvent {
        require(count in 0..samples.size)
        val dutchComplete = dutchRecognizer.acceptWaveForm(samples, count)
        val dutchText = if (dutchComplete) JSONObject(dutchRecognizer.result).optString("text") else ""
        val time = now()
        return when {
            cancelMatcher.matches(dutchText, time) -> DetectionEvent.CANCEL
            matcher.matches(dutchText, time) -> DetectionEvent.ALARM
            else -> DetectionEvent.NONE
        }
    }

    override fun reset() {
        dutchRecognizer.reset()
    }

    override fun close() {
        dutchRecognizer.close()
        dutch.close()
    }
}