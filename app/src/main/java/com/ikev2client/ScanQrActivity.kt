package com.ikev2client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.ikev2client.crypto.ProfileCrypto
import com.ikev2client.databinding.ActivityScanQrBinding
import com.ikev2client.storage.ProfileStorage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanQrBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var storage: ProfileStorage
    private var scannedData: String? = null
    private var isProcessing = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ProfileStorage(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnBack.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required for QR scanning", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !isProcessing) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )

                            val scanner = BarcodeScanning.getClient()
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        if (barcode.valueType == Barcode.TYPE_TEXT) {
                                            val data = barcode.rawValue
                                            if (data != null && !isProcessing) {
                                                isProcessing = true
                                                scannedData = data
                                                runOnUiThread { showPassphraseDialog(data) }
                                            }
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun showPassphraseDialog(encryptedData: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_paste_profile, null)
        val etEncrypted = dialogView.findViewById<TextInputEditText>(R.id.etEncryptedData)
        val etPassphrase = dialogView.findViewById<TextInputEditText>(R.id.etPassphrase)

        // Pre-fill with scanned data
        etEncrypted.setText(encryptedData)
        etEncrypted.isEnabled = false

        AlertDialog.Builder(this)
            .setTitle("QR Code Scanned")
            .setMessage("Enter the passphrase to decrypt the profile(s)")
            .setView(dialogView)
            .setPositiveButton("Import") { _, _ ->
                val passphrase = etPassphrase.text?.toString() ?: ""
                if (passphrase.isEmpty()) {
                    Toast.makeText(this, "Passphrase required", Toast.LENGTH_SHORT).show()
                    isProcessing = false
                    return@setPositiveButton
                }
                importProfile(encryptedData, passphrase)
            }
            .setNegativeButton("Cancel") { _, _ ->
                isProcessing = false
            }
            .setOnCancelListener {
                isProcessing = false
            }
            .show()
    }

    private fun importProfile(encryptedData: String, passphrase: String) {
        try {
            val profiles = ProfileCrypto.decryptProfiles(encryptedData, passphrase)
            val (valid, expired) = ProfileCrypto.filterValidProfiles(profiles)

            if (valid.isEmpty()) {
                Toast.makeText(this, "All profiles are expired!", Toast.LENGTH_LONG).show()
                isProcessing = false
                return
            }

            storage.addProfiles(valid)

            val msg = buildString {
                append("Imported ${valid.size} profile(s)")
                if (expired.isNotEmpty()) append(", ${expired.size} expired skipped")
            }

            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            finish()

        } catch (e: SecurityException) {
            Toast.makeText(this, "Decryption failed: ${e.message}", Toast.LENGTH_LONG).show()
            isProcessing = false
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
