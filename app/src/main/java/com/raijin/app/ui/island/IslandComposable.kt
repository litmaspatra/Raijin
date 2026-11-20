package com.raijin.app.ui.island

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.ui.theme.*

private val IslandShape = RoundedCornerShape(50)

@Composable
fun IslandComposable(
    state: IslandState,
    onTap: () -> Unit,
    onAction: (traceId: String, actionId: String, payload: String) -> Unit,
    modifier: Modifier = Modifier,
    /** UI-5: dismiss affordance for the long-answer card; defaults to collapse. */
    onDismiss: (() -> Unit)? = null,
    /** Test hook: render the long-answer card pre-expanded (snapshot tests). */
    initialExpanded: Boolean = false,
    /** Test hook: hoisted scroll state for the long-answer card. */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val targetWidth = if (state is IslandState.Idle)
        (88 * fontScale.coerceAtLeast(1f)).coerceAtMost(160f).dp
    else state.targetWidthDp().dp

    val animWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "island_width",
    )

    val shape = if (state is IslandState.Idle || state is IslandState.Listening)
        IslandShape else RoundedCornerShape(18.dp)

    // Holographic cybernetic HUD border gradient
    val hudBorder = BorderStroke(
        width = 1.dp,
        brush = Brush.horizontalGradient(
            listOf(
                RaijinBlue.copy(alpha = 0.25f),
                RaijinBlue.copy(alpha = 0.85f),
                RaijinBlueLight.copy(alpha = 0.95f),
                RaijinBlue.copy(alpha = 0.85f),
                RaijinBlue.copy(alpha = 0.25f),
            )
        )
    )

    Box(
        modifier = modifier
            .width(animWidth)
            .clip(shape)
            .background(RaijinSurfaceGlass)
            .border(hudBorder, shape)
            .clickable(onClick = onTap)
            .animateContentSize(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is IslandState.Idle          -> IdleContent()
            is IslandState.Listening     -> ListeningContent()
            is IslandState.Resolving     -> ResolvingContent(state)
            is IslandState.Thinking      -> ThinkingContent(state)
            is IslandState.Acting        -> ActingContent(state)
            is IslandState.NeedsInput    -> NeedsInputContent(state, onAction)
            is IslandState.Done          -> DoneContent(state)
            is IslandState.Failed        -> FailedContent(state)
            is IslandState.ProxyCall     -> ProxyCallContent(state)
            is IslandState.MorningBrief  -> MorningBriefContent(state)
            is IslandState.LongAnswer    -> LongAnswerContent(state, onDismiss, initialExpanded, scrollState)
        }
    }
}

// ── Idle: Arc-reactor pulsing core ─────────────────────────────────────────
@Composable
private fun IdleContent() {
    Text(
        text = "Raijin",
        color = RaijinWhite,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
    )
}

// ── Listening: Expanding tactical radar sweeps ─────────────────────────────
@Composable
private fun ListeningContent() {
    val inf = rememberInfiniteTransition(label = "listen_radar")
    val scale by inf.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar_scale",
    )
    val ringAlpha by inf.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar_alpha",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(16.dp)) {
            drawCircle(
                color = RaijinBlue.copy(alpha = ringAlpha),
                radius = size.minDimension / 2 * scale,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawCircle(
                color = RaijinBlueLight,
                radius = 3.dp.toPx(),
            )
        }
        Spacer(Modifier.width(8.dp))
        IslandLabel("Listening", RaijinWhite)
    }
}

// ── Resolving: Tactical step traces ────────────────────────────────────────
@Composable
private fun ResolvingContent(s: IslandState.Resolving) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = RaijinGold, radius = 3.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
            IslandLabel(s.label, RaijinWhite)
        }
        Spacer(Modifier.height(6.dp))
        s.traceLines.takeLast(4).forEach { line ->
            Text(
                text  = "> ${line.text}",
                color = if (line.isConclusion) RaijinBlueLight else RaijinTextDim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (line.isConclusion) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

// ── Thinking: Cybernetic telemetry calculation ─────────────────────────────
@Composable
private fun ThinkingContent(s: IslandState.Thinking) {
    val inf = rememberInfiniteTransition(label = "thinking_arc")
    val angle by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "arc_spin",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(14.dp)) {
            drawArc(
                color = RaijinBlue,
                startAngle = angle,
                sweepAngle = 120f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Spacer(Modifier.width(8.dp))
        IslandLabel(s.label, RaijinWhite)
    }
}

// ── Acting: Tactical Actuator In Progress ──────────────────────────────────
@Composable
private fun ActingContent(s: IslandState.Acting) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = RaijinBlue, radius = 3.5.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
            IslandLabel(s.label, RaijinWhite)
        }
        s.subLabel?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                color = RaijinText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            )
        }
    }
}

// ── NeedsInput: Interactive Tactical Confirmation ─────────────────────────
@Composable
private fun NeedsInputContent(
    s: IslandState.NeedsInput,
    onAction: (String, String, String) -> Unit,
) {
    Column {
        // Option-less clarifications are answered by voice/text, so the
        // affirmative control is omitted entirely rather than shown inert.
        IslandLabel(
            text = when {
                s.isDangerous -> "⚠ CONFIRMATION REQUIRED"
                !s.confirmEnabled -> "⚠ ANSWER NEEDED"
                else -> "DIRECTIVE CONFIRMATION"
            },
            color = if (s.isDangerous) RaijinAmber else RaijinBlueLight,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = s.question,
            color = RaijinWhite,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        s.context?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                color = RaijinText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick  = { onAction(s.traceId, s.actionId, "cancel") },
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = RaijinText),
                border   = BorderStroke(1.dp, RaijinBorderDim),
            ) { Text(s.cancelLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }

            if (s.confirmEnabled) {
                Button(
                    onClick  = { onAction(s.traceId, s.actionId, "confirm") },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (s.isDangerous) RaijinRed else RaijinBlue,
                        contentColor = if (s.isDangerous) RaijinWhite else RaijinBlack,
                    ),
                ) { Text(s.confirmLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

// ── Done: Tactical confirmation complete ───────────────────────────────────
@Composable
private fun DoneContent(s: IslandState.Done) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = s.label,
            color = RaijinWhite,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        )
        if (s.label.length > 48) {
            Text("Tap to dismiss", fontSize = 11.sp, color = RaijinText)
        }
    }
}

// ── Failed: Tactical alert ────────────────────────────────────────────────
@Composable
private fun FailedContent(s: IslandState.Failed) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = RaijinRed, radius = 3.5.dp.toPx())
        }
        Spacer(Modifier.width(6.dp))
        IslandLabel(s.reason, RaijinRed)
    }
}

// ── ProxyCall: Live communications transcript ─────────────────────────────
@Composable
private fun ProxyCallContent(s: IslandState.ProxyCall) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(
                    color = if (s.isListening) RaijinGreen else RaijinBlue,
                    radius = 3.5.dp.toPx(),
                )
            }
            Spacer(Modifier.width(6.dp))
            IslandLabel("[COMMS LINK] ${s.contactName}", RaijinWhite)
        }
        if (s.liveTranscript.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.liveTranscript,
                color = RaijinText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
    }
}

// ── MorningBrief: F.R.I.D.A.Y. / E.D.I.T.H. Tactical HUD Briefing ─────────
@Composable
private fun MorningBriefContent(s: IslandState.MorningBrief) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = RaijinGold, radius = 3.5.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
            IslandLabel("Briefing", RaijinGold)
        }
        Spacer(Modifier.height(8.dp))
        s.lines.forEach { line ->
            Text(
                text = "• $line",
                color = RaijinWhite,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
            Spacer(Modifier.height(3.dp))
        }
    }
}

// ── Shared tactical label ─────────────────────────────────────────────────
@Composable
private fun IslandLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
        ),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

// ── UI-5 LongAnswer: expandable, scrollable, tap to dismiss ─────────────
@Composable
internal fun LongAnswerContent(
    s: IslandState.LongAnswer,
    onDismiss: (() -> Unit)?,
    initialExpanded: Boolean = false,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    var expanded by remember(s.text) { mutableStateOf(initialExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Inner clickable consumes the tap before the island-level
                // onTap (which would start listening).
                if (expanded) {
                    if (onDismiss != null) onDismiss() else expanded = false
                } else {
                    expanded = true
                }
            }
            .animateContentSize(),
    ) {
        if (expanded) {
            Text(
                text = s.text,
                color = RaijinWhite,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(scrollState),
            )
            Text(
                text = "tap to dismiss",
                color = RaijinBlueLight.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = s.text,
                color = RaijinWhite,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = "tap to read",
                color = RaijinBlueLight.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
