package dev.njr.zync.attach

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.ZyncApp
import kotlinx.coroutines.launch

class ShareCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capture = CaptureRepository(application as ZyncApp)
        lifecycleScope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_SEND -> importSingle(capture, intent)
                    Intent.ACTION_SEND_MULTIPLE -> importMultiple(capture, intent)
                    else -> error("Unsupported share action")
                }
                Toast.makeText(this@ShareCaptureActivity, "Captured to Inbox", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this@ShareCaptureActivity, e.message ?: "Capture failed", Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }

    private suspend fun importSingle(capture: CaptureRepository, intent: Intent) {
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            stream != null -> capture.importUri(stream)
            !text.isNullOrBlank() -> capture.importSharedText(text)
            else -> error("Nothing to capture")
        }
    }

    private suspend fun importMultiple(capture: CaptureRepository, intent: Intent) {
        val streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: emptyList()
        if (streams.isEmpty()) error("Nothing to capture")
        for (uri in streams) capture.importUri(uri)
    }
}
