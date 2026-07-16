package dev.njr.zync.ui.capture

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.njr.zync.R
import dev.njr.zync.capture.CaptureExtractor
import dev.njr.zync.capture.RulesExtractor
import dev.njr.zync.capture.Suggestion
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.ui.CharonMono
import dev.njr.zync.ui.Geomini
import dev.njr.zync.ui.ZyncColors as C
import dev.njr.zync.web.content.ContextView
import dev.njr.zync.web.content.DueDates
import dev.njr.zync.web.content.NodeView
import java.time.LocalDate

/** What Save hands back to the host (which builds the ops). */
data class CaptureResult(
    val title: String,
    val notes: String?,
    val contextId: Ulid?,
    val dueMillis: Long?,
    val person: String?,
    /** Non-null = file directly under this node (skips the Inbox). */
    val parentId: Ulid?,
)

private enum class CaptureMode { Voice, Type, Photo, Scan }

/**
 * The native capture screen (spec + mock v3, 2026-07-16): voice-first with live
 * on-device transcription; editable fields are the save surface; the suggestion
 * card is a one-tap prefill; file-under candidates are separately pickable boxes.
 */
@Composable
fun CaptureScreen(
    contexts: List<ContextView>,
    projects: List<NodeView>,
    contactMatch: (String) -> String?,
    requestRecordAudio: ((Boolean) -> Unit) -> Unit,
    onPhoto: () -> Unit,
    onScan: () -> Unit,
    onSave: (CaptureResult) -> Unit,
    onDismiss: () -> Unit,
    extractor: CaptureExtractor = RulesExtractor,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(CaptureMode.Voice) }
    var listening by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf<Boolean?>(null) }
    var finalTranscript by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }

    var title by remember { mutableStateOf("") }
    var pickedContext by remember { mutableStateOf<ContextView?>(null) }
    var dueMillis by remember { mutableStateOf<Long?>(null) }
    var person by remember { mutableStateOf<String?>(null) }
    var pickedParent by remember { mutableStateOf<NodeCand?>(null) }
    var applied by remember { mutableStateOf(false) }

    val transcript = (finalTranscript + " " + partial).trim()
    val suggestion: Suggestion? = remember(transcript, contexts, projects) {
        transcript.takeIf { it.length >= 4 }?.let {
            val s = extractor.extract(it, contexts, projects, LocalDate.now())
            s.copy(person = s.person?.let { p -> contactMatch(p) ?: p })
        }
    }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { requestRecordAudio { granted -> micGranted = granted; listening = granted } }

    // The recognizer lives exactly as long as we're listening in Voice mode.
    DisposableEffect(listening, mode) {
        var recognizer: SpeechRecognizer? = null
        if (mode == CaptureMode.Voice && listening && SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onPartialResults(bundle: Bundle) {
                        partial = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial
                    }
                    override fun onResults(bundle: Bundle) {
                        val heard = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!heard.isNullOrBlank()) finalTranscript = (finalTranscript + " " + heard).trim()
                        partial = ""
                        listening = false
                    }
                    override fun onError(error: Int) { partial = ""; listening = false }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    },
                )
            }
        }
        onDispose { recognizer?.destroy() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // header + modes
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("Capture", style = TextStyle(color = C.Ink, fontSize = 17.sp, fontFamily = Geomini, fontWeight = FontWeight.SemiBold))
            BasicText("✕", style = TextStyle(color = C.Ink3, fontSize = 20.sp), modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp))
        }
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModePill("Voice", mode == CaptureMode.Voice) { mode = CaptureMode.Voice; if (micGranted == true) listening = true }
            ModePill("Type", mode == CaptureMode.Type) { mode = CaptureMode.Type; listening = false }
            ModePill("Photo", false) { onPhoto() }
            ModePill("Scan", false) { onScan() }
        }

        // live area
        if (mode == CaptureMode.Voice) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier
                        .size(42.dp)
                        .border(1.dp, if (listening) C.Accent else C.Border, CircleShape)
                        .background(C.Card, CircleShape)
                        .clickable { if (micGranted == true) listening = !listening },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painterResource(R.drawable.ic_voice),
                        contentDescription = if (listening) "Stop" else "Listen",
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(if (listening) C.Accent else C.Ink3),
                    )
                }
                BasicText(
                    when {
                        micGranted == false -> "Mic permission needed — use Type"
                        listening -> "Listening… on-device · tap to stop"
                        transcript.isEmpty() -> "Tap the mic to start"
                        else -> "Stopped · transcript kept with the task"
                    },
                    style = TextStyle(color = C.Ink2, fontSize = 13.sp, fontFamily = Geomini),
                )
            }
            if (transcript.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 18.dp)) {
                    BasicText("“$finalTranscript", style = TextStyle(color = C.Ink2, fontSize = 15.sp, fontFamily = Geomini))
                    if (partial.isNotEmpty()) BasicText(" $partial…", style = TextStyle(color = C.Ink3, fontSize = 15.sp, fontFamily = Geomini))
                    BasicText("”", style = TextStyle(color = C.Ink2, fontSize = 15.sp, fontFamily = Geomini))
                }
            }
        }

        // editable fields — the save surface
        BasicText("TASK", style = TextStyle(color = C.Ink3, fontSize = 11.sp, fontFamily = Geomini, letterSpacing = 0.6.sp), modifier = Modifier.padding(start = 18.dp, top = 14.dp))
        BasicTextField(
            value = title,
            onValueChange = { title = it },
            textStyle = TextStyle(color = C.Ink, fontSize = 17.sp, fontFamily = Geomini, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(C.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .background(C.Card, RoundedCornerShape(10.dp))
                .border(1.dp, C.Border, RoundedCornerShape(10.dp))
                .padding(12.dp),
        )
        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pickedContext?.let { Chip("context", it.name ?: "?") { pickedContext = null } }
            dueMillis?.let { Chip("due", DueDates.format(it)) { dueMillis = null } }
            person?.let { Chip("person", it) { person = null } }
        }

        // suggestion card: one-tap prefill
        suggestion?.let { s ->
            BasicText("SUGGESTED — ON-DEVICE", style = TextStyle(color = C.Ink3, fontSize = 11.sp, fontFamily = Geomini, letterSpacing = 0.6.sp), modifier = Modifier.padding(start = 18.dp, top = 12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .border(1.dp, if (applied) C.Accent else C.Border, RoundedCornerShape(12.dp))
                    .background(C.Card, RoundedCornerShape(12.dp))
                    .clickable {
                        title = s.title
                        pickedContext = contexts.firstOrNull { it.id.toString() == s.contextId }
                        dueMillis = s.dueMillis
                        person = s.person
                        applied = true
                    }
                    .padding(12.dp),
            ) {
                BasicText(s.title, style = TextStyle(color = C.Ink, fontSize = 15.sp, fontFamily = Geomini, fontWeight = FontWeight.SemiBold))
                val mini = listOfNotNull(s.contextName, s.dueLabel, s.person).joinToString(" · ")
                if (mini.isNotEmpty()) BasicText(mini, style = TextStyle(color = C.Ink2, fontSize = 12.sp, fontFamily = Geomini), modifier = Modifier.padding(top = 4.dp))
                BasicText(
                    if (applied) "✓ Applied — tweak above" else "Tap to fill the fields",
                    style = TextStyle(color = if (applied) C.Accent else C.Ink3, fontSize = 11.sp, fontFamily = Geomini),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // file-under: separately pickable boxes
            BasicText("FILE UNDER", style = TextStyle(color = C.Ink3, fontSize = 11.sp, fontFamily = Geomini, letterSpacing = 0.6.sp), modifier = Modifier.padding(start = 18.dp, top = 10.dp))
            LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp)) {
                items(s.nodeCandidates, key = { it.id }) { cand ->
                    NodeBox(cand.title, cand.why, pickedParent?.id == cand.id) {
                        pickedParent = if (pickedParent?.id == cand.id) null else NodeCand(cand.id, cand.title)
                    }
                }
                items(listOf("inbox")) { _ ->
                    NodeBox("Inbox", "decide later", pickedParent == null) { pickedParent = null }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Save raw", primary = false, enabled = transcript.isNotBlank() || title.isNotBlank(), Modifier.weight(1f)) {
                onSave(CaptureResult(title.ifBlank { RulesExtractor.cleanTitle(transcript) }, transcript.takeIf { it.isNotBlank() }, null, null, null, null))
                onDismiss()
            }
            ActionButton(
                pickedParent?.let { "Save to ${it.title}" } ?: "Save to Inbox",
                primary = true,
                enabled = title.isNotBlank() || transcript.isNotBlank(),
                Modifier.weight(1f),
            ) {
                onSave(
                    CaptureResult(
                        title = title.ifBlank { RulesExtractor.cleanTitle(transcript) },
                        notes = transcript.takeIf { it.isNotBlank() && it != title },
                        contextId = pickedContext?.id,
                        dueMillis = dueMillis,
                        person = person,
                        parentId = pickedParent?.let { runCatching { Ulid.parse(it.id) }.getOrNull() },
                    ),
                )
                onDismiss()
            }
        }

        // Quick-capture hardware shortcut: double-press volume-down opens this screen
        // from anywhere once the accessibility service is on (can't be enabled
        // programmatically — deep-link the user to the toggle).
        val quickKeys = remember { dev.njr.zync.capture.ZyncCaptureService.isEnabled(context) }
        if (!quickKeys) {
            BasicText(
                "vol− ×2 opens capture from anywhere — enable in Accessibility",
                style = TextStyle(color = C.Ink3, fontSize = 12.sp, fontFamily = Geomini, textDecoration = TextDecoration.Underline),
                modifier = Modifier
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
    }
}

private data class NodeCand(val id: String, val title: String)

@Composable
private fun ModePill(label: String, active: Boolean, onTap: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(
            color = if (active) C.Surface else C.Ink2,
            fontSize = 12.sp,
            fontFamily = CharonMono,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier
            .background(if (active) C.Ink else C.Surface, RoundedCornerShape(999.dp))
            .border(1.dp, if (active) C.Ink else C.Border, RoundedCornerShape(999.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun Chip(kind: String, value: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .border(1.dp, C.Border, RoundedCornerShape(999.dp))
            .background(C.Card, RoundedCornerShape(999.dp))
            .clickable(onClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(kind, style = TextStyle(color = C.Ink3, fontSize = 11.sp, fontFamily = CharonMono, fontWeight = FontWeight.Bold))
        BasicText(value, style = TextStyle(color = C.Ink, fontSize = 13.sp, fontFamily = Geomini))
        BasicText("✕", style = TextStyle(color = C.Ink3, fontSize = 12.sp))
    }
}

@Composable
private fun NodeBox(title: String, why: String, picked: Boolean, onTap: () -> Unit) {
    Column(
        Modifier
            .border(1.dp, if (picked) C.Accent else C.Border, RoundedCornerShape(12.dp))
            .background(C.Card, RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicText(
            if (picked) "$title ✓" else title,
            style = TextStyle(color = if (picked) C.Ink else C.Ink2, fontSize = 14.sp, fontFamily = Geomini, fontWeight = FontWeight.Medium),
        )
        BasicText(why, style = TextStyle(color = C.Ink3, fontSize = 11.sp, fontFamily = Geomini), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onTap: () -> Unit) {
    Row(
        modifier
            .background(if (primary) C.Accent.copy(alpha = if (enabled) 1f else .4f) else C.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, if (primary) C.Accent.copy(alpha = if (enabled) 1f else .4f) else C.Border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onTap)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (primary) androidx.compose.ui.graphics.Color.White else C.Ink2,
                fontSize = 15.sp,
                fontFamily = Geomini,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}
