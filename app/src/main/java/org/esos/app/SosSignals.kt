package org.esos.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

enum class AlarmSoundMode { HIGH_TONE, MORSE_SOS }

internal data class MorseSegment(val on: Boolean, val millis: Long)

internal object MorseSos {
    private const val DOT = 1
    private const val DASH = 3

    /** SOS (... --- ...) in standard morse timing; ends with the word gap so it can be looped. */
    fun segments(unitMillis: Long): List<MorseSegment> {
        val letters = listOf(listOf(DOT, DOT, DOT), listOf(DASH, DASH, DASH), listOf(DOT, DOT, DOT))
        val result = mutableListOf<MorseSegment>()
        letters.forEachIndexed { letterIndex, letter ->
            letter.forEachIndexed { index, length ->
                result += MorseSegment(true, length * unitMillis)
                if (index < letter.lastIndex) result += MorseSegment(false, unitMillis)
            }
            if (letterIndex < letters.lastIndex) result += MorseSegment(false, 3 * unitMillis)
        }
        result += MorseSegment(false, 7 * unitMillis)
        return result
    }

    /** Vibrator waveform timings: starts with an off period, then alternates off/on. */
    fun vibrationTimings(unitMillis: Long): LongArray {
        val timings = mutableListOf(0L)
        segments(unitMillis).forEach { segment ->
            val expectedOn = timings.size % 2 == 1
            if (segment.on == expectedOn) timings += segment.millis else timings[timings.lastIndex] += segment.millis
        }
        return timings.toLongArray()
    }
}

internal object AlarmTone {
    const val SAMPLE_RATE = 22_050
    private const val FREQUENCY_HZ = 3_000
    private const val BEEP_MILLIS = 400L
    private const val PAUSE_MILLIS = 200L
    private const val MORSE_UNIT_MILLIS = 200L

    fun segments(mode: AlarmSoundMode): List<MorseSegment> = when (mode) {
        AlarmSoundMode.HIGH_TONE -> listOf(MorseSegment(true, BEEP_MILLIS), MorseSegment(false, PAUSE_MILLIS))
        AlarmSoundMode.MORSE_SOS -> MorseSos.segments(MORSE_UNIT_MILLIS)
    }

    fun render(segments: List<MorseSegment>): ShortArray {
        val samples = ArrayList<Short>()
        val ramp = SAMPLE_RATE / 200
        segments.forEach { segment ->
            val count = (segment.millis * SAMPLE_RATE / 1000).toInt()
            for (i in 0 until count) {
                if (!segment.on) {
                    samples += 0
                    continue
                }
                val square = if ((i * FREQUENCY_HZ * 2L / SAMPLE_RATE) % 2L == 0L) 1.0 else -1.0
                val envelope = minOf(1.0, i.toDouble() / ramp, (count - i).toDouble() / ramp)
                samples += (square * envelope * Short.MAX_VALUE * 0.9).toInt().toShort()
            }
        }
        return samples.toShortArray()
    }
}

/** Piept elke seconde van de countdown als gesproken feedback uit staat; de laatste 10 s met een dubbele piep. */
object CountdownBeeper {
    private var tones: ToneGenerator? = null
    private var lastSecond = -1

    fun onTick(context: Context, remainingSeconds: Int) {
        if (AppSettings(context).spokenFeedbackEnabled || remainingSeconds <= 0 || remainingSeconds == lastSecond) return
        lastSecond = remainingSeconds
        val generator = tones ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }
            .getOrNull()?.also { tones = it } ?: return
        generator.startTone(if (remainingSeconds <= 10) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 150)
    }

    fun stop() {
        tones?.release()
        tones = null
        lastSecond = -1
    }
}

object SosSignals {
    private const val UNIT_MILLIS = 200L
    private var track: AudioTrack? = null
    private var savedAlarmVolume: Int? = null
    @Volatile var running = false
        private set

    /** One-shot morse vibration announcing the start of an alert, before the SOS phase begins. */
    fun announce(context: Context) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        if (settings.vibrationEnabled && !settings.vibrationContinuous) startVibration(app, false)
    }

    /** Starts the alarm sound and continuous vibration once SOS is really triggered. */
    fun start(context: Context, restoring: Boolean = false) {
        val app = context.applicationContext
        if (restoring && running) return
        running = true
        val settings = AppSettings(app)
        if (settings.alarmSoundEnabled) startSound(app, settings.alarmSoundMode)
        if (settings.vibrationEnabled && settings.vibrationContinuous) startVibration(app, true)
    }

    fun stop(context: Context) {
        running = false
        val app = context.applicationContext
        stopSound(app)
        app.getSystemService(Vibrator::class.java)?.cancel()
    }

    @Synchronized
    private fun startSound(context: Context, mode: AlarmSoundMode) {
        stopSound(context)
        runCatching {
            val pcm = AlarmTone.render(AlarmTone.segments(mode))
            val audio = context.getSystemService(AudioManager::class.java)
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val next = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AlarmTone.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            next.write(pcm, 0, pcm.size)
            next.setLoopPoints(0, pcm.size, -1)
            next.play()
            track = next
        }.onFailure { android.util.Log.e("SosSignals", "Unable to play alarm sound", it) }
    }

    @Synchronized
    private fun stopSound(context: Context) {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        savedAlarmVolume?.let {
            runCatching { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        }
        savedAlarmVolume = null
    }

    private fun startVibration(context: Context, continuous: Boolean) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            val timings = MorseSos.vibrationTimings(UNIT_MILLIS)
            val effect = VibrationEffect.createWaveform(timings, if (continuous) 0 else -1)
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        }.onFailure { android.util.Log.e("SosSignals", "Unable to vibrate", it) }
    }
}
