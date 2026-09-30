package org.esos.app

import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class VoskDetector(
    dutchDirectory: File,
    englishDirectory: File,
    private val matcher: PhraseMatcher = PhraseMatcher(),
    private val now: () -> Long = System::currentTimeMillis
) : OfflineDetector {
    private val dutch: Model
    private val english: Model
    private val dutchRecognizer: Recognizer
    private val englishRecognizer: Recognizer

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        dutch = Model(dutchDirectory.absolutePath)
        try {
            english = Model(englishDirectory.absolutePath)
        } catch (error: Exception) {
            dutch.close()
            throw error
        }
        try {
            dutchRecognizer = Recognizer(dutch, 16_000f, """["help help","help mij","ik heb hulp nodig","[unk]"]""")
            englishRecognizer = try {
                Recognizer(english, 16_000f, """["help help","help me","i need help","[unk]"]""")
            } catch (error: Exception) {
                dutchRecognizer.close()
                throw error
            }
        } catch (error: Exception) {
            english.close()
            dutch.close()
            throw error
        }
    }

    override fun acceptPcm16(samples: ShortArray, count: Int): Boolean {
        require(count in 0..samples.size)
        val dutchComplete = dutchRecognizer.acceptWaveForm(samples, count)
        val englishComplete = englishRecognizer.acceptWaveForm(samples, count)
        val dutchText = if (dutchComplete) JSONObject(dutchRecognizer.result).optString("text") else ""
        val englishText = if (englishComplete) JSONObject(englishRecognizer.result).optString("text") else ""
        return matcher.matches(dutchText, now()) || matcher.matches(englishText, now())
    }

    override fun reset() {
        dutchRecognizer.reset()
        englishRecognizer.reset()
    }

    override fun close() {
        englishRecognizer.close()
        dutchRecognizer.close()
        english.close()
        dutch.close()
    }
}