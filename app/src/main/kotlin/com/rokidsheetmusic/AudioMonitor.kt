package com.rokidsheetmusic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Monitors microphone audio for two purposes:
 *
 * 1. **Silence detection** — calls [onPlayerStopped] when the player stops
 *    playing for longer than [silenceBeatsToStop] beats, and [onPlayerResumed]
 *    when they come back. Used to pause/resume the playhead.
 *
 * 2. **Onset detection** — feeds raw audio through [OnsetDetector] to estimate
 *    the player's actual BPM. Calls [onPlayerBpmUpdate] with the rolling estimate.
 *
 * All callbacks fire on the main (UI) thread.
 */
class AudioMonitor(
    private val context: Context,
    var bpm: Int,
    val silenceBeatsToStop: Int   = 2,
    val onPlayerStopped:    () -> Unit,
    val onPlayerResumed:    () -> Unit,
    val onPlayerBpmUpdate:  (bpm: Float) -> Unit = {},
) {
    companion object {
        private const val TAG         = "AudioMonitor"
        private const val SAMPLE_RATE = 16_000
        private const val SILENCE_RMS = 0.008f
        private const val RESUME_RMS  = 0.025f
    }

    var enabled = true

    private var recorder:      AudioRecord? = null
    private var monitorThread: Thread?      = null
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile private var monitoring   = false
    @Volatile private var playerSilent = false

    private val onsetDetector = OnsetDetector(
        sampleRate   = SAMPLE_RATE,
        onBpmUpdate  = { bpm -> uiHandler.post { onPlayerBpmUpdate(bpm) } },
    )

    fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!enabled || !hasPermission()) return
        stop()
        onsetDetector.reset()

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT),
            SAMPLE_RATE / 10 * 2   // 100ms of audio
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        ).also { it.startRecording() }

        monitoring   = true
        playerSilent = false

        monitorThread = Thread {
            val buf           = ShortArray(bufSize / 2)
            val silenceMs     = silenceBeatsToStop * 60_000L / bpm
            var silenceSince  = 0L
            var lastReadMs    = System.currentTimeMillis()

            while (monitoring) {
                val read = recorder?.read(buf, 0, buf.size) ?: break
                if (read <= 0) continue

                val nowMs  = System.currentTimeMillis()
                val dtMs   = nowMs - lastReadMs
                lastReadMs = nowMs

                // RMS for silence detection
                var sum = 0.0
                for (i in 0 until read) {
                    val s = buf[i] / 32_768.0
                    sum += s * s
                }
                val rms = sqrt(sum / read).toFloat()

                // Onset detection (runs on same thread, callback posts to UI)
                onsetDetector.process(buf, read, nowMs)

                // Silence / resume logic
                if (rms < SILENCE_RMS) {
                    silenceSince += dtMs
                    if (!playerSilent && silenceSince >= silenceMs) {
                        playerSilent = true
                        uiHandler.post { onPlayerStopped() }
                    }
                } else if (rms > RESUME_RMS) {
                    silenceSince = 0
                    if (playerSilent) {
                        playerSilent = false
                        uiHandler.post { onPlayerResumed() }
                    }
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "AudioMonitor"
            it.start()
        }

        Log.d(TAG, "Monitoring started — silence window: ${silenceBeatsToStop} beats @ ${bpm} BPM = ${silenceBeatsToStop * 60_000 / bpm}ms")
    }

    fun stop() {
        monitoring = false
        monitorThread?.interrupt()
        monitorThread = null
        recorder?.stop()
        recorder?.release()
        recorder = null
    }
}
