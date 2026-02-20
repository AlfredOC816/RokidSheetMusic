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
 * Monitors microphone RMS level in the background.
 *
 * Calls [onPlayerStopped] when silence lasts longer than [silenceBeatsToStop]
 * beats at the given BPM. Calls [onPlayerResumed] when sound comes back.
 *
 * Requires RECORD_AUDIO permission — check before calling start().
 *
 * Note: on some AR glasses, the mic may not be accessible from an app;
 * in that case hasPermission() returns true but no sound is detected.
 * The auto-pause feature degrades gracefully — if RMS stays near zero
 * it will just pause immediately. Use [enabled] to disable if not wanted.
 */
class AudioMonitor(
    private val context: Context,
    var bpm: Int,
    val silenceBeatsToStop: Int = 2,
    val onPlayerStopped:  () -> Unit,
    val onPlayerResumed:  () -> Unit,
) {
    companion object {
        private const val TAG         = "AudioMonitor"
        private const val SAMPLE_RATE = 16_000
        private const val SILENCE_RMS = 0.008f   // below this = silence
        private const val RESUME_RMS  = 0.025f   // above this = playing
    }

    var enabled = true

    private var recorder:     AudioRecord? = null
    private var monitorThread: Thread?     = null
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile private var monitoring = false
    @Volatile private var playerSilent = false

    fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!enabled || !hasPermission()) return
        stop()

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT),
            SAMPLE_RATE / 10 * 2  // 100ms of samples
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        ).also { it.startRecording() }

        monitoring = true
        playerSilent = false

        monitorThread = Thread {
            val buf = ShortArray(bufSize / 2)
            val silenceWindowMs = silenceBeatsToStop * 60_000L / bpm
            var silenceSinceMs = 0L
            var lastReadMs = System.currentTimeMillis()

            while (monitoring) {
                val read = recorder?.read(buf, 0, buf.size) ?: break
                if (read <= 0) continue

                val now    = System.currentTimeMillis()
                val dtMs   = now - lastReadMs
                lastReadMs = now

                // RMS of this buffer
                var sum = 0.0
                for (i in 0 until read) sum += (buf[i] / 32768.0) * (buf[i] / 32768.0)
                val rms = sqrt(sum / read).toFloat()

                if (rms < SILENCE_RMS) {
                    silenceSinceMs += dtMs
                    if (!playerSilent && silenceSinceMs >= silenceWindowMs) {
                        playerSilent = true
                        uiHandler.post { onPlayerStopped() }
                    }
                } else if (rms > RESUME_RMS) {
                    if (silenceSinceMs > 0 || playerSilent) {
                        silenceSinceMs = 0
                        if (playerSilent) {
                            playerSilent = false
                            uiHandler.post { onPlayerResumed() }
                        }
                    }
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "AudioMonitor"
            it.start()
        }

        Log.d(TAG, "Monitoring started (silence threshold: ${silenceBeatsToStop} beats @ ${bpm} BPM = ${silenceBeatsToStop * 60_000 / bpm}ms)")
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
