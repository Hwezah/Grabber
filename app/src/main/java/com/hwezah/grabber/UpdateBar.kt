package com.hwezah.grabber

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
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
    Row(
        modifier.statusBarsPadding().padding(horizontal = EDGE, vertical = 6.dp).fillMaxWidth()
            .frost(RoundedCornerShape(14.dp), Ink.surface).padding(start = CARD, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lc(L.drawable.lucide_ic_download, tint = if (s is Updater.Status.Failed) Ink.orange else Ink.green, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
        when (s) {
            is Updater.Status.Ready -> Button("Update", { Updater.update(context) }, accent = Ink.green, compact = true)
            is Updater.Status.Failed -> Button("", { Updater.dismissed = true; Updater.status = Updater.Status.None }, icon = L.drawable.lucide_ic_x, compact = true)
            else -> {}
        }
    }
}
