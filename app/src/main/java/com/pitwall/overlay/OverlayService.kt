package com.pitwall.overlay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var wm: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var mainTxt: TextView
    private lateinit var subTxt: TextView
    private lateinit var radioTxt: TextView
    private lateinit var statusTxt: TextView

    private var tts: TextToSpeech? = null
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    private var driverTag = "MAI"
    private var lap = 1
    private var totalLaps = 0
    private var pos = "--"
    private var temp = "--"
    private var bestTime = "--:--.---"
    private var isSC = false
    private var wear = 100
    private var tire = "SOFT"
    private var mode = "BAL"
    private var boxQueued = false
    private var scanning = false
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        buildHud()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chId = "pitwall_hud"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(chId, "Pitwall HUD", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, chId)
            .setContentTitle("Pitwall Monoposto Sync")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        val code = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("DATA")
        }

        if (code == Activity.RESULT_OK && data != null && projection == null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    scanning = false
                    handler.removeCallbacksAndMessages(null)
                    vDisplay?.release()
                    reader?.close()
                }
            }, handler)
            initDisplay()
            scanning = true
            statusTxt.text = "👁️ MONOPOSTO SYNC  "
            statusTxt.setTextColor(Color.parseColor("#00D2BE"))
            loopScan()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (scanning && projection != null) handler.postDelayed({ initDisplay() }, 350)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            speak("Telemetry linked. Ready for lights out.")
        }
    }

    private fun speak(msg: String) {
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "radio")
    }

    private fun initDisplay() {
        vDisplay?.release()
        reader?.close()
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        val w = m.widthPixels.coerceAtLeast(1)
        val h = m.heightPixels.coerceAtLeast(1)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vDisplay = projection?.createVirtualDisplay(
            "PitwallCap", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
        )
    }

    private fun loopScan() {
        if (!scanning) return
        handler.postDelayed({
            scanFrame()
            loopScan()
        }, 1200)
    }

    private fun scanFrame() {
        if (busy) return
        val img = reader?.acquireLatestImage() ?: return
        busy = true
        try {
            val p = img.planes[0]
            val pad = p.rowStride - p.pixelStride * img.width
            val raw = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(p.buffer)
            val full = Bitmap.createBitmap(raw, 0, 0, img.width, img.height)
            raw.recycle()

            val w = full.width
            val h = full.height
            if (w <= h || w < 400) {
                full.recycle()
                busy = false
                return
            }

            val hW = (w * 0.28f).toInt()
            val hH = (h * 0.16f).toInt()
            val tY = (h * 0.11f).toInt()
            val tW = (w * 0.24f).toInt()
            val tH = (h * 0.78f).toInt()
            val rX = (w * 0.72f).toInt()
            val rY = (h * 0.12f).toInt()
            val rW = (w * 0.28f).toInt()
            val rH = (h * 0.42f).toInt()

            val stitched = Bitmap.createBitmap((hW * 2) + rW + 30, maxOf((hH * 2) + tH + 30, rH + 30), Bitmap.Config.ARGB_8888)
            val c = Canvas(stitched)
            c.drawColor(Color.BLACK)
            c.drawBitmap(full, Rect(0, 0, hW, hH), Rect(0, 0, hW * 2, hH * 2), null)
            c.drawBitmap(full, Rect(0, tY, tW, tY + tH), Rect(0, (hH * 2) + 15, tW, (hH * 2) + 15 + tH), null)
            c.drawBitmap(full, Rect(rX, rY, rX + rW, rY + rH), Rect((hW * 2) + 15, 0, (hW * 2) + 15 + rW, rH), null)
            full.recycle()

            ocr.process(InputImage.fromBitmap(stitched, 0))
                .addOnSuccessListener { res ->
                    parseText(res.text.uppercase(Locale.US))
                    stitched.recycle()
                    busy = false
                }
                .addOnFailureListener {
                    stitched.recycle()
                    busy = false
                }
        } catch (e: Exception) {
            busy = false
        } finally {
            img.close()
        }
    }

    private fun parseText(txt: String) {
        val scNow = txt.contains("SAFETY CAR")
        if (scNow && !isSC) {
            isSC = true
            radioTxt.text = "🟡 SAFETY CAR! โอกาสเข้าพิท"
            speak("Safety car deployed. Good window to box.")
        } else if (!scNow && isSC) {
            isSC = false
            radioTxt.text = "🟢 Green flag! Safety car in."
            speak("Safety car in this lap.")
        }

        val mFull = Pattern.compile("LAP\\s*(\\d{1,2})\\s*[/|\\\\I1]\\s*(\\d{1,2})").matcher(txt)
        val mSingle = Pattern.compile("LAP\\s*(\\d{1,2})\\s*[/|\\\\]").matcher(txt)
        var newLap: Int? = null
        if (mFull.find()) {
            val l = mFull.group(1)?.toIntOrNull()
            val t = mFull.group(2)?.toIntOrNull()
            if (l != null && t != null && t in 3..78 && l in 1..t) {
                newLap = l
                totalLaps = t
            }
        } else if (mSingle.find()) {
            val l = mSingle.group(1)?.toIntOrNull()
            if (l != null && l in 1..78) newLap = l
        }

        if (newLap != null) {
            if (newLap > lap && (newLap - lap) <= 3) advanceLap(newLap)
            else if (newLap == 1 && lap > 2) {
                lap = 1; wear = 100; boxQueued = false
                radioTxt.text = "📻 Lights out! เริ่มแข่งใหม่"
            }
        }

        val mPos = Pattern.compile("(\\d{1,2})\\s*$driverTag\\b").matcher(txt)
        if (mPos.find()) mPos.group(1)?.toIntOrNull()?.let { if (it in 1..22) pos = "P$it" }

        val mTemp = Pattern.compile("(\\d{2,3})\\s*[°º]").matcher(txt)
        if (mTemp.find()) mTemp.group(1)?.toIntOrNull()?.let { if (it in 30..145) temp = "${it}°C" }

        val mTime = Pattern.compile("BEST\\s*TIME[^0-9]*(\\d{2}:\\d{2}\\.\\d{3})").matcher(txt)
        if (mTime.find()) bestTime = mTime.group(1) ?: bestTime

        refreshUI()
    }

    private fun advanceLap(nextLap: Int) {
        val diff = (nextLap - lap).coerceAtLeast(1)
        lap = nextLap
        var step = when (tire) {
            "SOFT" -> if (mode == "PUSH") 8 else 6
            "MED" -> if (mode == "PUSH") 6 else 4
            else -> 3
        }
        if (isSC) step = (step * 0.4).toInt().coerceAtLeast(1)

        if (boxQueued) {
            wear = 100; boxQueued = false
            tire = if (tire == "SOFT") "MED" else "HARD"
            radioTxt.text = "📻 Pit stop done! ยาง $tire 100%"
            speak("Pit stop complete. Push now.")
        } else {
            wear = (wear - step * diff).coerceAtLeast(0)
            if (wear in 22..30) {
                radioTxt.text = "⚠️ ยางเหลือ $wear% เตรียมเข้าพิท"
                speak("Tires at $wear percent. Box soon.")
            } else if (wear < 22) {
                radioTxt.text = "🚨 ยางวิกฤต ($wear%) เข้าพิทด่วน!"
                speak("Critical tire wear. Box this lap!")
            } else {
                radioTxt.text = "📻 Lap $lap ($pos) | จังหวะดี"
            }
        }
    }

    private fun refreshUI() {
        val lStr = if (totalLaps > 0) "LAP $lap/$totalLaps" else "LAP $lap"
        mainTxt.text = "$pos | $lStr | TIRE: $wear% ($tire)"
        mainTxt.setTextColor(if (wear <= 25) Color.RED else if (wear <= 50) Color.YELLOW else Color.WHITE)
        subTxt.text = "${if (isSC) "🟡 SC | " else ""}TEMP: $temp | BEST: $bestTime"
    }

    private fun buildHud() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 16 }

        rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bubble = TextView(this).apply {
            text = "🏎️ PIT"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(24, 14, 24, 14); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#E6E10600")); cornerRadius = 32f }
            setOnClickListener { visibility = View.GONE; panel.visibility = View.VISIBLE }
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 10, 18, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D912141C")); cornerRadius = 20f; setStroke(2, Color.parseColor("#E10600"))
            }
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusTxt = TextView(this).apply { text = "👁️ WAIT  "; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.LTGRAY) }
        val minBtn = TextView(this).apply {
            text = " [ย่อจอ] "; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.YELLOW)
            setOnClickListener { panel.visibility = View.GONE; bubble.visibility = View.VISIBLE }
        }
        top.addView(statusTxt); top.addView(minBtn)

        mainTxt = TextView(this).apply { text = "-- | LAP 1 | TIRE: 100% (SOFT)"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) }
        subTxt = TextView(this).apply { text = "TEMP: -- | BEST: --:--.---"; textSize = 10f; setTextColor(Color.parseColor("#90CAF9")) }
        radioTxt = TextView(this).apply { text = "📻 เข้าเกม Monoposto เพื่อซิงค์..."; textSize = 11f; setTextColor(Color.YELLOW) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bLap = Button(this).apply { text = "+1"; textSize = 10f; setOnClickListener { advanceLap(lap + 1); refreshUI() } }
        val bRst = Button(this).apply {
            text = "RST"; textSize = 10f
            setOnClickListener { lap = 1; wear = 100; tire = "SOFT"; boxQueued = false; isSC = false; refreshUI() }
        }
        val bMod = Button(this).apply {
            text = "BAL"; textSize = 10f
            setOnClickListener { mode = if (mode == "BAL") "PUSH" else if (mode == "PUSH") "SAVE" else "BAL"; text = mode }
        }
        val bBox = Button(this).apply {
            text = "BOX"; textSize = 10f
            setOnClickListener { boxQueued = !boxQueued; text = if (boxQueued) "BOX✅" else "BOX"; if (boxQueued) speak("Box this lap.") }
        }
        row.addView(bLap); row.addView(bRst); row.addView(bMod); row.addView(bBox)

        panel.addView(top); panel.addView(mainTxt); panel.addView(subTxt); panel.addView(radioTxt); panel.addView(row)
        rootView.addView(bubble); rootView.addView(panel)

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        rootView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { params.x = ix + (e.rawX - tx).toInt(); params.y = iy + (e.rawY - ty).toInt(); wm.updateViewLayout(rootView, params); true }
                else -> false
            }
        }
        wm.addView(rootView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanning = false
        handler.removeCallbacksAndMessages(null)
        vDisplay?.release()
        projection?.stop()
        reader?.close()
        tts?.shutdown()
        if (::rootView.isInitialized) wm.removeView(rootView)
    }
}
