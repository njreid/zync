package dev.njr.zync.capture

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.ZyncApp
import dev.njr.zync.attach.AttachmentStore
import dev.njr.zync.data.AttachmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal record-a-voice-note screen launched by the double-Volume-Up gesture
 * (or directly). Records with [MediaRecorder]; on "Stop & save" it stores the
 * clip and creates an Inbox task via `NodeRepository.captureToInbox`.
 *
 * Runtime behaviour (mic, MediaRecorder) is device-verified.
 */
class VoiceCaptureActivity : ComponentActivity() {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var recording = false

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else finishWith("Microphone permission needed")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private lateinit var status: TextView

    private fun buildUi(): ViewGroup {
        status = TextView(this).apply { text = "Preparing…" }
        val stop = Button(this).apply {
            text = "Stop & save"
            setOnClickListener { stopAndSave() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(stop)
        }
    }

    private fun startRecording() {
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        outFile = file
        recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recording = true
        status.text = "Recording… tap Stop when done"
    }

    private fun stopAndSave() {
        if (!recording) { finish(); return }
        recording = false
        val file = outFile
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        if (file == null || !file.isFile || file.length() == 0L) {
            finishWith("Nothing recorded")
            return
        }
        val app = application as ZyncApp
        val store = AttachmentStore.default(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = file.readBytes()
            app.repository.captureToInbox("Voice note", AttachmentType.AUDIO, bytes, "m4a", store)
            file.delete()
            withContext(Dispatchers.Main) { finishWith("Added voice note to Inbox") }
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { if (recording) { recorder?.stop() } }
        recorder?.release()
        recorder = null
    }
}
