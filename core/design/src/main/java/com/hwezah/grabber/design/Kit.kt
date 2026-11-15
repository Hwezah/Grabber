package com.hwezah.grabber.design

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Side margin of every page: tight on an upright phone. */
val EDGE: Dp = 8.dp

/** Padding inside cards and sheets. */
val CARD: Dp = 14.dp

/** A Lucide icon. */
@Composable
fun Lc(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = Ink.text, size: Dp = 20.dp) =
    Icon(painterResource(icon), null, modifier.size(size), tint = tint)

/** Anything tappable: dips while pressed, no ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    Box(
        modifier
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick),
        propagateMinConstraints = true,
    ) { content(pressed) }
}

/**
 * A pill button. [accent] set: a grainy glow in that colour with white words (red for the main action,
 * green for downloads, orange for torrents and conversion). Otherwise a quiet translucent tint.
 */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    accent: Color? = null,
    compact: Boolean = false,
) {
    Tap(onClick, modifier, RoundedCornerShape(50)) { pressed ->
        val fg = if (accent != null) Color.White else Ink.text
        // A solid accent, or a lighter shade of frosted glass.
        val base = if (accent != null) Modifier.background(if (pressed) accent.copy(alpha = 0.8f) else accent)
        else Modifier.frost(RoundedCornerShape(50), alpha = if (pressed) 0.14f else 0.08f)
        Row(
            base.height(if (compact) 42.dp else 50.dp).padding(horizontal = if (compact) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 18.dp)
                if (label.isNotEmpty()) Spacer(Modifier.width(5.dp))
            }
            if (label.isNotEmpty()) Text(label, style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A filter pill: filled with [accent] when on. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, accent: Color = Ink.red) {
    Tap(onClick, modifier, CircleShape) { pressed ->
        val bg = when {
            on -> accent
            pressed -> Ink.text.copy(alpha = 0.14f)
            else -> Ink.text.copy(alpha = 0.07f)
        }
        val fg = if (on) Color.White else Ink.muted
        Row(
            Modifier.background(bg).then(if (on) Modifier else Modifier.grain()).height(34.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 15.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                label, style = Type.label.copy(fontSize = 14.sp, color = fg, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            )
        }
    }
}

/** An on/off switch: a flat pill, red when on. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, accent: Color = Ink.red) {
    val travel by animateDpAsState(if (checked) 18.dp else 0.dp, tween(160), label = "toggle")
    val track by animateColorAsState(if (checked) accent else Ink.text.copy(alpha = 0.16f), tween(160), label = "track")
    val thumb by animateColorAsState(if (checked) Color.White else Ink.muted, tween(160), label = "thumb")
    Box(
        modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(50)).background(track)
            .toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = Role.Switch, onValueChange = onChange)
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = travel).size(18.dp).clip(CircleShape).background(thumb))
    }
}

/** Quiet text that says why something is empty; centred on a portrait phone. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = Type.body.copy(color = Ink.faint, textAlign = TextAlign.Center))

/** A page heading under the status bar. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.statusBarsPadding().padding(start = EDGE + 4.dp, end = EDGE + 4.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = Type.display)
        trailing()
    }
}

/** A small caps label over a group of rows. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, center: Boolean = false) =
    if (center) Text(text.uppercase(), modifier.fillMaxWidth().padding(bottom = 8.dp), style = Type.overline.copy(textAlign = TextAlign.Center))
    else Text(text.uppercase(), modifier.padding(start = 4.dp, bottom = 8.dp), style = Type.overline)

/**
 * Rows of pills: three or more spread across the width, first and last at the ends;
 * one or two centred with even space around them (never stuck to the left).
 */
fun spreadOut(min: Dp = 8.dp): Arrangement.Horizontal = object : Arrangement.Horizontal {
    override val spacing = min

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        val floor = spacing.roundToPx()
        val free = totalSize - sizes.sum()
        var x: Int
        val gap: Int
        if (sizes.size > 2) {
            gap = maxOf(floor, free / (sizes.size - 1))
            x = 0
        } else {
            // Space-around: half a gap at each end, a whole one between.
            gap = maxOf(floor, free / sizes.size.coerceAtLeast(1))
            x = maxOf(0, (totalSize - sizes.sum() - gap * (sizes.size - 1)) / 2)
        }
        sizes.forEachIndexed { i, w ->
            outPositions[i] = x
            x += w + gap
        }
    }
}

val SpreadOut: Arrangement.Horizontal = spreadOut()

/** How many sheets are open: the floating tab bar hides while any is. */
object Sheets {
    var open by mutableStateOf(0)
}

/** The frame every sheet shares: a dimmed page, a plain grey sheet with a grab handle and a heading. */
@Composable
fun BottomSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    androidx.compose.runtime.DisposableEffect(Unit) {
        Sheets.open++
        onDispose { Sheets.open-- }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically(Motion.spatial()) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .clip(shape).background(Ink.sheet)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().padding(bottom = 24.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(Ink.stroke))
                Text(title, Modifier.padding(start = CARD, end = CARD, top = 14.dp, bottom = 8.dp), style = Type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                content()
            }
        }
    }
}

/** A button alone on its row: as wide as everything else in the sheet. */
fun Modifier.solo() = fillMaxWidth()

/** A row of buttons stretched across the width, each the same size, 8dp apart. */
@Composable
fun StretchRow(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val n = measurables.size.coerceAtLeast(1)
        val g = gap.roundToPx()
        val w = ((constraints.maxWidth - g * (n - 1)) / n).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints.fixedWidth(w)) }
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, h) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (h - it.height) / 2)
                x += w + g
            }
        }
    }
}

/**
 * A row you can swipe: left runs [onLeft] (e.g. delete), right runs [onRight] (e.g. pin). Behind the row,
 * the side being revealed shows its colour and icon. Past a third of the width it goes; short of that it springs back.
 */
@Composable
fun SwipeRow(
    onLeft: () -> Unit,
    onRight: () -> Unit,
    @DrawableRes leftIcon: Int,
    @DrawableRes rightIcon: Int,
    leftColor: Color,
    rightColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var width by remember { mutableStateOf(1f) }
    Box(modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        val x = offset.value
        if (x != 0f) {
            val right = x > 0
            Box(
                Modifier.matchParentSize().clip(shape).background((if (right) rightColor else leftColor).copy(alpha = (kotlin.math.abs(x) / (width / 3)).coerceIn(0.25f, 1f))),
                contentAlignment = if (right) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Lc(if (right) rightIcon else leftIcon, Modifier.padding(horizontal = 24.dp), tint = Color.White, size = 22.dp)
            }
        }
        Box(
            Modifier.graphicsLayer { translationX = offset.value }
                .draggable(
                    androidx.compose.foundation.gestures.rememberDraggableState { d -> scope.launch { offset.snapTo(offset.value + d) } },
                    androidx.compose.foundation.gestures.Orientation.Horizontal,
                    onDragStopped = {
                        val v = offset.value
                        scope.launch {
                            if (kotlin.math.abs(v) > width / 3) {
                                offset.animateTo(if (v > 0) width else -width, tween(160))
                                if (v > 0) onRight() else onLeft()
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f, Motion.spatial())
                            }
                        }
                    },
                ),
        ) { content() }
    }
}
