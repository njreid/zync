package dev.njr.zync.attach

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import dev.njr.zync.ZyncApp
import dev.njr.zync.data.AttachmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles `ACTION_SEND` / `ACTION_SEND_MULTIPLE` shares of audio or PDF content
 * (see the intent-filter in the manifest) by importing each shared item as a
 * new Inbox task with its blob attached. This is the pragmatic capture path for
 * "voice notes and scanned docs into the Inbox": record in any voice-memo app,
 * or scan in any scanner/Drive app, then Share → zync.
 *
 * No custom camera/mic UI and no runtime permission is required — reading a
 * shared `content://` Uri is granted by the sender via the share intent.
 *
 * The transient blank UI is invisible (`Theme.Translucent.NoTitleBar` per the
 * manifest): we import, toast, and finish.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        val sharedText = if (uris.isEmpty()) intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim() else null
        if (uris.isEmpty() && sharedText.isNullOrEmpty()) {
            toastAndFinish("Nothing to add to zync")
            return
        }
        val app = application as ZyncApp
        if (!sharedText.isNullOrEmpty()) {
            // A shared URL/text becomes a plain Inbox note: title from the subject or
            // a compact form of the URL, the full text preserved in notes.
            val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    app.replicaCapture.captureNote(ShareImport.titleForText(subject, sharedText), notes = sharedText)
                    app.contentChanges.notifyChanged()
                    dev.njr.zync.sync.SyncScheduler.requestSync(app)
                }
                withContext(Dispatchers.Main) { toastAndFinish("Added to your Inbox") }
            }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var added = 0
            for (uri in uris) {
                if (importOne(app, uri)) added++
            }
            withContext(Dispatchers.Main) {
                toastAndFinish(
                    if (added > 0) "Added $added item(s) to your Inbox"
                    else "Unsupported share (audio, PDF, images, links)"
                )
            }
        }
    }

    private suspend fun importOne(app: ZyncApp, uri: Uri): Boolean {
        val mime = contentResolver.getType(uri)
        val type: AttachmentType = ShareImport.typeFor(mime) ?: return false
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return false
        if (bytes.size > 25 * 1024 * 1024) return false // shares can be huge; cap them
        app.captureToInbox(
            title = ShareImport.titleFor(type, mime),
            type = type,
            bytes = bytes,
            extension = ShareImport.extensionFor(mime),
        )
        return true
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }
}
