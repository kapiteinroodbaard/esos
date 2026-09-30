package org.esos.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

object AlertAudioPlayer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingResources = ArrayDeque<Int>()
    private var appContext: Context? = null
    private var player: MediaPlayer? = null

    fun play(context: Context, vararg resources: Int) {
        onMainThread {
            releaseCurrent()
            pendingResources.clear()
            appContext = context.applicationContext
            resources.forEach(pendingResources::addLast)
            playNext()
        }
    }

    private fun playNext() {
        val context = appContext ?: return
        val resource = pendingResources.pollFirst() ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val next = runCatching { MediaPlayer.create(context, resource, attributes, 0) }.getOrNull()
        if (next == null) {
            playNext()
            return
        }
        player = next
        next.setOnCompletionListener {
            if (player === it) {
                player = null
                it.release()
                playNext()
            }
        }
        next.setOnErrorListener { failed, _, _ ->
            if (player === failed) player = null
            failed.release()
            playNext()
            true
        }
        try {
            next.start()
        } catch (_: RuntimeException) {
            if (player === next) player = null
            next.release()
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