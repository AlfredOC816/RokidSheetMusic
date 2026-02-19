package com.rokidsheetmusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * RokidSheetMusic — MainActivity
 *
 * Display model:
 *   • PDF page is rendered at SCREEN WIDTH (fit-to-width zoom).
 *     This makes staff lines and notes large enough to read.
 *   • A vertical ScrollView lets the user pan through the page height.
 *   • Sensor gestures (GAME_ROTATION_VECTOR) control navigation:
 *       – Roll  (head tilt left/right) → previous / next PAGE
 *       – Pitch (head nod up/down)     → scroll UP / DOWN within page
 *
 * Orientation:
 *   • Manifest locks to LANDSCAPE — matching the Rokid AR1 waveguide
 *     display which is physically landscape (wider than tall).
 *   • In the emulator, run at 640×480 or any landscape size.
 *   • 480×640 portrait emulator will show portrait content — that does
 *     NOT match what the glasses look like; prefer 640×480 for testing.
 *
 * Colour:
 *   • PDF is rendered white-on-black (inverted from normal black-on-white).
 *     On waveguide AR: black = no light (transparent), white/grey = visible.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "SheetMusic"
        private const val PDF_ASSET_NAME = "sheet_music.pdf"

        // ── Gesture thresholds — tune on-device ─────────────────────────

        /** Roll angular velocity (rad/s) to trigger a page turn. */
        private const val PAGE_TURN_VELOCITY   = 1.8f

        /** Pitch angular velocity (rad/s) to trigger scrolling. */
        private const val SCROLL_VELOCITY_MIN  = 0.3f

        /** How many pixels to scroll per rad/s of pitch velocity. */
        private const val SCROLL_SENSITIVITY   = 120f

        /** Minimum ms between page turns (debounce). */
        private const val PAGE_COOLDOWN_MS     = 1500L

        /** Ignore sensor dt gaps larger than this (resume/init spikes). */
        private const val MAX_DT_MS            = 150f

        /** Render scale: 1 CSS pt → N pixels (higher = sharper, more RAM). */
        private const val RENDER_SCALE         = 2
    }

    // ── UI ───────────────────────────────────────────────────────────────
    private lateinit var scrollView:    ScrollView
    private lateinit var imageView:     ImageView
    private lateinit var pageIndicator: TextView
    private lateinit var gestureHint:   TextView
    private lateinit var scrollBar:     View
    private val uiHandler = Handler(Looper.getMainLooper())

    // ── PDF state ────────────────────────────────────────────────────────
    private var pdfRenderer:      PdfRenderer? = null
    private var currentPage:      PdfRenderer.Page? = null
    private var currentPageIndex  = 0
    private var pageCount         = 0
    private var renderedPageH     = 1   // rendered bitmap height in px (for scroll bar)

    // ── Sensor state ─────────────────────────────────────────────────────
    private lateinit var sensorManager:     SensorManager
    private var gameRotationSensor:         Sensor? = null
    private val rotationMatrix  = FloatArray(9)
    private val orientation     = FloatArray(3)

    private var lastRoll              = Float.NaN
    private var lastPitch             = Float.NaN
    private var lastTimestampNs       = 0L
    private var lastPageTurnMs        = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive, keep screen on
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        scrollView    = findViewById(R.id.scrollView)
        imageView     = findViewById(R.id.pdfImageView)
        pageIndicator = findViewById(R.id.pageIndicator)
        gestureHint   = findViewById(R.id.gestureHint)
        scrollBar     = findViewById(R.id.scrollBar)

        sensorManager      = getSystemService(SENSOR_SERVICE) as SensorManager
        gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        if (gameRotationSensor == null) {
            Log.w(TAG, "GAME_ROTATION_VECTOR not available — gestures disabled")
            showHint("⚠ No rotation sensor")
        }

        openPdf()
    }

    override fun onResume() {
        super.onResume()
        gameRotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // Reset sensor baselines to avoid stale-delta mis-fires on re-entry
        lastRoll  = Float.NaN
        lastPitch = Float.NaN
        lastTimestampNs = 0L
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
    }

    // ── PDF ───────────────────────────────────────────────────────────────

    private fun openPdf() {
        // PdfRenderer needs a seekable file descriptor — copy asset once
        val dest = File(filesDir, PDF_ASSET_NAME)
        if (!dest.exists()) {
            assets.open(PDF_ASSET_NAME).use { it.copyTo(dest.outputStream()) }
        }
        val fd = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(fd).also { pageCount = it.pageCount }
        showPage(0)
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= pageCount) return

        currentPage?.close()
        val page = renderer.openPage(index).also { currentPage = it }
        currentPageIndex = index

        // ── Fit-to-width zoom ────────────────────────────────────────────
        // Screen width drives the horizontal zoom: every point of the PDF
        // maps to (screenW / pdfW * RENDER_SCALE) pixels.
        val screenW = resources.displayMetrics.widthPixels
        val pdfW    = page.width.toFloat()
        val pdfH    = page.height.toFloat()

        val renderW = screenW * RENDER_SCALE
        val renderH = (pdfH / pdfW * renderW).toInt()
        renderedPageH = renderH / RENDER_SCALE   // logical pixels

        val bmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        // Invert: white paper → black, black ink → white
        // Black = no light = invisible on waveguide; white/grey = glows
        val inv = applyInvert(bmp)
        bmp.recycle()

        // Set ImageView width to match_parent, height to preserve aspect
        // (wrap_content + adjustViewBounds would work but this is explicit)
        imageView.layoutParams = (imageView.layoutParams as ViewGroup.LayoutParams).also {
            it.width  = ViewGroup.LayoutParams.MATCH_PARENT
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        imageView.setImageBitmap(inv)

        // Scroll to top of new page
        scrollView.post { scrollView.scrollTo(0, 0) }
        updateScrollBar()

        pageIndicator.text = "${index + 1} / $pageCount"
        Log.d(TAG, "Page ${index + 1}/$pageCount  renderW=$renderW renderH=$renderH")
    }

    private fun applyInvert(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f, -1f, 0f, 255f,
                 0f,  0f,  0f, 1f,   0f
            )))
        })
        return out
    }

    // ── Sensor ────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val roll  = orientation[2]   // tilt left/right → page turn
        val pitch = orientation[1]   // nod up/down     → scroll

        if (!lastRoll.isNaN() && lastTimestampNs != 0L) {
            val dtMs = (event.timestamp - lastTimestampNs) / 1_000_000f

            if (dtMs in 1f..MAX_DT_MS) {
                val dtSec     = dtMs / 1000f
                val rollVel   = (roll  - lastRoll)  / dtSec
                val pitchVel  = (pitch - lastPitch) / dtSec

                val now = System.currentTimeMillis()

                // ── Page turn (roll) ─────────────────────────────────────
                if (now - lastPageTurnMs > PAGE_COOLDOWN_MS) {
                    when {
                        rollVel >  PAGE_TURN_VELOCITY -> {
                            lastPageTurnMs = now
                            runOnUiThread { showPage(currentPageIndex + 1); showHint("▶ next page") }
                        }
                        rollVel < -PAGE_TURN_VELOCITY -> {
                            lastPageTurnMs = now
                            runOnUiThread { showPage(currentPageIndex - 1); showHint("◀ prev page") }
                        }
                    }
                }

                // ── Scroll (pitch) ───────────────────────────────────────
                // pitchVel > 0 → nodding down → scroll down (positive Y)
                if (Math.abs(pitchVel) > SCROLL_VELOCITY_MIN) {
                    val pixels = (pitchVel * SCROLL_SENSITIVITY * dtSec).toInt()
                    if (pixels != 0) {
                        runOnUiThread {
                            scrollView.scrollBy(0, pixels)
                            updateScrollBar()
                        }
                    }
                }
            }
        }

        lastRoll          = roll
        lastPitch         = pitch
        lastTimestampNs   = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Animate a tiny position marker on the right edge to show scroll depth. */
    private fun updateScrollBar() {
        scrollView.post {
            val screenH   = scrollView.height.toFloat()
            val totalH    = renderedPageH.toFloat()
            if (totalH <= screenH) { scrollBar.visibility = View.INVISIBLE; return@post }
            scrollBar.visibility = View.VISIBLE
            val scrollY   = scrollView.scrollY.toFloat()
            val maxScroll = totalH - screenH
            val frac      = (scrollY / maxScroll).coerceIn(0f, 1f)
            val barRange  = screenH - scrollBar.height
            scrollBar.translationY = frac * barRange
        }
    }

    private fun showHint(text: String) {
        gestureHint.text = text
        gestureHint.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ gestureHint.visibility = View.INVISIBLE }, 900)
    }
}
