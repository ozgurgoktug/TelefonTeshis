package com.example.telefonteshis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.media.ExifInterface
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.util.Size
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

class CountActivity : ComponentActivity() {

    private lateinit var image: ImageView
    private lateinit var countText: TextView
    private lateinit var freezeBtn: Button

    @Volatile private var lastRaw: Bitmap? = null
    @Volatile private var frozen: Bitmap? = null
    @Volatile private var shift = 0
    @Volatile private var minPct = 0.06f
    @Volatile private var invert = false

    private val camExec = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var dirty = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 13f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(12), dp(28), dp(12), dp(8))
        }
        countText = label("Sayı: -", 22f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        root.addView(countText)

        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(label("Eşik ayarı (nesneler eksik/fazla ise oynat)"))
        root.addView(SeekBar(this).apply {
            max = 120
            progress = 60
            setOnSeekBarChangeListener(listener { shift = it - 60 })
        })
        root.addView(label("Minimum nesne boyutu (küçük lekeleri yok say)"))
        root.addView(SeekBar(this).apply {
            max = 100
            progress = 5
            setOnSeekBarChangeListener(listener { minPct = 0.01f + it * 0.01f })
        })
        root.addView(Switch(this).apply {
            text = "Açık/koyu ters çevir"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> invert = c; if (frozen != null) requestAnalyze() }
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        freezeBtn = Button(this).apply {
            text = "Dondur"
            setOnClickListener { toggleFreeze() }
        }
        row.addView(freezeBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Galeriden seç"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, 2)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
            onChange(p)
            if (fromUser && frozen != null) requestAnalyze()
        }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else countText.text = "Kamera izni yok. Galeriden fotoğraf seçebilirsiniz."
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(camExec) { proxy ->
                try {
                    val raw = proxy.toBitmap()
                    val rot = proxy.imageInfo.rotationDegrees
                    val bm = if (rot != 0)
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    else raw
                    lastRaw = bm
                    if (frozen == null) show(Counter.analyze(bm, 480, shift, minPct, invert))
                } finally {
                    proxy.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun show(r: Counter.Result) {
        runOnUiThread {
            image.setImageBitmap(r.image)
            countText.text = "Sayı: ${r.total}" +
                if (r.total != r.blobs) "   (${r.blobs} parça, turuncular tahmini)" else ""
        }
    }

    private fun toggleFreeze() {
        if (frozen == null) {
            frozen = lastRaw ?: return
            freezeBtn.text = "Canlıya dön"
            requestAnalyze()
        } else {
            frozen = null
            freezeBtn.text = "Dondur"
        }
    }

    private fun requestAnalyze() {
        if (!busy.compareAndSet(false, true)) { dirty = true; return }
        worker.execute {
            try {
                do {
                    dirty = false
                    val src = frozen
                    if (src != null) show(Counter.analyze(src, 720, shift, minPct, invert))
                } while (dirty)
            } finally {
                busy.set(false)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 2) return
        thread {
            val bm = try { decode(uri) } catch (e: Exception) { null }
            runOnUiThread {
                if (bm == null) { countText.text = "Fotoğraf açılamadı"; return@runOnUiThread }
                frozen = bm
                freezeBtn.text = "Canlıya dön"
                requestAnalyze()
            }
        }
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        return if (rot == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        camExec.shutdown()
        worker.shutdown()
    }
}
