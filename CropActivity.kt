package com.cheatoverlay

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.*
import android.util.AttributeSet
import android.view.*
import android.widget.Toast

class CropActivity : Activity() {

    private lateinit var cropView: CropView
    private var mediaProjection: MediaProjection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen transparent buat crop
        window.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        mediaProjection = OverlayService.instance?.getMediaProjection()
        if (mediaProjection == null) {
            Toast.makeText(this, "Error: projection null", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        cropView = CropView(this)
        setContentView(cropView)

        cropView.onCropSelected = { rect ->
            // Delay dikit biar overlay-nya hilang dulu sebelum screenshot
            Handler(Looper.getMainLooper()).postDelayed({
                captureScreen(rect)
            }, 200)
        }
    }

    private fun captureScreen(cropRect: Rect) {
        OverlayService.instance?.showLoading()

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CheatCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        // Tunggu frame tersedia
        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop sesuai selection
                val safeCrop = Rect(
                    cropRect.left.coerceIn(0, width),
                    cropRect.top.coerceIn(0, height),
                    cropRect.right.coerceIn(0, width),
                    cropRect.bottom.coerceIn(0, height)
                )

                if (safeCrop.width() > 10 && safeCrop.height() > 10) {
                    val cropped = Bitmap.createBitmap(
                        bitmap, safeCrop.left, safeCrop.top,
                        safeCrop.width(), safeCrop.height()
                    )

                    // Kirim ke Gemini di background thread
                    Thread {
                        val answer = GeminiHelper.askWithImage(
                            cropped,
                            OverlayService.instance?.getApiKey() ?: ""
                        )
                        OverlayService.instance?.showAnswer(answer)
                    }.start()
                } else {
                    OverlayService.instance?.showAnswer("Crop area terlalu kecil")
                }

                bitmap.recycle()
            } else {
                OverlayService.instance?.showAnswer("❌ Gagal capture screen")
            }

            virtualDisplay?.release()
            finish()
        }, 500)
    }
}

// Custom View buat nggambar selection rectangle
class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onCropSelected: ((Rect) -> Unit)? = null

    private var startX = 0f; private var startY = 0f
    private var endX = 0f; private var endY = 0f
    private var isDragging = false

    private val overlayPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0) // Semi-transparent gelap
        style = Paint.Style.FILL
    }

    private val selectionPaint = Paint().apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isDragging) {
            // Instruksi awal
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            canvas.drawText("Drag untuk pilih area soal", 60f, height / 2f, textPaint)
            canvas.drawText("Lepas untuk screenshot & kirim ke AI", 60f, height / 2f + 60f, textPaint)
        } else {
            val left = minOf(startX, endX)
            val top = minOf(startY, endY)
            val right = maxOf(startX, endX)
            val bottom = maxOf(startY, endY)

            // Gelapi semua, buka yang di-select
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            canvas.drawRect(left, top, right, bottom, selectionPaint)
            canvas.drawRect(left, top, right, bottom, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                endX = event.x; endY = event.y
                isDragging = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x; endY = event.y
                isDragging = false
                invalidate()

                val rect = Rect(
                    minOf(startX, endX).toInt(),
                    minOf(startY, endY).toInt(),
                    maxOf(startX, endX).toInt(),
                    maxOf(startY, endY).toInt()
                )
                onCropSelected?.invoke(rect)
            }
        }
        return true
    }
}
