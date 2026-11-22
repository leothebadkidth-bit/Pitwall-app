package com.pitwall.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
    private lateinit var subTelemetryTxt: TextView
    private lateinit var radioTxt: TextView
    private lateinit var scanStatusTxt: TextView

    private var tts: TextToSpeech? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    // สถานะการแข่งจาก Monoposto
    private var driverTag = "MAI"
    private var currentLap = 1
    private var totalLaps = 0
    private var currentPos = "--"
    private var tireTemp = "--"
    private var bestLapTime = "--:--.---"
    private var isSafetyCar = false
    private var tireWear = 100
    private var compound = "SOFT"
    private var mode = "BAL"
    private var boxQueued = false

    private var isScanning = false
    private var isProcessingFrame = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                startScreenCapture()
            } catch (e: Exception) {
                radioTxt.text = "⚠️ กล้องสแกนขัดข้อง: ใช้ปุ่ม +1 แทนได้"
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isScanning && mediaProjection != null) {
            handler.postDelayed({ setupVirtualDisplay() }, 350)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            speak("Monoposto telemetry linked. Ready for lights out.")
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
            .setContentTitle("Pitwall Monoposto Telemetry")
            .setContentText("กำลังอ่านค่าจากหน้าจอ Monoposto")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun startScreenCapture() {
        val projection = mediaProjection ?: return

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                isScanning = false
                handler.removeCallbacksAndMessages(null)
                virtualDisplay?.release()
                imageReader?.close()
                if (::scanStatusTxt.isInitialized) {
                    scanStatusTxt.text = "👁️ SCAN: OFF  "
                    scanStatusTxt.setTextColor(Color.LTGRAY)
                }
            }
        }, handler)

        setupVirtualDisplay()
        isScanning = true
        scanStatusTxt.text = "👁️ MONOPOSTO SYNC  "
        scanStatusTxt.setTextColor(Color.parseColor("#00D2BE"))
        scheduleNextScan()
    }

    private fun setupVirtualDisplay() {
        val projection = mediaProjection ?: return
        virtualDisplay?.release()
        imageReader?.close()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "PitwallCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    private fun scheduleNextScan() {
        if (!isScanning) return
        handler.postDelayed({
            captureAndAnalyze()
            scheduleNextScan()
        }, 1200)
    }

    private fun captureAndAnalyze() {
        if (isProcessingFrame) return
        val image = imageReader?.acquireLatestImage() ?: return
        isProcessingFrame = true

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            val fullScreen = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
            rawBitmap.recycle()

            val w = fullScreen.width
            val h = fullScreen.height

            // รอจนกว่าจะเข้าเกมแนวนอน
            if (w <= h || w < 400 || h < 200) {
                fullScreen.recycle()
                isProcessingFrame = false
                return
            }

            // ตัดเฉพาะ 3 โซนข้อมูลของ Monoposto มารวมในภาพเดียวเพื่อความแม่นยำสูงสุด:
            // โซน 1: หัวตารางซ้ายบน (LAP 1/15, SAFETY CAR) -> ขยาย 2 เท่าให้อ่านตัวเลขห้อยท้ายชัด
            val headerW = (w * 0.28f).toInt()
            val headerH = (h * 0.16f).toInt()

            // โซน 2: รายชื่อนักขับฝั่งซ้าย (01..22 + MAI)
            val towerY = (h * 0.11f).toInt()
            val towerW = (w * 0.24f).toInt()
            val towerH = (h * 0.78f).toInt()

            // โซน 3: กล่องเวลาและอุณหภูมิยางฝั่งขวา (BEST TIME, 100°)
            val rightX = (w * 0.72f).toInt()
            val rightY = (h * 0.12f).toInt()
            val rightW = (w * 0.28f).toInt()
            val rightH = (h * 0.42f).toInt()

            val stitchedW = (headerW * 2) + rightW + 40
            val stitchedH = maxOf((headerH * 2) + towerH + 40, rightH + 40)
            val stitched = Bitmap.createBitmap(stitchedW, stitchedH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stitched)
            canvas.drawColor(Color.BLACK)

            // วาดโซน 1 (ขยาย 2x)
            canvas.drawBitmap(
                fullScreen,
                Rect(0, 0, headerW, headerH),
                Rect(0, 0, headerW * 2, headerH * 2),
                null
            )
            // วาดโซน 2 (รายชื่อนักขับใต้หัวตาราง)
            canvas.drawBitmap(
                fullScreen,
                Rect(0, towerY, towerW, towerY + towerH),
                Rect(0, (headerH * 2) + 20, towerW, (headerH * 2) + 20 + towerH),
                null
            )
            // วาดโซน 3 (เทเลเมทรีฝั่งขวา)
            canvas.drawBitmap(
                fullScreen,
                Rect(rightX, rightY, rightX + rightW, rightY + rightH),
                Rect((headerW * 2) + 20, 0, (headerW * 2) + 20 + rightW, rightH),
                null
            )

            fullScreen.recycle()

            val inputImage = InputImage.fromBitmap(stitched, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    parseMonopostoTelemetry(visionText.text)
                    stitched.recycle()
                    isProcessingFrame = false
                }
                .addOnFailureListener {
                    stitched.recycle()
                    isProcessingFrame = false
                }
        } catch (e: Exception) {
            isProcessingFrame = false
        } finally {
            image.close()
        }
    }

    private fun parseMonopostoTelemetry(rawText: String) {
        val upper = rawText.uppercase(Locale.US)

        // 1. ตรวจจับ Safety Car
        val scNow = upper.contains("SAFETY CAR")
        if (scNow && !isSafetyCar) {
            isSafetyCar = true
            radioTxt.text = "🟡 SAFETY CAR! โอกาสทองเข้าพิทประหยัดเวลา"
            speak("Safety car deployed! Good window to box.")
        } else if (!scNow && isSafetyCar) {
            isSafetyCar = false
            radioTxt.text = "🟢 Green flag! Safety car in this lap."
            speak("Safety car in this lap. Get ready to push.")
        }

        // 2. อ่านเลขรอบ LAP X/Y (รองรับตัวเลขห้อยท้าย /15, /12)
        val lapFullMatcher = Pattern.compile("LAP\\s*(\\d{1,2})\\s*[/|\\\\I1]\\s*(\\d{1,2})").matcher(upper)
        val lapSingleMatcher = Pattern.compile("LAP\\s*(\\d{1,2})\\s*[/|\\\\]").matcher(upper)

        var detectedLap: Int? = null
        if (lapFullMatcher.find()) {
            val l = lapFullMatcher.group(1)?.toIntOrNull()
            val t = lapFullMatcher.group(2)?.toIntOrNull()
            if (l != null && t != null && t in 3..78 && l in 1..t) {
                detectedLap = l
                totalLaps = t
            }
        } else if (lapSingleMatcher.find()) {
            val l = lapSingleMatcher.group(1)?.toIntOrNull()
            if (l != null && l in 1..78) {
                detectedLap = l
            }
        }

        if (detectedLap != null) {
            if (detectedLap > currentLap && (detectedLap - currentLap) <= 3) {
                onNewLapDetected(detectedLap)
            } else if (detectedLap == 1 && currentLap > 2) {
                currentLap = 1
                tireWear = 100
                boxQueued = false
                radioTxt.text = "📻 Lights out! เริ่มแข่งใหม่ยาง 100%"
                speak("Lights out and away we go!")
            }
        }

        // 3. ค้นหาอันดับของรถผู้เล่น (MAI) จากตารางฝั่งซ้าย
        val posMatcher = Pattern.compile("(\\d{1,2})\\s*$driverTag\\b").matcher(upper)
        if (posMatcher.find()) {
            val posNum = posMatcher.group(1)?.toIntOrNull()
            if (posNum != null && posNum in 1..22) {
                currentPos = "P$posNum"
            }
        }

        // 4. อ่านอุณหภูมิยาง (เช่น 80°, 100°) จากกล่องขวา
        val tempMatcher = Pattern.compile("(\\d{2,3})\\s*[°º]").matcher(upper)
        if (tempMatcher.find()) {
            val tVal = tempMatcher.group(1)?.toIntOrNull()
            if (tVal != null && tVal in 30..145) {
                tireTemp = "${tVal}°C"
            }
        }

        // 5. อ่าน BEST TIME (เช่น 01:32.044)
        val timeMatcher = Pattern.compile("BEST\\s*TIME[^0-9]*(\\d{2}:\\d{2}\\.\\d{3})").matcher(upper)
        if (timeMatcher.find()) {
            bestLapTime = timeMatcher.group(1) ?: bestLapTime
        }

        updateDisplay()
    }

    private fun onNewLapDetected(newLap: Int) {
        val lapsPassed = (newLap - currentLap).coerceAtLeast(1)
        currentLap = newLap

        var wearPerLap = when (compound) {
            "SOFT" -> if (mode == "PUSH") 8 else 6
            "MED" -> if (mode == "PUSH") 6 else 4
            else -> 3
        }
        // ช่วง Safety Car ยางสึกน้อยลง 60%
        if (isSafetyCar) {
            wearPerLap = (wearPerLap * 0.4).toInt().coerceAtLeast(1)
        }

        if (boxQueued) {
            tireWear = 100
            boxQueued = false
            compound = if (compound == "SOFT") "MED" else "HARD"
            radioTxt.text = "📻 Pit stop done! เปลี่ยนยาง $compound 100%"
            speak("Pit stop complete. Push now.")
        } else {
            tireWear = (tireWear - (wearPerLap * lapsPassed)).coerceAtLeast(0)
            if (tireWear in 22..30) {
                radioTxt.text = "⚠️ ยางเหลือ $tireWear% เตรียมเข้าพิท"
                speak("Tires at $tireWear percent. Consider boxing.")
            } else if (tireWear < 22) {
                radioTxt.text = "🚨 ยางวิกฤต ($tireWear%) เข้าพิทด่วน!"
                speak("Critical tire wear! Box this lap!")
            } else {
                radioTxt.text = "📻 Lap $currentLap ($currentPos) | จังหวะดีมาก"
            }
        }
    }

    private fun updateDisplay() {
        val lapStr = if (totalLaps > 0) "LAP $currentLap/$totalLaps" else "LAP $currentLap"
        telemetryTxt.text = "$currentPos | $lapStr | TIRE: $tireWear% ($compound)"
        telemetryTxt.setTextColor(
            when {
                tireWear <= 25 -> Color.parseColor("#FF3B30")
                tireWear <= 50 -> Color.parseColor("#FFD700")
                else -> Color.WHITE
            }
        )
        val scBadge = if (isSafetyCar) "🟡 SC | " else ""
        subTelemetryTxt.text = "${scBadge}TEMP: $tireTemp | BEST: $bestLapTime"
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 16
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
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D912141C"))
                cornerRadius = 20f
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
            text = "-- | LAP 1 | TIRE: 100% (SOFT)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 6, 0, 2)
        }

        subTelemetryTxt = TextView(this).apply {
            text = "TEMP: -- | BEST: --:--.---"
            textSize = 10f
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 0, 0, 4)
        }

        radioTxt = TextView(this).apply {
            text = "📻 เข้าแข่งใน Monoposto เพื่อซิงค์ข้อมูล..."
            textSize = 11f
            setTextColor(Color.parseColor("#FFD700"))
            setPadding(0, 0, 0, 8)
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btnLap = Button(this).apply {
            text = "+1"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#E10600"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                onNewLapDetected(currentLap + 1)
                updateDisplay()
            }
        }

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
                isSafetyCar = false
                radioTxt.text = "📻 รีเซ็ตเริ่มรอบ 1 ใหม่แล้ว"
                updateDisplay()
            }
        }

        val btnMode = Button(this).apply {
            text = "BAL"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#252836"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                mode = when (mode) {
                    "BAL" -> "PUSH"
                    "PUSH" -> "SAVE"
                    else -> "BAL"
                }
                text = mode
                radioTxt.text = "📻 Engine mode: $mode"
            }
        }

        val btnBox = Button(this).apply {
            text = "BOX"
            textSize = 10f
            setBackgro
