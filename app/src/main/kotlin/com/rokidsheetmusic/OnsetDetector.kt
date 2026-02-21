package com.rokidsheetmusic

import kotlin.math.sqrt

/**
 * Simple energy-based onset detector for strummed/plucked instruments.
 *
 * Algorithm:
 *   1. Compute RMS of each short audio frame (~10ms)
 *   2. Onset = RMS rises sharply (ratio > threshold) AND exceeds minimum level
 *   3. Debounce: ignore onsets within MIN_INTERVAL_MS of the last one
 *   4. Track rolling median of inter-onset intervals → estimated player BPM
 *
 * Works well for ukulele/guitar strums. Won't chase individual picked notes
 * at fast tempos (debounce filters those), which is what we want — we're
 * tracking beat-level strums, not individual notes.
 *
 * [onBpmUpdate] fires on the calling thread (AudioRecord thread) — post to
 * UI thread before touching views.
 */
class OnsetDetector(
    private val sampleRate: Int = 16_000,
    private val onBpmUpdate: (bpm: Float) -> Unit,
) {
    companion object {
        private const val MIN_ONSET_INTERVAL_MS = 150L   // 400 BPM max — filters pick noise
        private const val ONSET_ENERGY_RATIO    = 1.55f  // RMS must jump by this factor
        private const val ONSET_MIN_RMS         = 0.018f // ignore background noise
        private const val HISTORY_SIZE          = 6      // inter-onset intervals to average
        private const val MIN_BPM               = 30f
        private const val MAX_BPM               = 240f
    }

    private var smoothedRms      = 0f
    private var lastOnsetTimeMs  = 0L
    private val intervalHistory  = ArrayDeque<Long>()

    /**
     * Feed raw 16-bit PCM samples. Call from the AudioRecord read loop.
     * [nowMs] = System.currentTimeMillis() at start of this buffer.
     */
    fun process(samples: ShortArray, count: Int, nowMs: Long) {
        // RMS of this buffer
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32_768.0
            sum += s * s
        }
        val rms = sqrt(sum / count).toFloat()

        // Onset detection: sudden energy spike above background
        val isOnset = rms > ONSET_MIN_RMS &&
                      rms > smoothedRms * ONSET_ENERGY_RATIO &&
                      (nowMs - lastOnsetTimeMs) >= MIN_ONSET_INTERVAL_MS

        if (isOnset) {
            val interval = nowMs - lastOnsetTimeMs
            lastOnsetTimeMs = nowMs

            if (interval in MIN_ONSET_INTERVAL_MS..2_500L) {
                // Only accept intervals implying BPM in sane range
                val impliedBpm = 60_000f / interval
                if (impliedBpm in MIN_BPM..MAX_BPM) {
                    intervalHistory.addLast(interval)
                    if (intervalHistory.size > HISTORY_SIZE) intervalHistory.removeFirst()

                    if (intervalHistory.size >= 2) {
                        val median = intervalHistory.sorted()[intervalHistory.size / 2]
                        onBpmUpdate(60_000f / median)
                    }
                }
            }
        }

        // Slow-tracking envelope (don't let it decay faster than onsets can rise)
        smoothedRms = if (rms > smoothedRms)
            0.6f * smoothedRms + 0.4f * rms   // fast attack
        else
            0.92f * smoothedRms + 0.08f * rms  // slow decay
    }

    fun reset() {
        smoothedRms     = 0f
        lastOnsetTimeMs = 0L
        intervalHistory.clear()
    }
}
