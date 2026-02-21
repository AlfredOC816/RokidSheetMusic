package com.rokidsheetmusic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Metronome using AudioTrack (generated sine click, no asset files needed).
 *
 * Scheduling uses a Handler — accurate to ~1-2ms at 70 BPM, good enough
 * for visual playhead sync. For sub-millisecond accuracy, switch to a
 * dedicated AudioTrack write-loop.
 *
 * onBeat callback fires on the main thread with beatNum 1..beatsPerBar.
 * Beat 1 = downbeat (louder, higher pitch).
 */
class Metronome(var bpm: Int, val beatsPerBar: Int = 4) {

    private val handler   = Handler(Looper.getMainLooper())
    private var beatNum   = 0
    private var running   = false
    var onBeat: ((beat: Int) -> Unit)? = null

    val intervalMs: Long get() = 60_000L / bpm

    // ── Click sound generation ─────────────────────────────────────────

    private val sampleRate = 22_050
    private val strongClick by lazy { buildClick(freq = 880.0, durationMs = 22, amp = 30_000) }
    private val weakClick   by lazy { buildClick(freq = 660.0, durationMs = 16, amp = 20_000) }

    private fun buildClick(freq: Double, durationMs: Int, amp: Int): ShortArray {
        val n = sampleRate * durationMs / 1000
        return ShortArray(n) { i ->
            // Quick attack, exponential decay
            val env = if (i < 30) i / 30f else (1f - i.toFloat() / n)
            (env * amp * sin(2.0 * PI * freq * i / sampleRate)).toInt().toShort()
        }
    }

    private fun makeTrack(pcm: ShortArray): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { track ->
                track.write(pcm, 0, pcm.size)
            }
    }

    private fun playClick(strong: Boolean) {
        try {
            val pcm = if (strong) strongClick else weakClick
            val track = makeTrack(pcm)
            track.play()
            // Release on a short delay after playback
            handler.postDelayed({ track.release() }, 200)
        } catch (_: Exception) {}
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        beatNum = 0
        tick(0L)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun tick(delay: Long) {
        handler.postDelayed({
            if (!running) return@postDelayed
            beatNum = (beatNum % beatsPerBar) + 1
            playClick(beatNum == 1)
            onBeat?.invoke(beatNum)
            tick(intervalMs)
        }, delay)
    }
}
