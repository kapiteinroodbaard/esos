package org.esos.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

object AlertAudioPlayer {
    private data class Announcement(val resource: Int, val onComplete: () -> Unit)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingResources = ArrayDeque<Announcement>()
    private var appContext: Context? = null
    private var player: MediaPlayer? = null

    fun play(context: Context, vararg resources: Int) {
        if (!AppSettings(context).spokenFeedbackEnabled) return
        onMainThread {
            releaseCurrent()
            pendingResources.clear()
            appContext = context.applicationContext
            resources.forEach { pendingResources.addLast(Announcement(it, {})) }
            playNext()
        }
    }

    fun enqueue(context: Context, resource: Int, onComplete: () -> Unit = {}) {
        if (!AppSettings(context).spokenFeedbackEnabled) {
            onMainThread(onComplete)
            return
        }
        onMainThread {
            appContext = context.applicationContext
            pendingResources.addLast(Announcement(resource, onComplete))
            if (player == null) playNext()
        }
    }

    private fun playNext() {
        val context = appContext ?: return
        val announcement = pendingResources.pollFirst() ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val next = runCatching { MediaPlayer.create(context, announcement.resource, attributes, 0) }.getOrNull()
        if (next == null) {
            announcement.onComplete()
            playNext()
            return
        }
        player = next
        next.setOnCompletionListener {
            if (player === it) {
                player = null
                it.release()
                announcement.onComplete()
                playNext()
            }
        }
        next.setOnErrorListener { failed, _, _ ->
            if (player === failed) {
                player = null
                failed.release()
                announcement.onComplete()
                playNext()
            }
            true
        }
        try {
            next.start()
        } catch (_: RuntimeException) {
            if (player === next) player = null
            next.release()
            announcement.onComplete()
            playNext()
        }
    }

    private fun releaseCurrent() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}