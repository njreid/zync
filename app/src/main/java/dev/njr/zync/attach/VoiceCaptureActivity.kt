package dev.njr.zync.attach

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.njr.zync.ZyncApp
import dev.njr.zync.data.AttachmentType
import java.io.File
import kotlinx.coroutines.launch

class VoiceCaptureActivity : ComponentActivity() {
    private var recorder: MediaRecorder? = null
    private lateinit var outputFile: File
    private lateinit var status: TextView
    private lateinit var stopButton: Button

    private val permissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) startRecording() else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Ready to record" }
        stopButton = Button(this).apply {
            text = "Stop and save"
            isEnabled = false
            setOnClickListener { stopAndSave() }
        }
        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { cancelRecording() }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(status)
                addView(stopButton)
                addView(cancelButton)
            },
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        outputFile = File.createTempFile("zync-voice-", ".m4a", cacheDir)
        recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        status.text = "Recording..."
        stopButton.isEnabled = true
    }

    private fun stopAndSave() {
        stopButton.isEnabled = false
        runCatching {
            recorder?.stop()
        }
        recorder?.release()
        recorder = null
        lifecycleScope.launch {
            try {
                CaptureRepository(application as ZyncApp).importBytes(
                    title = CaptureRepository.timestampTitle("Voice note"),
                    type = AttachmentType.AUDIO,
                    bytes = outputFile.readBytes(),
                    extension = "m4a",
                )
                Toast.makeText(this@VoiceCaptureActivity, "Voice note captured to Inbox", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this@VoiceCaptureActivity, e.message ?: "Voice capture failed", Toast.LENGTH_LONG).show()
            } finally {
                outputFile.delete()
                finish()
            }
        }
    }

    private fun cancelRecording() {
        recorder?.release()
        recorder = null
        if (::outputFile.isInitialized) outputFile.delete()
        finish()
    }

    override fun onDestroy() {
        recorder?.release()
        recorder = null
        super.onDestroy()
    }
}
