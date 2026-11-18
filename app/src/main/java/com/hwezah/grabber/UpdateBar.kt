package com.hwezah.grabber

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
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

/**
 * A bar across the top when a newer build is on GitHub: a white card that fills with the accent from the left as the
 * update downloads, the words turning white where the colour has reached them.
 */
@Composable
fun UpdateBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = Updater.status
    if (s is Updater.Status.None || Updater.dismissed) return
    val title = when (s) {
        is Updater.Status.Ready -> "Update ready · build ${s.code - 100}"
        is Updater.Status.Downloading -> "Downloading update…"
        Updater.Status.Installing -> "Installing…"
        Updater.Status.NeedsPermission -> "Allow installing updates"
        is Updater.Status.Failed -> "Update didn't finish"
        Updater.Status.None -> ""
    }
    val line = when (s) {
        is Updater.Status.Ready -> "Tap Update, then confirm Android's prompt"
        is Updater.Status.Downloading -> listOfNotNull(
            "${s.pct}%",
            if (s.total > 0) "${Fmt.bytes(s.done)} of ${Fmt.bytes(s.total)}" else null,
            "the installer opens when it's done",
        ).joinToString(" · ")
        Updater.Status.Installing -> "Confirm Android's prompt"
        Updater.Status.NeedsPermission -> "Allow Grabber to install updates, then tap Update"
        is Updater.Status.Failed -> s.message
        Updater.Status.None -> ""
    }
    val fill = when (s) {
        is Updater.Status.Downloading -> s.pct / 100f
        Updater.Status.Installing -> 1f
        else -> 0f
    }
    val ink = androidx.compose.ui.graphics.Color(0xFF141414)
    var cardW by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val white = androidx.compose.ui.graphics.Color.White
    // The words, in [color]: drawn dark on the white, and again in white over the filled part.
    val words: @Composable (androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color) -> Unit = { c, sub ->
        androidx.compose.foundation.layout.Column {
            Text(title, style = Type.title.copy(fontSize = 17.sp, color = c), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(line, Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 14.sp, color = sub), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    Box(
        modifier.statusBarsPadding().padding(horizontal = EDGE, vertical = 8.dp).fillMaxWidth()
            .onSizeChanged { cardW = it.width.toFloat() }
            .clip(RoundedCornerShape(18.dp)).background(white)
            .drawBehind { drawRect(Ink.red, size = this.size.copy(width = this.size.width * fill)) },
    ) {
        Row(Modifier.heightIn(min = 68.dp).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                words(ink, androidx.compose.ui.graphics.Color(0xFF5F5F5F))
                // The same words in white, shown only where the colour has filled.
                Box(Modifier.matchParentSize().drawWithContent {
                    // The fill's edge, measured from the card's left, less the padding before the words.
                    val right = cardW * fill - 18.dp.toPx()
                    clipRect(right = right.coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
                }) { words(white, white.copy(alpha = 0.85f)) }
            }
            when (s) {
                is Updater.Status.Ready -> Button("Update", { Updater.update(context) }, accent = Ink.red, compact = true)
                is Updater.Status.Failed -> Updater.last?.let { r ->
                    Button("Retry", { Updater.status = r; Updater.update(context) }, accent = Ink.red, compact = true)
                }
                else -> {}
            }
            if (s !is Updater.Status.Installing) com.hwezah.grabber.design.Tap({ Updater.dismissed = true }, Modifier.size(48.dp), RoundedCornerShape(50)) {
                Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_x, tint = ink, size = 30.dp) }
            }
        }
    }
}
