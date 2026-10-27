package com.steampunk.cam

import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val BRASS = 0xFFB5832F.toInt()
    private val DARK = 0xFF1E1209.toInt()
    private val COPPER = 0xFF8C4A1E.toInt()
    private val PAPER = 0xFFF3DFA2.toInt()

    private lateinit var root: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var label: TextView
    private lateinit var dial: DialView
    private var imageCapture: ImageCapture? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val gears = mutableListOf<GearView>()
    private val names = listOf("Original", "Sepia", "Daguerreotype", "Brass", "Verdigris")
    private var filter = 0
    private var spin = 0.5f
    private var baseAngle = 0f

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else toast("Camera permission is needed")
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setContentView(root)
        applyFilter()
        startGears()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun brassDrawable(radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(radius).toFloat()
        colors = intArrayOf(0xFFF0CB78.toInt(), 0xFF8C5A1B.toInt())
        setStroke(dp(5), 0xFF5A3510.toInt())
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(DARK) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(36), dp(12), dp(16))
        }

        // Header: gears + title
        val header = FrameLayout(this)
        val g1 = GearView(this, 16, 1f, 1f)
        val g2 = GearView(this, 10, -1f, 1.6f)
        gears += listOf(g1, g2)
        header.addView(g1, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(g2, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.CENTER_VERTICAL))
        header.addView(TextView(this).apply {
            text = "AETHER CAM"
            setTextColor(PAPER)
            textSize = 22f
            letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(90)))

        // Brass-framed viewfinder
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        val frame = FrameLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(COPPER)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(4), BRASS)
            }
        }
        frame.addView(preview, MATCH_PARENT, MATCH_PARENT)
        col.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        label = TextView(this).apply {
            setTextColor(PAPER)
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        col.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        // Controls: gallery, shutter, filter dial
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gal = TextView(this).apply {
            text = "▣"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(DARK)
            background = brassDrawable(40)
            setOnClickListener { openGallery() }
        }
        val shutter = View(this).apply {
            background = brassDrawable(48)
            setOnClickListener { shoot() }
        }
        dial = DialView(this).apply {
            count = names.size
            setOnClickListener { filter = (filter + 1) % names.size; applyFilter() }
        }
        fun lp(size: Int) = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(16), dp(8), dp(16), 0) }
        bar.addView(gal, lp(60))
        bar.addView(shutter, lp(90))
        bar.addView(dial, lp(70))
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(col, MATCH_PARENT, MATCH_PARENT)
    }

    private fun filterMatrix(i: Int): ColorMatrix? = when (i) {
        0 -> null
        1 -> ColorMatrix(floatArrayOf(
            .393f, .769f, .189f, 0f, 0f,
            .349f, .686f, .168f, 0f, 0f,
            .272f, .534f, .131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        else -> {
            val m = ColorMatrix().apply { setSaturation(0f) }
            val t = when (i) {
                2 -> floatArrayOf(.85f, .95f, 1.1f)
                3 -> floatArrayOf(1.2f, 1.0f, .6f)
                else -> floatArrayOf(.7f, 1.1f, .95f)
            }
            m.postConcat(ColorMatrix().apply { setScale(t[0], t[1], t[2], 1f) })
            m
        }
    }

    private fun applyFilter() {
        val m = filterMatrix(filter)
        if (m == null) preview.setLayerType(View.LAYER_TYPE_NONE, null)
        else preview.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        label.text = names[filter]
        dial.index = filter
        dial.invalidate()
    }

    private fun startGears() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spin = maxOf(0.5f, spin * 0.96f)
                baseAngle += spin
                gears.forEach { it.angle = baseAngle; it.invalidate() }
            }
        }.start()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shoot() {
        val ic = imageCapture ?: return
        spin = 14f // gears whirr
        val f = filter
        ic.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val out = process(image.toBitmap(), image.imageInfo.rotationDegrees, f)
                    save(out)
                    runOnUiThread { toast("Plate saved to Pictures/AetherCam") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Save failed: ${e.message}") }
                } finally {
                    image.close()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { toast("Capture failed") }
            }
        })
    }

    private fun process(src: Bitmap, deg: Int, f: Int): Bitmap {
        val rot = Bitmap.createBitmap(src, 0, 0, src.width, src.height,
            Matrix().apply { postRotate(deg.toFloat()) }, true)
        val out = Bitmap.createBitmap(rot.width, rot.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        filterMatrix(f)?.let { p.colorFilter = ColorMatrixColorFilter(it) }
        c.drawBitmap(rot, 0f, 0f, p)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val vig = Paint().apply {
            shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.75f,
                intArrayOf(Color.TRANSPARENT, 0xB0000000.toInt()), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, vig)
        val b = Paint().apply { style = Paint.Style.STROKE; strokeWidth = w * 0.04f; color = BRASS }
        val s = b.strokeWidth / 2
        c.drawRect(s, s, w - s, h - s, b)
        return out
    }

    private fun save(bmp: Bitmap) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "aether_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AetherCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    private fun openGallery() {
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val panel = FrameLayout(this).apply { setBackgroundColor(DARK); isClickable = true }
        panel.addView(ScrollView(this).apply { addView(grid) },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(90) })
        panel.addView(TextView(this).apply {
            text = "THE PLATE ARCHIVE"; setTextColor(PAPER); textSize = 18f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(46); leftMargin = dp(16) })
        panel.addView(TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(DARK); gravity = Gravity.CENTER
            background = brassDrawable(30)
            setOnClickListener { root.removeView(panel) }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40); rightMargin = dp(16) })
        root.addView(panel, MATCH_PARENT, MATCH_PARENT)

        val size = resources.displayMetrics.widthPixels / 3 - dp(8)
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Thread {
            contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/AetherCam%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(base, c.getLong(0))
                    val th = try { contentResolver.loadThumbnail(uri, Size(300, 300), null) } catch (e: Exception) { null }
                    if (th == null) continue
                    runOnUiThread {
                        grid.addView(ImageView(this).apply {
                            setImageBitmap(th)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setOnClickListener {
                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                        }, GridLayout.LayoutParams().apply {
                            width = size; height = size; setMargins(dp(2), dp(2), dp(2), dp(2))
                        })
                    }
                }
            }
        }.start()
    }
}

class GearView(ctx: Context, private val teeth: Int, private val dir: Float, private val ratio: Float) : View(ctx) {
    var angle = 0f
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB5832F.toInt() }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1209.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A3510.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        c.save()
        c.rotate(angle * dir * ratio, cx, cy)
        for (i in 0 until teeth) {
            c.save(); c.rotate(i * 360f / teeth, cx, cy)
            c.drawRect(cx - r * 0.09f, cy - r, cx + r * 0.09f, cy - r * 0.7f, body)
            c.restore()
        }
        c.drawCircle(cx, cy, r * 0.84f, body)
        c.drawCircle(cx, cy, r * 0.84f, edge)
        for (i in 0 until 4) {
            c.save(); c.rotate(i * 90f, cx, cy)
            c.drawRect(cx - r * 0.2f, cy - r * 0.65f, cx + r * 0.2f, cy - r * 0.2f, hole)
            c.restore()
        }
        c.drawCircle(cx, cy, r * 0.12f, hole)
        c.restore()
    }
}

class DialView(ctx: Context) : View(ctx) {
    var index = 0
    var count = 5
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB5832F.toInt() }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B1B0E.toInt() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB5832F.toInt(); strokeWidth = 4f }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3DFA2.toInt(); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, r * 0.85f, face)
        val step = if (count > 1) 240f / (count - 1) else 0f
        for (i in 0 until count) {
            c.save(); c.rotate(-120f + i * step, cx, cy)
            c.drawLine(cx, cy - r * 0.76f, cx, cy - r * 0.6f, tick)
            c.restore()
        }
        c.save(); c.rotate(-120f + index * step, cx, cy)
        c.drawLine(cx, cy, cx, cy - r * 0.68f, needle)
        c.restore()
        c.drawCircle(cx, cy, r * 0.1f, ring)
    }
}
