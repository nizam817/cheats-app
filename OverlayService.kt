package com.cheatoverlay

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var mediaProjection: MediaProjection? = null
    private var apiKey: String = ""

    // Mode: "clock" = samaran jam, "cheat" = mode cheat aktif
    private var currentMode = "clock"

    // Drag
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var isDragging = false
    private var dragStartX = 0f; private var dragStartY = 0f

    // Clock updater
    private val clockHandler = Handler(Looper.getMainLooper())
    private lateinit var clockRunnable: Runnable

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY

        apiKey = intent.getStringExtra("apiKey") ?: ""
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        if (resultCode != -1 && data != null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
        }

        setupOverlayWindow()
        return START_STICKY
    }

    private fun setupOverlayWindow() {
        // Inflate clock view dulu (mode default = samaran)
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_clock, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 100
        }

        // Touch listener — handle drag + double tap buat switch mode
        overlayView.setOnTouchListener { _, event ->
            val lp = overlayView.layoutParams as WindowManager.LayoutParams

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x; initialY = lp.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    dragStartX = event.rawX; dragStartY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        lp.x = initialX - (event.rawX - initialTouchX).toInt()
                        lp.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Cek double tap
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 400) {
                            toggleMode()
                        }
                        lastTapTime = now
                    }
                }
            }
            true
        }

        // Start clock ticker
        startClockTicker()

        instance = this
        windowManager.addView(overlayView, params)
        showClockMode()
    }

    private fun toggleMode() {
        if (currentMode == "clock") {
            switchToCheatMode()
        } else {
            switchToClockMode()
        }
    }

    private fun switchToClockMode() {
        currentMode = "clock"
        // Sembunyiin semua elemen cheat, tampilkan clock
        overlayView.findViewById<View>(R.id.layoutCheat)?.visibility = View.GONE
        overlayView.findViewById<View>(R.id.layoutClock)?.visibility = View.VISIBLE

        // Resize ke ukuran kecil widget jam
        val lp = overlayView.layoutParams as WindowManager.LayoutParams
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(overlayView, lp)
    }

    private fun switchToCheatMode() {
        currentMode = "cheat"
        overlayView.findViewById<View>(R.id.layoutClock)?.visibility = View.GONE
        overlayView.findViewById<View>(R.id.layoutCheat)?.visibility = View.VISIBLE

        // Setup tombol-tombol cheat
        setupCheatControls()
    }

    private fun showClockMode() {
        switchToClockMode()
    }

    private fun setupCheatControls() {
        val btnCrop = overlayView.findViewById<Button>(R.id.btnCrop)
        val btnBack = overlayView.findViewById<Button>(R.id.btnBack)
        val tvAnswer = overlayView.findViewById<TextView>(R.id.tvAnswer)

        answerView = tvAnswer
        tvAnswer.text = "Tap ✂️ untuk crop soal"

        btnCrop?.setOnClickListener {
            launchCropActivity()
        }

        // Tombol kembali ke clock mode
        btnBack?.setOnClickListener {
            switchToClockMode()
        }
    }

    private fun startClockTicker() {
        clockRunnable = object : Runnable {
            override fun run() {
                updateClock()
                clockHandler.postDelayed(this, 1000)
            }
        }
        clockHandler.post(clockRunnable)
    }

    private fun updateClock() {
        val tvTime = overlayView.findViewById<TextView>(R.id.tvTime) ?: return
        val tvDate = overlayView.findViewById<TextView>(R.id.tvDate) ?: return

        val now = Date()
        tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        tvDate.text = SimpleDateFormat("EEE, dd MMM", Locale("id")).format(now)
    }

    fun launchCropActivity() {
        val intent = Intent(this, CropActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    fun showAnswer(text: String) {
        Handler(Looper.getMainLooper()).post {
            answerView?.text = text
            // Auto switch ke cheat mode kalau jawaban dateng & lagi di clock mode
            if (currentMode == "clock") switchToCheatMode()
        }
    }

    fun showLoading() {
        Handler(Looper.getMainLooper()).post {
            if (currentMode == "clock") switchToCheatMode()
            answerView?.text = "⏳ AI lagi mikir..."
        }
    }

    fun getApiKey() = apiKey
    fun getMediaProjection() = mediaProjection

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        mediaProjection?.stop()
        instance = null
        answerView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_channel", "Overlay",
                NotificationManager.IMPORTANCE_MIN  // IMPORTANCE_MIN = gak ada suara/icon di status bar
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Clock Widget")  // Nama innocent di notif
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // Gak keliatan di lock screen
            .build()
    }

    companion object {
        var instance: OverlayService? = null
        var answerView: TextView? = null
    }
}
