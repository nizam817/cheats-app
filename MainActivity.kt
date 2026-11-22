package com.cheatoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQ_OVERLAY = 1001
    private val REQ_MEDIA_PROJECTION = 1002

    private lateinit var etApiKey: EditText
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        btnStart = findViewById(R.id.btnStart)

        // Load saved API key
        val prefs = getSharedPreferences("cheat", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("gemini_key", ""))

        btnStart.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Masukin Gemini API key dulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save key
            prefs.edit().putString("gemini_key", key).apply()

            // Cek overlay permission dulu
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQ_OVERLAY)
            } else {
                requestMediaProjection()
            }
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    Toast.makeText(this, "Overlay permission diperlukan!", Toast.LENGTH_LONG).show()
                }
            }
            REQ_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Simpan projection data dan start service
                    val prefs = getSharedPreferences("cheat", MODE_PRIVATE)
                    val key = prefs.getString("gemini_key", "") ?: ""

                    val serviceIntent = Intent(this, OverlayService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                        putExtra("apiKey", key)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    Toast.makeText(this, "Overlay aktif! Minimize app ini.", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Screen capture permission ditolak", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
