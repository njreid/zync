package dev.njr.zync.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.njr.zync.ZyncApp
import dev.njr.zync.data.AttachmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launches the Google Play Services document scanner (no CAMERA permission —
 * Play Services owns the capture UI) via the double-Volume-Down gesture, then
 * stores the resulting PDF as a new Inbox task.
 *
 * Runtime behaviour (the scanner UI, Play Services availability) is
 * device-verified.
 */
class DocScanActivity : ComponentActivity() {

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            onScanResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = GmsDocumentScannerOptions.Builder()
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setGalleryImportAllowed(true)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scanLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                finishWith(e.message ?: "Document scanner unavailable")
            }
    }

    private fun onScanResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) {
            finish()
            return
        }
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdfUri = scan?.pdf?.uri ?: run { finishWith("No document captured"); return }

        val app = application as ZyncApp
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                withContext(Dispatchers.Main) { finishWith("Could not read the scan") }
                return@launch
            }
            app.captureToInbox("Scanned document", AttachmentType.PDF, bytes, "pdf")
            withContext(Dispatchers.Main) { finishWith("Added scan to Inbox") }
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
