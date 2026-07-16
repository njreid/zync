package dev.njr.zync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.njr.zync.ui.ZyncColors as C

/**
 * The bar's Text capture: a minimal native sheet straight into the inbox (the :web
 * inbox is a triage surface and no longer has an entry field — capture owns creation).
 */
@Composable
fun TextCaptureDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC0D1117))
            .clickable(onClick = onDismiss),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .background(C.Card, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(18.dp)
                .imePadding(),
        ) {
            BasicText("Capture", style = TextStyle(color = C.Ink2, fontSize = 12.sp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = C.Ink, fontSize = 18.sp),
                cursorBrush = SolidColor(C.Ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .background(C.Surface, RoundedCornerShape(10.dp))
                    .padding(12.dp)
                    .focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText(
                    "Cancel",
                    style = TextStyle(color = C.Ink3, fontSize = 15.sp),
                    modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
                )
                BasicText(
                    "Save to Inbox",
                    style = TextStyle(color = C.Accent, fontSize = 15.sp),
                    modifier = Modifier
                        .clickable(enabled = text.isNotBlank()) { onSave(text.trim()); onDismiss() }
                        .padding(12.dp),
                )
            }
        }
    }
}
