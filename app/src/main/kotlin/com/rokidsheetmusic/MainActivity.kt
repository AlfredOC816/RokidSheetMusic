package com.rokidsheetmusic

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.File

/**
 * RokidSheetMusic — MainActivity (v2: Metronome + Playhead)
 *
 * STATES:
 *   IDLE      — normal tab browsing, DPAD left/right navigates, up/down changes BPM
 *   COUNT_IN  — 4 metronome beats, big beat number flashes on screen
 *   PLAYING   — playhead sweeps top segment then bottom segment, then auto-advances
 *   PAUSED    — player stopped (silence detected), playhead frozen, waiting to resume
 *
 * CONTROLS:
 *   DPAD CENTER       — IDLE→COUNT_IN  |  PLAYING/PAUSED→IDLE (stop)
 *   DPAD LEFT/RIGHT   — manual prev/next page (any state)
 *   DPAD UP/DOWN      — BPM +5 / -5 (IDLE only)
 *   Head tilt (roll)  — prev/next page (any state, same as before)
 *
 * DISPLAY:
 *   Playhead = thin vertical green line sweeping left→right.
 *   Active row is full brightness; inactive row dimmed to 40%.
 *   Count-in: large beat number overlaid centre-screen.
 *   BPM shown top-left while IDLE.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG              = "SheetMusic"
        private const val BARS_PER_SEGMENT = 2       // bars in each pre-rendered segment
        private const val SEGMENTS_PER_PAGE = 2
        private const val DEFAULT_BPM      = 70
        private const val REQUEST_MIC      = 1001
        private const val PAGE_TURN_VEL    = 1.8f
        private const val PAGE_COOLDOWN_MS = 1500L
        private const val MAX_DT_MS        = 150f
        // Playhead colour — green-ish white, visible on waveguide
        private val PLAYHEAD_COLOR = Color.argb(220, 180, 255, 180)
        private val DIM_PAINT = Paint().apply { alpha = 102 }  // 40% opacity
    }

    enum class PlayState { IDLE, COUNT_IN, PLAYING, PAUSED }

    // ── UI ────────────────────────────────────────────────────────────────
    private lateinit var imageView:     ImageView
    private lateinit var pageIndicator: TextView
    private lateinit var gestureHint:   TextView
    private lateinit var bpmLabel:      TextView
    private lateinit var countInLabel:  TextView
    private val uiHandler = Handler(Looper.getMainLooper())

    // ── Playhead drawing ──────────────────────────────────────────────────
    private val playheadPaint = Paint().apply {
        color  = PLAYHEAD_COLOR
        strokeWidth = 3f
        isAntiAlias = true
    }
    /** 0..1 position of playhead within the active row (null = hidden) */
    private var playheadFraction  = 0f
    /** Which of the 2 stacked rows is active (0=top, 1=bottom) */
    private var activeRow         = 0
    private var playheadAnimator: ValueAnimator? = null

    // ── Segments ──────────────────────────────────────────────────────────
    private var segments     = listOf<Bitmap>()
    private var currentPage  = 0     // index of first segment on screen
    private var totalSegments = 0

    // ── Playback state ───────────────────────────────────────────────────
    private var playState    = PlayState.IDLE
    private var bpm          = DEFAULT_BPM
    private var beatsPerBar  = 4
    private var countInBeat  = 0

    private lateinit var metronome:    Metronome
    private lateinit var audioMonitor: AudioMonitor

    // ── Sensor ────────────────────────────────────────────────────────────
    private lateinit var sensorManager:    SensorManager
    private var gameRotationSensor:        Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation    = FloatArray(3)
    private var lastRoll          = Float.NaN
    private var lastTimestampNs   = 0L
    private var lastPageTurnMs    = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        imageView     = findViewById(R.id.pdfImageView)
        pageIndicator = findViewById(R.id.pageIndicator)
        gestureHint   = findViewById(R.id.gestureHint)
        bpmLabel      = findViewById(R.id.bpmLabel)
        countInLabel  = findViewById(R.id.countInLabel)

        sensorManager     = getSystemService(SENSOR_SERVICE) as SensorManager
        gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        loadTabMeta()
        loadSegments()
        showPage(0)

        metronome = Metronome(bpm, beatsPerBar).also { m ->
            m.onBeat = { beat -> onMetronomeBeat(beat) }
        }

        audioMonitor = AudioMonitor(
            context           = this,
            bpm               = bpm,
            silenceBeatsToStop = 2,
            onPlayerStopped   = { onPlayerStopped() },
            onPlayerResumed   = { onPlayerResumed() },
        )

        // Ask for mic permission upfront (gracefully degrades if denied)
        if (!audioMonitor.hasPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }

        updateBpmLabel()
    }

    override fun onResume() {
        super.onResume()
        gameRotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        lastRoll = Float.NaN
        lastTimestampNs = 0L
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        segments.forEach { it.recycle() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Permission result handled — AudioMonitor checks at start() time
    }

    // ── Tab meta + segments ───────────────────────────────────────────────

    private fun loadTabMeta() {
        try {
            val json = JSONObject(assets.open("woman_tab.json").bufferedReader().readText())
            bpm         = json.optInt("tempo", DEFAULT_BPM)
            val timeSig = json.optString("time", "4/4")
            beatsPerBar = timeSig.split("/").firstOrNull()?.toIntOrNull() ?: 4
            Log.d(TAG, "Tab meta: ${bpm} BPM, ${timeSig}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load tab meta, using defaults: $e")
        }
    }

    private fun loadSegments() {
        val list = mutableListOf<Bitmap>()
        var i = 1
        while (true) {
            try {
                val bmp = assets.open("tab_%03d.png".format(i)).use { BitmapFactory.decodeStream(it) }
                if (bmp != null) list.add(bmp) else break
            } catch (_: Exception) { break }
            i++
        }
        segments      = list
        totalSegments = segments.size
        Log.d(TAG, "Loaded $totalSegments segments")
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun showPage(startIndex: Int) {
        if (startIndex < 0 || startIndex >= totalSegments) return
        currentPage = startIndex

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        val count = minOf(SEGMENTS_PER_PAGE, totalSegments - startIndex)
        val slotH = h / SEGMENTS_PER_PAGE

        for (i in 0 until count) {
            val seg     = segments[startIndex + i]
            val slotTop = i * slotH
            val scale   = w.toFloat() / seg.width
            val scaledH = (seg.height * scale).toInt()
            val yOffset = slotTop + (slotH - scaledH) / 2
            val dst     = Rect(0, yOffset, w, yOffset + scaledH)

            when {
                // In PLAYING/PAUSED: dim the inactive row
                (playState == PlayState.PLAYING || playState == PlayState.PAUSED) && i != activeRow ->
                    canvas.drawBitmap(seg, null, dst, DIM_PAINT)
                else ->
                    canvas.drawBitmap(seg, null, dst, null)
            }
        }

        // Draw playhead line over active row
        if (playState == PlayState.PLAYING || playState == PlayState.PAUSED) {
            val x       = playheadFraction * w
            val rowTop  = activeRow * slotH
            val rowBot  = rowTop + slotH
            canvas.drawLine(x, rowTop.toFloat(), x, rowBot.toFloat(), playheadPaint)
        }

        imageView.setImageBitmap(bmp)

        val endSeg = startIndex + count
        pageIndicator.text = "${startIndex / SEGMENTS_PER_PAGE + 1} / ${(totalSegments + 1) / SEGMENTS_PER_PAGE}"
    }

    /** Re-draw current page (playhead position changed). */
    private fun refreshPlayhead() = showPage(currentPage)

    // ── Playback control ──────────────────────────────────────────────────

    private fun startCountIn() {
        if (playState != PlayState.IDLE) return
        playState    = PlayState.COUNT_IN
        countInBeat  = 0
        activeRow    = 0
        playheadFraction = 0f

        countInLabel.visibility = View.VISIBLE
        bpmLabel.visibility     = View.INVISIBLE

        metronome.bpm = bpm
        metronome.start()
    }

    private fun onMetronomeBeat(beat: Int) {
        when (playState) {
            PlayState.COUNT_IN -> {
                countInBeat++
                countInLabel.text = countInBeat.toString()
                if (countInBeat >= beatsPerBar) {
                    // Count-in done — start playhead
                    uiHandler.post { beginPlaying() }
                }
            }
            PlayState.PLAYING -> {
                // Metronome ticking — playhead driven by ValueAnimator, not by beats
                // (beats just provide the audio click; animator handles smooth visuals)
            }
            else -> {}
        }
    }

    private fun beginPlaying() {
        playState = PlayState.PLAYING
        countInLabel.visibility = View.INVISIBLE
        audioMonitor.bpm        = bpm
        audioMonitor.start()
        animateRow(0)
    }

    /**
     * Start a ValueAnimator sweeping the playhead across [row] (0=top, 1=bottom).
     * Duration = BARS_PER_SEGMENT bars at current BPM.
     */
    private fun animateRow(row: Int) {
        if (playState != PlayState.PLAYING) return
        activeRow        = row
        playheadFraction = 0f

        val durationMs = (BARS_PER_SEGMENT.toLong() * beatsPerBar * 60_000L / bpm)

        playheadAnimator?.cancel()
        playheadAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                playheadFraction = it.animatedValue as Float
                refreshPlayhead()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (playState != PlayState.PLAYING) return
                    if (row == 0) {
                        // Done with top row → animate bottom row
                        animateRow(1)
                    } else {
                        // Done with bottom row → advance page
                        val next = currentPage + SEGMENTS_PER_PAGE
                        if (next < totalSegments) {
                            showPage(next)
                            animateRow(0)
                        } else {
                            // Reached end of tab
                            stopPlayback()
                            showHint("✓ end of tab")
                        }
                    }
                }
            })
            start()
        }
    }

    private fun onPlayerStopped() {
        if (playState != PlayState.PLAYING) return
        playState = PlayState.PAUSED
        playheadAnimator?.pause()
        showHint("⏸ waiting…")
        Log.d(TAG, "Player paused (silence detected)")
    }

    private fun onPlayerResumed() {
        if (playState != PlayState.PAUSED) return
        playState = PlayState.PLAYING
        playheadAnimator?.resume()
        gestureHint.visibility = View.INVISIBLE
        Log.d(TAG, "Player resumed")
    }

    private fun stopPlayback() {
        playState = PlayState.IDLE
        metronome.stop()
        audioMonitor.stop()
        playheadAnimator?.cancel()
        playheadAnimator        = null
        playheadFraction        = 0f
        countInLabel.visibility = View.INVISIBLE
        bpmLabel.visibility     = View.VISIBLE
        refreshPlayhead()
    }

    private fun updateBpmLabel() {
        bpmLabel.text = "♩ $bpm"
    }

    // ── Input — DPAD (Rokid touchpad) ────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                when (playState) {
                    PlayState.IDLE    -> { startCountIn(); true }
                    else              -> { stopPlayback(); true }
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (playState == PlayState.IDLE) {
                    showPage(currentPage + SEGMENTS_PER_PAGE); showHint("▶")
                } else {
                    // Manual advance during playback — restart row animation
                    showPage(currentPage + SEGMENTS_PER_PAGE)
                    if (playState == PlayState.PLAYING) animateRow(0)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (playState == PlayState.IDLE) {
                    showPage(currentPage - SEGMENTS_PER_PAGE); showHint("◀")
                } else {
                    showPage(currentPage - SEGMENTS_PER_PAGE)
                    if (playState == PlayState.PLAYING) animateRow(0)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (playState == PlayState.IDLE) {
                    bpm = (bpm + 5).coerceAtMost(220)
                    metronome.bpm = bpm
                    updateBpmLabel()
                    showHint("♩ $bpm")
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (playState == PlayState.IDLE) {
                    bpm = (bpm - 5).coerceAtLeast(20)
                    metronome.bpm = bpm
                    updateBpmLabel()
                    showHint("♩ $bpm")
                }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── Sensor / roll gesture ─────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val roll = orientation[2]

        if (!lastRoll.isNaN() && lastTimestampNs != 0L) {
            val dtMs = (event.timestamp - lastTimestampNs) / 1_000_000f
            if (dtMs in 1f..MAX_DT_MS) {
                val rollVel = (roll - lastRoll) / (dtMs / 1000f)
                val now     = System.currentTimeMillis()
                if (now - lastPageTurnMs > PAGE_COOLDOWN_MS) {
                    when {
                        rollVel >  PAGE_TURN_VEL -> {
                            lastPageTurnMs = now
                            runOnUiThread {
                                showPage(currentPage + SEGMENTS_PER_PAGE)
                                if (playState == PlayState.PLAYING) animateRow(0)
                                showHint("▶")
                            }
                        }
                        rollVel < -PAGE_TURN_VEL -> {
                            lastPageTurnMs = now
                            runOnUiThread {
                                showPage(currentPage - SEGMENTS_PER_PAGE)
                                if (playState == PlayState.PLAYING) animateRow(0)
                                showHint("◀")
                            }
                        }
                    }
                }
            }
        }

        lastRoll        = roll
        lastTimestampNs = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun showHint(text: String) {
        gestureHint.text = text
        gestureHint.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ gestureHint.visibility = View.INVISIBLE }, 800)
    }
}
