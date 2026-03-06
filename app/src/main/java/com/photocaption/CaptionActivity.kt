package com.photocaption

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.photocaption.databinding.ActivityCaptionBinding
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class CaptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptionBinding
    private lateinit var photoPath: String

    // ── Speech ────────────────────────────────────────────────────────────────
    private var isTitleMicActive = true

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull() ?: return@registerForActivityResult
            if (isTitleMicActive) binding.etTitle.setText(text)
            else binding.etDescription.setText(text)
        }
    }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSpeechRecognizer()
        else Toast.makeText(this, getString(R.string.permission_audio_required), Toast.LENGTH_SHORT).show()
    }

    // ── GPS ───────────────────────────────────────────────────────────────────
    private var isGpsActive = false
    private var gpsCoords: String? = null       // null = not yet acquired
    private var locationListener: LocationListener? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchLocation()
        else {
            isGpsActive = false
            binding.btnGpsToggle.alpha = GPS_ALPHA_OFF
            Toast.makeText(this, getString(R.string.permission_location_required), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoPath = intent.getStringExtra(EXTRA_PHOTO_PATH) ?: run { finish(); return }

        val bitmap = loadBitmapWithCorrectOrientation(photoPath)
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.error_load_photo), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.ivPhoto.setImageBitmap(bitmap)

        binding.btnTitleMic.setOnClickListener { isTitleMicActive = true;  checkAudioAndStart() }
        binding.btnDescMic.setOnClickListener  { isTitleMicActive = false; checkAudioAndStart() }
        binding.btnSave.setOnClickListener     { savePhoto() }
        binding.btnGpsToggle.setOnClickListener { toggleGps() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
    }

    // ── Speech helpers ────────────────────────────────────────────────────────
    private fun checkAudioAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) launchSpeechRecognizer()
        else audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchSpeechRecognizer() {
        val hint = if (isTitleMicActive) getString(R.string.speech_hint_title)
                   else getString(R.string.speech_hint_description)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, hint)
        }
        try { speechLauncher.launch(intent) }
        catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_speech_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    // ── GPS helpers ───────────────────────────────────────────────────────────
    private fun toggleGps() {
        isGpsActive = !isGpsActive
        binding.btnGpsToggle.alpha = if (isGpsActive) GPS_ALPHA_ON else GPS_ALPHA_OFF
        if (isGpsActive) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) fetchLocation()
            else locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            stopLocationUpdates()
            gpsCoords = null
            binding.tvGpsCoords.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        // Use a fresh-enough last-known fix if available (< 60 s old)
        val recent = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 60_000L }

        if (recent != null) {
            applyLocation(recent)
            return
        }

        // Show searching indicator while waiting for a fresh fix
        binding.tvGpsCoords.text = getString(R.string.gps_searching)
        binding.tvGpsCoords.visibility = View.VISIBLE

        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        if (provider == null) {
            Toast.makeText(this, getString(R.string.error_gps_unavailable), Toast.LENGTH_SHORT).show()
            isGpsActive = false
            binding.btnGpsToggle.alpha = GPS_ALPHA_OFF
            binding.tvGpsCoords.visibility = View.GONE
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                applyLocation(location)
                lm.removeUpdates(this)
                locationListener = null
            }
        }
        locationListener = listener
        lm.requestLocationUpdates(provider, 0L, 0f, listener, mainLooper)
    }

    private fun applyLocation(location: Location) {
        val latDir = if (location.latitude  >= 0) "N" else "S"
        val lonDir = if (location.longitude >= 0) "E" else "W"
        val text = "%.5f°%s  %.5f°%s".format(
            abs(location.latitude),  latDir,
            abs(location.longitude), lonDir
        )
        gpsCoords = text
        binding.tvGpsCoords.text = text
        binding.tvGpsCoords.visibility = View.VISIBLE
    }

    private fun stopLocationUpdates() {
        locationListener?.let {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it)
            locationListener = null
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────
    private fun savePhoto() {
        val title = binding.etTitle.text.toString().trim()
        if (title.isEmpty()) {
            binding.etTitle.error = getString(R.string.error_title_required)
            return
        }
        val description = binding.etDescription.text.toString().trim()

        val original = loadBitmapWithCorrectOrientation(photoPath) ?: run {
            Toast.makeText(this, getString(R.string.error_load_photo), Toast.LENGTH_SHORT).show()
            return
        }

        val mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val displayWidth = binding.ivPhoto.width
            .takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        val scaleFactor = mutableBitmap.width.toFloat() / displayWidth

        drawTitleOnCanvas(canvas, title, mutableBitmap.width, scaleFactor)

        if (description.isNotEmpty()) {
            drawDescriptionOnCanvas(canvas, description, mutableBitmap.width, mutableBitmap.height, scaleFactor)
        }

        val gpsText = binding.tvGpsCoords.text
            .takeIf { it.isNotEmpty() && it != getString(R.string.gps_searching) }
            ?.toString()
        if (gpsText != null) {
            drawGpsOnCanvas(canvas, gpsText, mutableBitmap.width, scaleFactor)
        }

        persistToGallery(mutableBitmap)
    }

    // ── Canvas drawing ────────────────────────────────────────────────────────
    /** Title: top-left, 14sp. */
    private fun drawTitleOnCanvas(canvas: Canvas, text: String, imgWidth: Int, scaleFactor: Float) {
        val textSizePx = spToPx(14f) * scaleFactor
        val padding = (8f * scaleFactor).toInt()

        val textPaint = TextPaint().apply {
            color = Color.WHITE; textSize = textSizePx; isAntiAlias = true
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, (imgWidth * 0.75f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f).setIncludePad(false).build()

        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
        canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        canvas.drawRect(0f, 0f, (layout.width + padding * 2).toFloat(), (layout.height + padding * 2).toFloat(), bgPaint)
        canvas.translate(padding.toFloat(), padding.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    /** Description: bottom edge, full width, multiline, 9sp. */
    private fun drawDescriptionOnCanvas(
        canvas: Canvas, text: String, imgWidth: Int, imgHeight: Int, scaleFactor: Float
    ) {
        val textSizePx = spToPx(9f) * scaleFactor
        val padding = (8f * scaleFactor).toInt()

        val textPaint = TextPaint().apply {
            color = Color.WHITE; textSize = textSizePx; isAntiAlias = true
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, imgWidth - padding * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f).setIncludePad(false).build()

        val bgHeight = layout.height + padding * 2
        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

        canvas.save()
        canvas.translate(0f, imgHeight - bgHeight.toFloat())
        canvas.drawRect(0f, 0f, imgWidth.toFloat(), bgHeight.toFloat(), bgPaint)
        canvas.translate(padding.toFloat(), padding.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * GPS coordinates: top-right corner, single line, 9sp.
     */
    private fun drawGpsOnCanvas(
        canvas: Canvas, text: String, imgWidth: Int, scaleFactor: Float
    ) {
        val textSizePx = spToPx(9f) * scaleFactor
        val margin  = (8f * scaleFactor).toInt()
        val padding = (6f * scaleFactor).toInt()

        val textPaint = Paint().apply {
            color = Color.WHITE; textSize = textSizePx; isAntiAlias = true
        }

        val textWidth  = textPaint.measureText(text)
        val textHeight = (-textPaint.ascent() + textPaint.descent())
        val bgW = textWidth  + padding * 2
        val bgH = textHeight + padding * 2

        val bgLeft = imgWidth - bgW - margin
        val bgTop  = margin.toFloat()

        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
        canvas.drawRect(bgLeft, bgTop, bgLeft + bgW, bgTop + bgH, bgPaint)
        canvas.drawText(text, bgLeft + padding, bgTop + padding - textPaint.ascent(), textPaint)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    /** Loads bitmap and rotates according to EXIF so top-left always matches visual top-left. */
    private fun loadBitmapWithCorrectOrientation(path: String): Bitmap? {
        val raw = BitmapFactory.decodeFile(path) ?: return null
        val exif = ExifInterface(path)
        val degrees = when (
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return raw
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private fun persistToGallery(bitmap: Bitmap) {
        val fileName = "PhotoCaption_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoCaption")
        }
        var outputStream: OutputStream? = null
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("MediaStore insert returned null")
            outputStream = contentResolver.openOutputStream(uri)
                ?: throw Exception("Could not open output stream")
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
            Toast.makeText(this, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
            File(photoPath).delete()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_save_failed, e.message), Toast.LENGTH_LONG).show()
        } finally {
            outputStream?.close()
        }
    }

    companion object {
        const val EXTRA_PHOTO_PATH = "extra_photo_path"
        private const val GPS_ALPHA_ON  = 1.0f
        private const val GPS_ALPHA_OFF = 0.45f
    }
}
