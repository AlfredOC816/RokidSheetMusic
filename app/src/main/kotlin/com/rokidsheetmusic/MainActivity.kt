package com.rokidsheetmusic

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
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "SheetMusic"

        // Gesture thresholds
        private const val PAGE_TURN_VELOCITY = 1.8f
        private const val PAGE_COOLDOWN_MS   = 1500L
        private const val MAX_DT_MS          = 150f

        // Each screen shows 2 segments stacked top/bottom
        private const val SEGMENTS_PER_PAGE = 2
    }

    // UI
    private lateinit var imageView: ImageView
    private lateinit var pageIndicator: TextView
    private lateinit var gestureHint: TextView
    private val uiHandler = Handler(Looper.getMainLooper())

    // Tab image segments
    private var segments = listOf<Bitmap>()
    private var currentPage = 0   // index of first segment on current screen
    private var totalSegments = 0

    // Sensor state
    private lateinit var sensorManager: SensorManager
    private var gameRotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastRoll = Float.NaN
    private var lastTimestampNs = 0L
    private var lastPageTurnMs = 0L

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

        sensorManager      = getSystemService(SENSOR_SERVICE) as SensorManager
        gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        loadSegments()
        showPage(0)
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
    }

    // ── Tab loading ─────────────────────────────────────────────────────

    private fun loadSegments() {
        val list = mutableListOf<Bitmap>()
        var i = 1
        while (true) {
            val name = "tab_%03d.png".format(i)
            try {
                val bmp = assets.open(name).use { BitmapFactory.decodeStream(it) }
                if (bmp != null) list.add(bmp) else break
            } catch (_: Exception) {
                break
            }
            i++
        }
        segments = list
        totalSegments = segments.size
        Log.d(TAG, "Loaded $totalSegments tab segments")
    }

    // ── Tab rendering (2 segments stacked top/bottom) ─────────────────

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
            val seg = segments[startIndex + i]
            val slotTop = i * slotH

            // Scale segment to fit slot width, centered vertically in slot
            val scale = w.toFloat() / seg.width
            val scaledH = (seg.height * scale).toInt()
            val yOffset = slotTop + (slotH - scaledH) / 2

            val dst = Rect(0, yOffset, w, yOffset + scaledH)
            canvas.drawBitmap(seg, null, dst, null)
        }

        imageView.setImageBitmap(bmp)
        val endSeg = startIndex + count
        pageIndicator.text = "${startIndex / SEGMENTS_PER_PAGE + 1} / ${(totalSegments + 1) / SEGMENTS_PER_PAGE}"
        Log.d(TAG, "Segments ${startIndex + 1}–$endSeg of $totalSegments")

        // Debug: save bitmap for screencap
        try {
            val f = java.io.File(filesDir, "debug_render.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {}
    }

    // ── Sensor ──────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val roll = orientation[2]

        if (!lastRoll.isNaN() && lastTimestampNs != 0L) {
            val dtMs = (event.timestamp - lastTimestampNs) / 1_000_000f
            if (dtMs in 1f..MAX_DT_MS) {
                val rollVel = (roll - lastRoll) / (dtMs / 1000f)
                val now = System.currentTimeMillis()

                if (now - lastPageTurnMs > PAGE_COOLDOWN_MS) {
                    when {
                        rollVel > PAGE_TURN_VELOCITY -> {
                            lastPageTurnMs = now
                            runOnUiThread { showPage(currentPage + SEGMENTS_PER_PAGE); showHint("▶") }
                        }
                        rollVel < -PAGE_TURN_VELOCITY -> {
                            lastPageTurnMs = now
                            runOnUiThread { showPage(currentPage - SEGMENTS_PER_PAGE); showHint("◀") }
                        }
                    }
                }
            }
        }

        lastRoll = roll
        lastTimestampNs = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ── Touchpad ────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> {
                showPage(currentPage + SEGMENTS_PER_PAGE); showHint("▶"); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showPage(currentPage - SEGMENTS_PER_PAGE); showHint("◀"); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun showHint(text: String) {
        gestureHint.text = text
        gestureHint.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ gestureHint.visibility = View.INVISIBLE }, 600)
    }
}
