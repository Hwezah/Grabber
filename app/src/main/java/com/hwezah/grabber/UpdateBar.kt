package com.hwezah.grabber

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type

/**
 * A slim strip along the top when a newer build is out: it downloads on its own (spinner, "Downloading update…"),
 * then shows a green dot, "Update ready" and Install. One line; ✕ hides it until Grabber is next opened.
 */
@Composable
fun UpdateBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = Updater.status
    val shown = s !is Updater.Status.None && !Updater.dismissed
    val rise = with(LocalDensity.current) { 18.dp.roundToPx() }
    val spring = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)
    AnimatedVisibility(
        shown, modifier,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300, easing = spring)) { rise } + scaleIn(tween(300, easing = spring), initialScale = 0.98f),
        exit = fadeOut(tween(200)),
    ) { Strip(s) { Updater.update(context) } }
}

@Composable
private fun Strip(s: Updater.Status, onAction: () -> Unit) {
    val good = if (Ink.isDark) Color(0xFF46D369) else Color(0xFF1E8E3E)
    val build = (Updater.fetched?.code ?: (s as? Updater.Status.Ready)?.code ?: Updater.last?.code)?.let { "build ${it - 100}" }
    val about = listOfNotNull(build, Updater.summary.takeIf { it.isNotBlank() }).joinToString(" — ")
    val (bold, muted) = when (s) {
        is Updater.Status.Ready, is Updater.Status.Downloading -> "Downloading update…" to about
        is Updater.Status.Downloaded -> "Update ready" to about
        Updater.Status.Installing -> "Installing…" to "confirm Android's prompt"
        Updater.Status.NeedsPermission -> "Allow installs" to "let Grabber install updates, then tap Install"
        is Updater.Status.Failed -> s.message to ""
        Updater.Status.None -> "" to ""
    }
    val action = when (s) {
        is Updater.Status.Downloaded, Updater.Status.NeedsPermission -> "Install"
        is Updater.Status.Failed -> if (Updater.last != null || Updater.fetched != null) "Retry" else null
        else -> null
    }
    // Frosted page colour, flat: no border, shadow or gradient.
    val surface = if (Ink.isDark) Color(0xD10A0A0A) else Color(0xD6F6F2EE)
    Row(
        Modifier.fillMaxWidth().background(surface).statusBarsPadding().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The status mark: a spinner while busy, a green dot when ready, an accent dot when something needs you.
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (s) {
                is Updater.Status.Ready, is Updater.Status.Downloading, Updater.Status.Installing ->
                    CircularProgressIndicator(Modifier.size(14.dp), color = Ink.text, strokeWidth = 2.dp, trackColor = Color.Transparent)
                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (s is Updater.Status.Downloaded) good else Ink.red))
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Ink.text)) { append(bold) }
                if (muted.isNotEmpty()) withStyle(SpanStyle(fontSize = 13.sp, color = Ink.muted)) { append(" · $muted") }
            },
            Modifier.weight(1f), style = Type.body.copy(fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (action != null) Tap(onAction, shape = RoundedCornerShape(50)) { pressed ->
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(50))
                    .background(lerp(Ink.red, Color.Black, if (pressed) 0.28f else 0.12f)).padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) { Text(action, style = Type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)) }
        }
        if (s !is Updater.Status.Installing) Tap({ Updater.dismissed = true }, Modifier.size(34.dp), CircleShape) { pressed ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (pressed) Ink.text.copy(alpha = 0.08f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { Lc(L.drawable.lucide_ic_x, tint = Ink.text, size = 18.dp) }
        }
    }
}
