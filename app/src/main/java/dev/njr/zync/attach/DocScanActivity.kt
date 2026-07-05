package dev.njr.zync.attach

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.njr.zync.ZyncApp
import dev.njr.zync.data.AttachmentType
import kotlinx.coroutines.launch

class DocScanActivity : ComponentActivity() {
    private val scannerLauncher = registerForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            finish()
            return@registerForActivityResult
        }
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdfUri = scan?.pdf?.uri
        if (pdfUri == null) {
            Toast.makeText(this, "No PDF returned from scanner", Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
                    ?: error("Cannot read scanned PDF")
                CaptureRepository(application as ZyncApp).importBytes(
                    title = CaptureRepository.timestampTitle("Scan"),
                    type = AttachmentType.PDF,
                    bytes = bytes,
                    extension = "pdf",
                )
                Toast.makeText(this@DocScanActivity, "Scan captured to Inbox", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this@DocScanActivity, e.message ?: "Scan capture failed", Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, e.message ?: "Cannot start scanner", Toast.LENGTH_LONG).show()
                finish()
            }
    }
}
