package dev.njr.zync.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.njr.zync.MainActivity
import dev.njr.zync.R
import dev.njr.zync.capture.DocScanActivity
import dev.njr.zync.capture.DocUploadActivity
import dev.njr.zync.capture.VoiceCaptureActivity

class QuickCaptureWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { QuickCaptureContent() }
    }
}

class QuickCaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()
}

@Composable
private fun QuickCaptureContent() {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(0xfff8fafc.toInt()))
            .padding(12),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.mipmap.ic_launcher),
                contentDescription = "zync",
                modifier = GlanceModifier.padding(end = 8),
            )
            Text(
                text = "zync",
                style = TextStyle(fontWeight = FontWeight.Bold),
            )
        }
        Button(
            text = "Type",
            onClick = actionStartActivity<MainActivity>(),
            modifier = GlanceModifier.fillMaxWidth().padding(top = 8),
        )
        Button(
            text = "Voice",
            onClick = actionStartActivity<VoiceCaptureActivity>(),
            modifier = GlanceModifier.fillMaxWidth().padding(top = 6),
        )
        Button(
            text = "Scan",
            onClick = actionStartActivity<DocScanActivity>(),
            modifier = GlanceModifier.fillMaxWidth().padding(top = 6),
        )
        Button(
            text = "Upload",
            onClick = actionStartActivity<DocUploadActivity>(),
            modifier = GlanceModifier.fillMaxWidth().padding(top = 6),
        )
    }
}
