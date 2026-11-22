package com.pitwall.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private val REQ_MEDIA_PROJECTION = 202

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0D0E12"))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "PITWALL // AUTO-VISION HUD"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E10600"))
            gravity = Gravity.CENTER
        }

        val desc = TextView(this).apply {
            text = "ระบบสแกนหน้าจอเกมอัตโนมัติ (OCR)\nอ่านเลขรอบ (LAP) และแจ้งเตือนเข้าพิทด้วยเสียงโดยไม่ต้องกดเอง"
            textSize = 14f
            setTextColor(Color.parseColor("#B0B5C0"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 48)
        }

        val btnStart = Button(this).apply {
            text = "👁️ เปิดระบบอ่านหน้าจอ + HUD"
            setBackgroundColor(Color.parseColor("#E10600"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "กรุณาอนุญาตสิทธิ์ 'แสดงทับแอปอื่น'", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "🛑 ปิดหน้าต่างลอย"
            setBackgroundColor(Color.parseColor("#1E222D"))
            setTextColor(Color.LTGRAY)
            setPadding(32, 20, 32, 20)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 24
            layoutParams = lp
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "ปิด Pitwall HUD แล้ว", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(title)
        root.addView(desc)
        root.addView(btnStart)
        root.addView(btnStop)
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            startForegroundService(svc)
            Toast.makeText(this, "เปิดระบบสแกนหน้าจอแล้ว! เข้าเกม Monoposto ได้เลย", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "จำเป็นต้องกด 'เริ่มเลย' เพื่ออนุญาตให้อ่านหน้าจอเกม", Toast.LENGTH_LONG).show()
        }
    }
}
