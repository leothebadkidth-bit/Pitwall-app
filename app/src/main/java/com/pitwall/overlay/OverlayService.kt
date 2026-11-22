package com.pitwall.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var expandedPanel: LinearLayout
    private lateinit var miniBubble: TextView
    private var tts: TextToSpeech? = null

    private var lap = 1
    private var tireWear = 100
    private var compound = "SOFT"
    private var mode = "BAL"
    private var boxQueued = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        tts = TextToSpeech(this, this)
        setupOverlay()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            speak("Pitwall online. Ready for race.")
        }
    }

    private fun speak(msg: String) {
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "pitwall_radio")
    }

    private fun startForegroundNotif() {
        val channelId = "pitwall_hud_channel"
        val channel = NotificationChannel(channelId, "Pitwall HUD", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notif = android.app.Notification.Builder(this, channelId)
            .setContentTitle("Pitwall HUD Active")
            .setContentText("แตะหน้าต่างลอยบนจอเพื่อควบคุมกลยุทธ์")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notif)
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

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

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

        val titleTxt = TextView(this).apply {
            text = "PITWALL HUD  "
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00D2BE"))
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

        headerRow.addView(titleTxt)
        headerRow.addView(btnMin)

        val telemetryTxt = TextView(this).apply {
            text = "LAP 1 | TIRE: 100% (SOFT)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
        }

        val radioTxt = TextView(this).apply {
            text = "📻 Push mode ready"
            textSize = 11f
            setTextColor(Color.parseColor("#FFD700"))
            setPadding(0, 0, 0, 10)
        }

        fun updateDisplay() {
            telemetryTxt.text = "LAP $lap | TIRE: $tireWear% ($compound)"
            telemetryTxt.setTextColor(
                when {
                    tireWear <= 25 -> Color.parseColor("#FF3B30")
                    tireWear <= 50 -> Color.parseColor("#FFD700")
                    else -> Color.WHITE
                }
            )
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btnLap = Button(this).apply {
            text = "+1 LAP"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#E10600"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val wearStep = when (compound) {
                    "SOFT" -> if (mode == "PUSH") 9 else 6
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
                    tireWear = (tireWear - wearStep).coerceAtLeast(0)
                    if (tireWear in 20..28) {
                        radioTxt.text = "⚠️ ยางเริ่มหมด! เตรียมเข้าพิท"
                        speak("Tires getting low. Box this lap.")
                    } else if (tireWear < 20) {
                        radioTxt.text = "🚨 ยางวิกฤต! เข้าพิทด่วน!"
                        speak("Critical tire wear. Box now!")
                    } else {
                        radioTxt.text = "📻 Pace looks good ($mode)"
                    }
                }
                lap++
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

        btnRow.addView(btnLap)
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
        tts?.shutdown()
        if (::rootView.isInitialized) {
            windowManager.removeView(rootView)
        }
    }
}
