package com.hwezah.grabber

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.EDGE
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.frost

/** A bar across the top when a newer build is on GitHub. */
@Composable
fun UpdateBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = Updater.status
    if (s is Updater.Status.None || Updater.dismissed) return
    val text = when (s) {
        is Updater.Status.Ready -> "Update ready · build ${s.code - 100}"
        is Updater.Status.Downloading -> "Downloading update… ${s.pct}%"
        Updater.Status.Installing -> "Installing…"
        Updater.Status.NeedsPermission -> "Allow Grabber to install updates, then tap Update"
        is Updater.Status.Failed -> s.message
        Updater.Status.None -> ""
    }
    // Solid, so the page's heading doesn't show through; taller, with room for a progress line.
    Row(
        modifier.statusBarsPadding().padding(horizontal = EDGE, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(Ink.sheet)
            .heightIn(min = 72.dp).padding(start = CARD, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background((if (s is Updater.Status.Failed) Ink.orange else Ink.green).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Lc(L.drawable.lucide_ic_download, tint = if (s is Updater.Status.Failed) Ink.orange else Ink.green, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(text, style = Type.title.copy(fontSize = 16.sp), maxLines = 2)
            if (s is Updater.Status.Downloading) {
                Box(Modifier.padding(top = 8.dp, end = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Ink.text.copy(alpha = 0.1f))) {
                    Box(Modifier.fillMaxWidth(s.pct / 100f).height(4.dp).clip(RoundedCornerShape(50)).background(Ink.green))
                }
            } else if (s is Updater.Status.Ready) {
                Text("Tap Update, then confirm Android's prompt", Modifier.padding(top = 2.dp), style = Type.meta)
            }
        }
        when (s) {
            is Updater.Status.Ready -> Button("Update", { Updater.update(context) }, accent = Ink.green, compact = true)
            is Updater.Status.Failed -> {
                Updater.last?.let { r ->
                    Button("Retry", { Updater.status = r; Updater.update(context) }, accent = Ink.green, compact = true)
                    Spacer(Modifier.width(6.dp))
                }
                Button("", { Updater.dismissed = true; Updater.status = Updater.Status.None }, icon = L.drawable.lucide_ic_x, compact = true)
            }
            else -> {}
        }
    }
}
