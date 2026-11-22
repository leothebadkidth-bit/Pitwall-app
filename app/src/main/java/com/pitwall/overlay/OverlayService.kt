package com.pitwall.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var expandedPanel: LinearLayout
    private lateinit var miniBubble: TextView
    private lateinit var telemetryTxt: TextView
    private lateinit var radioTxt: TextView
    private lateinit var scanStatusTxt: TextView

    private var tts: TextToSpeech? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    private var currentLap = 1
    private var totalLaps = 0
    private var tireWear = 100
    private var compound = "SOFT"
    private var mode = "BAL"
    private var boxQueued = false
    private var isScanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        tts = TextToSpeech(this, this)
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            startScreenCapture()
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            speak("Auto vision online. Monitoring screen.")
        }
    }

    private fun speak(msg: String) {
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "pitwall_radio")
    }

    private fun startForegroundNotif() {
        val channelId = "pitwall_hud_channel"
        val channel = NotificationChannel(channelId, "Pitwall Vision HUD", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = android.app.Notification.Builder(this, channelId)
            .setContentTitle("Pitwall Auto-Vision Active")
            .setContentText("กำลังสแกนค่าจากหน้าจอเกมอัตโนมัติ")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun startScreenCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        // Downscale by 2 for fast OCR performance
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PitwallCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isScanning = true
        scanStatusTxt.text = "👁️ AUTO-SCAN: ON"
        scanStatusTxt.setTextColor(Color.parseColor("#00D2BE"))
        scheduleNextScan()
    }

    private fun scheduleNextScan() {
        if (!isScanning) return
        handler.postDelayed({
            captureAndAnalyze()
            scheduleNextScan()
        }, 2000) // Scan every 2 seconds
    }

    private fun captureAndAnalyze() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()

            val inputImage = InputImage.fromBitmap(cropped, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    parseGameTelemetry(visionText.text)
                    cropped.recycle()
                }
                .addOnFailureListener {
                    cropped.recycle()
                }
        } catch (e: Exception) {
            // Ignore frame drop
        } finally {
            image.close()
        }
    }

    private fun parseGameTelemetry(rawText: String) {
        val upper = rawText.uppercase(Locale.US)
        // Look for Lap pattern like "1/25", "LAP 3/20", "3 / 15"
        val lapMatcher = Pattern.compile("(?:LAP\\s*)?(\\d{1,2})\\s*/\\s*(\\d{1,2})").matcher(upper)
        if (lapMatcher.find()) {
            val detectedLap = lapMatcher.group(1)?.toIntOrNull() ?: currentLap
            val detectedTotal = lapMatcher.group(2)?.toIntOrNull() ?: totalLaps

            if (detectedTotal in 3..78 && detectedLap in 1..detectedTotal) {
                totalLaps = detectedTotal
                if (detectedLap > currentLap) {
                    onNewLapDetected(detectedLap)
                } else if (detectedLap == 1 && currentLap > 2) {
                    // New race restarted
                    currentLap = 1
                    tireWear = 100
                    boxQueued = false
                    radioTxt.text = "📻 New race detected! Lights out!"
                    speak("New race started.")
                }
                updateDisplay()
            }
        }
    }

    private fun onNewLapDetected(newLap: Int) {
        val lapsPassed = (newLap - currentLap).coerceAtLeast(1)
        currentLap = newLap

        val wearPerLap = when (compound) {
            "SOFT" -> if (mode == "PUSH") 8 else 6
            "MED" -> if (mode == "PUSH") 6 else 4
            else -> 3
        }

        if (boxQueued) {
            tireWear = 100
            boxQueued = false
            compound = if (compound == "SOFT") "MED" else "HARD"
            radioTxt.text = "📻 Pit stop done! New $compound"
            speak("Pit stop complete. Push now.")
        } else {
            tireWear = (tireWear - (wearPerLap * lapsPassed)).coerceAtLeast(0)
            if (tireWear in 22..30) {
                radioTxt.text = "⚠️ ยางเริ่มหมด ($tireWear%) เตรียมเข้าพิท"
                speak("Tires at $tireWear percent. Consider boxing.")
            } else if (tireWear < 22) {
                radioTxt.text = "🚨 ยางวิกฤต ($tireWear%) เข้าพิทด่วน!"
                speak("Critical tire wear! Box this lap!")
            } else {
                radioTxt.text = "📻 Lap $currentLap synced ($mode)"
            }
        }
    }

    private fun updateDisplay() {
        val lapStr = if (totalLaps > 0) "LAP $currentLap/$totalLaps" else "LAP $currentLap"
        telemetryTxt.text = "$lapStr | TIRE: $tireWear% ($compound)"
        telemetryTxt.setTextColor(
            when {
                tireWear <= 25 -> Color.parseColor("#FF3B30")
                tireWear <= 50 -> Color.parseColor("#FFD700")
                else -> Color.WHITE
            }
        )
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 140
        }

        rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        miniBubble = TextView(this).apply {
            text = "🏎️ PIT"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(24, 14, 24, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6E10600"))
                cornerRadius = 32f
            }
            visibility = View.GONE
            setOnClickListener {
                visibility = View.GONE
                expandedPanel.visibility = View.VISIBLE
            }
        }

        expandedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC12141C"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#E10600"))
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        scanStatusTxt = TextView(this).apply {
            text = "👁️ SCAN: WAIT  "
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
        }

        val btnMin = TextView(this).apply {
            text = " [ย่อจอ] "
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.YELLOW)
            setOnClickListener {
                expandedPanel.visibility = View.GONE
                miniBubble.visibility = View.VISIBLE
            }
        }

        headerRow.addView(scanStatusTxt)
        headerRow.addView(btnMin)

        telemetryTxt = TextView(this).apply {
            text = "LAP 1 | TIRE: 100% (SOFT)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
        }

        radioTxt = TextView(this).apply {
            text = "📻 รอสแกนเลขรอบจากหน้าจอเกม..."
            textSize = 11f
            setTextColor(Color.parseColor("#FFD700"))
            setPadding(0, 0, 0, 10)
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btnReset = Button(this).apply {
            text = "RESET"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#333847"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                currentLap = 1
                tireWear = 100
                compound = "SOFT"
                boxQueued = false
                radioTxt.text = "📻 รีเซ็ตเริ่มรอบ 1 ใหม่แล้ว"
                updateDisplay()
            }
        }

        val btnMode = Button(this).apply {
            text = "MODE: BAL"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#252836"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                mode = when (mode) {
                    "BAL" -> "PUSH"
                    "PUSH" -> "SAVE"
                    else -> "BAL"
                }
                text = "MODE: $mode"
                radioTxt.text = "📻 Engine mode: $mode"
            }
        }

        val btnBox = Button(this).apply {
            text = "BOX"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#00897B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                boxQueued = !boxQueued
                if (boxQueued) {
                    text = "BOX ✅"
                    radioTxt.text = "📻 Box confirmed this lap!"
                    speak("Copy, box this lap.")
                } else {
                    text = "BOX"
                    radioTxt.text = "📻 Stay out, stay out."
                }
            }
        }

        btnRow.addView(btnReset)
        btnRow.addView(btnMode)
        btnRow.addView(btnBox)

        expandedPanel.addView(headerRow)
        expandedPanel.addView(telemetryTxt)
        expandedPanel.addView(radioTxt)
        expandedPanel.addView(btnRow)

        rootView.addView(miniBubble)
        rootView.addView(expandedPanel)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(rootView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(rootView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        tts?.shutdown()
        if (::rootView.isInitialized) {
            windowManager.removeView(rootView)
        }
    }
}
