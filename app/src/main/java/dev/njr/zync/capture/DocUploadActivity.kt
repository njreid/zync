package dev.njr.zync.capture

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.ZyncApp
import dev.njr.zync.attach.CaptureRepository
import kotlinx.coroutines.launch

/** Lets the user pick an existing document and imports it as a new Inbox task. */
class DocUploadActivity : ComponentActivity() {
    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    CaptureRepository(application as ZyncApp).importUri(uri, "Uploaded document")
                }.onSuccess {
                    finishWith("Added document to Inbox")
                }.onFailure { e ->
                    finishWith(e.message ?: "Could not upload document")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocument.launch(arrayOf("application/pdf", "image/*", "text/*"))
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }
}
