package com.hasan.airspeaker

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.hasan.airspeaker.databinding.ActivityQrScannerBinding
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult

class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private var isTorchOn = false
    private var hasResult = false

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            decodeImageUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            binding.barcodeView.setTorch(isTorchOn)
            binding.btnTorch.text = if (isTorchOn) "🔦 Flash On" else "🔦 Flash"
        }

        binding.btnGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        binding.btnPaste.setOnClickListener {
            val clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val item = clipMgr?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!item.isNullOrBlank()) {
                handleScanResult(item)
            } else {
                Toast.makeText(this, "Clipboard is empty. Copy a party join link first.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text?.trim()
                if (!text.isNullOrBlank()) {
                    handleScanResult(text)
                }
            }
        })
    }

    private fun decodeImageUri(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    val width = bitmap.width
                    val height = bitmap.height
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    val source = RGBLuminanceSource(width, height, pixels)
                    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                    val reader = MultiFormatReader()
                    val result = reader.decode(binaryBitmap)
                    val text = result.text?.trim()
                    if (!text.isNullOrBlank()) {
                        handleScanResult(text)
                        return
                    }
                }
            }
            Toast.makeText(this, "No QR code found in selected photo.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not decode QR code from image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleScanResult(text: String) {
        if (hasResult) return
        hasResult = true
        val data = Intent().apply {
            putExtra("SCAN_RESULT", text)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    override fun onResume() {
        super.onResume()
        binding.barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeView.pause()
    }
}
