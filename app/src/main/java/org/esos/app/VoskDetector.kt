package org.esos.app

import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class VoskDetector(
    dutchDirectory: File,
    private val matcher: PhraseMatcher = PhraseMatcher(),
    private val now: () -> Long = System::currentTimeMillis
) : OfflineDetector {
    private val dutch: Model
    private val dutchRecognizer: Recognizer

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        dutch = Model(dutchDirectory.absolutePath)
        try {
            dutchRecognizer = Recognizer(dutch, 16_000f, """["help mij","ik heb hulp nodig","[unk]"]""")
        } catch (error: Exception) {
            dutch.close()
            throw error
        }
    }

    override fun acceptPcm16(samples: ShortArray, count: Int): Boolean {
        require(count in 0..samples.size)
        val dutchComplete = dutchRecognizer.acceptWaveForm(samples, count)
        val dutchText = if (dutchComplete) JSONObject(dutchRecognizer.result).optString("text") else ""
        return matcher.matches(dutchText, now())
    }

    override fun reset() {
        dutchRecognizer.reset()
    }

    override fun close() {
        dutchRecognizer.close()
        dutch.close()
    }
}