package com.example.cameraxapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.graphics.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

//Meminta izin akses kamera (1)
// Ini nomor acak yang kita gunakan untuk menampilkan tab permintaan izin akses kita
private const val REQUEST_CODE_PERMISSIONS = 10

// Ini adalah array dari semua izin yang ditentukan dalam manifes
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

class MainActivity : AppCompatActivity(), LifecycleOwner {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Tambahkan di akhir onCreate Function

        viewFinder = findViewById(R.id.view_finder)

        // Meminta izin Kamera (4)( Trigger permission request ketika dibutuhkan)
        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Setiap kali tampilan tekstur yang disediakan berubah, hitung ulang tata letak
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }
    }

    // Tambahkan Setelah onCreate

    private lateinit var viewFinder: TextureView

    //Menerapkan Operasi CameraX
    private fun startCamera() {

        //Implement viewfinder (1)
        // Buat objek konfigurasi untuk viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 1))
            setTargetResolution(Size(640, 640))
        }.build()

        // Membuat viewfinder use case
        val preview = Preview(previewConfig)

        //
        //Setiap kali viewfinder diperbarui, hitung ulang tata letak
        preview.setOnPreviewOutputUpdateListener {

            // Untuk memperbarui SurfaceTexture, kita harus menghapusnya dan menambahkannya kembali
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        // Tambahkan sebelum CameraX.bindToLifecycle

        //Implement Imange Capture (1)
        // Buat objek konfigurasi untuk image capture use case
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                setTargetAspectRatio(Rational(1, 1))
                // Kita tidak menetapkan resolusi untuk image capture; sebaliknya, kita
                // pilih capture mode yang akan menyimpulkan resolusi yang sesuai
                // berdasarkan rasio aspek dan mode yang diminta
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            }.build()

        // Buat image capture use case and berikan button click listener
        val imageCapture = ImageCapture(imageCaptureConfig)
        findViewById<ImageButton>(R.id.capture_button).setOnClickListener {
            val file = File(
                externalMediaDirs.first(),
                "${System.currentTimeMillis()}.jpg"
            )
            imageCapture.takePicture(file,
                object : ImageCapture.OnImageSavedListener {
                    override fun onError(
                        error: ImageCapture.UseCaseError,
                        message: String, exc: Throwable?
                    ) {
                        val msg = "Photo capture failed: $message"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.e("CameraXApp", msg)
                        exc?.printStackTrace()
                    }

                    override fun onImageSaved(file: File) {
                        val msg = "Photo capture succeeded: ${file.absolutePath}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d("CameraXApp", msg)
                    }
                })
        }

        //Implement image analysis use case (2)
        // Siapkan image analysis pipeline yang menghitung rata-rata pixel luminance
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            //Gunakan sebuah worker thread untuk image analysis agar mencegah glitch
            val analyzerThread = HandlerThread(
                "LuminosityAnalysis"
            ).apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
            // Di analysis ini, kita mementingkan gambar terbaru dari pada
            // menganalisa setiap gambar
            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE
            )
        }.build()

        // Buat image analysis use case dan instantiate analyzernya
        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            analyzer = LuminosityAnalyzer()
        }

        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(
            this, preview, imageCapture, analyzerUseCase
        )
    }

    // Menerapkan transformasi viewfinder kamera
    private fun updateTransform() {
        //Implement viewfinder (2)
        val matrix = Matrix()

        // Hitung titik tengah dari viewfinder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Memperbaiki preview keluaran untuk memperhitungkan rotasi tampilan
        val rotationDegrees = when (viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Akhirnya, terapkan transformasi ke TextureView
        viewFinder.setTransform(matrix)
    }


    //Meminta izin akses kamera (2)
    /**
     * Hasil process dari kotak dialog permintaan izin, apakah izin sudah diberikan
     * jika ya , start Camera. Jika tidak tampilkan toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    //Meminta izin akses kamera (3)
    /**
     * Periksa apakah semua izin yang ditentukan dalam manifest telah diberikan
     */
    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this, permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    //Implement image analysis use case (1)
    private class LuminosityAnalyzer : ImageAnalysis.Analyzer {
            private var lastAnalyzedTimestamp = 0L

            /**
             * Fungsi ekstensi pembantu digunakan untuk mengekstrak array byte dari sebuah
             * image plane buffer
             */
            private fun ByteBuffer.toByteArray(): ByteArray {
                rewind()    // Ulang buffer ke nol
                val data = ByteArray(remaining())
                get(data)   // Copy kedalam byte aray
                return data // Kembalikan byte array
            }

            override fun analyze(image: ImageProxy, rotationDegrees: Int) {
                val currentTimestamp = System.currentTimeMillis()
                // Hitung rata-rata luma tidak lebih dari setiap detik
                if (currentTimestamp - lastAnalyzedTimestamp >=
                    TimeUnit.SECONDS.toMillis(1)
                ) {
                    //Karena format dalam ImageAnalysis adalah YUV, image.planes [0]
                    //berisi bidang Y (luminance)
                    val buffer = image.planes[0].buffer
                    // Ekstrak data gambar dari callback objek
                    val data = buffer.toByteArray()
                    // Konversi data menjadi array nilai piksel
                    val pixels = data.map { it.toInt() and 0xFF }
                    // Hitung rata-rata luminance untuk gambar
                    val luma = pixels.average()
                    // Catat nilai luma baru
                    Log.d("CameraXApp", "Average luminosity: $luma")
                    // Perbarui cap waktu bingkai yang terakhir dianalisis
                    lastAnalyzedTimestamp = currentTimestamp
                }
            }
    }
}

