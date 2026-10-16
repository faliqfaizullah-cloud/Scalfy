package com.scalfy.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

private val Glow = Color(0xFFFF4D6D)
private val GlowSoft = Color(0xFFFF8FA6)
private val RimRed = Color(0xFFE8263E)

private fun DrawScope.edgeGlow(color: Color, strength: Float, corner: Float) {
    for (i in 0 until 9) {
        val w = (3 + i * 7).dp.toPx()
        drawRoundRect(color.copy(alpha = (strength * 0.11f).coerceIn(0f, 1f)),
            cornerRadius = CornerRadius(corner), style = Stroke(w))
    }
}

/**
 * Wraps the app. Timeline recreated from the reference video:
 *  1. BLOOM     – content blurs, pink/white light blooms from the touching (top) edge and washes the screen
 *  2. CONNECTED – bloom relaxes, black island-style pill springs out of the cutout with avatar / name / "Connected",
 *                 a red rim pulses around the screen edge
 *  3. DISMISS   – pill retracts into the cutout, rim fades
 */
@Composable
fun TapExperience(content: @Composable () -> Unit) {
    val phase by ScalfyState.phase.collectAsState()
    val peer by ScalfyState.peer.collectAsState()
    val status by ScalfyState.status.collectAsState()

    val bloom = remember { Animatable(0f) }
    val rim = remember { Animatable(0f) }
    val pill = remember { Animatable(0f) }

    LaunchedEffect(phase) {
        when (phase) {
            Phase.BLOOM -> { pill.snapTo(0f); bloom.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
            Phase.CONNECTED -> coroutineScope {
                launch { bloom.animateTo(0f, tween(650, easing = FastOutSlowInEasing)) }
                launch { rim.animateTo(1f, tween(700)) }
                launch { pill.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 240f)) }
            }
            Phase.DISMISS -> coroutineScope {
                launch { pill.animateTo(0f, tween(420, easing = FastOutSlowInEasing)) }
                launch { rim.animateTo(0f, tween(560)) }
            }
            Phase.IDLE -> { bloom.snapTo(0f); rim.snapTo(0f); pill.snapTo(0f) }
        }
    }

    val pulse by rememberInfiniteTransition(label = "rim").animateFloat(
        0.7f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "p"
    )

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().blur((bloom.value * 24f).dp)) { content() }

        Canvas(Modifier.fillMaxSize()) {
            val corner = 48.dp.toPx()
            val b = bloom.value
            if (b > 0.001f) {
                drawRect(Glow.copy(alpha = 0.30f * b))
                drawRect(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.95f * b),
                    0.18f to GlowSoft.copy(alpha = 0.85f * b),
                    0.55f to RimRed.copy(alpha = 0.30f * b),
                    1f to Color.Transparent,
                    endY = size.height * 0.75f))
                drawRect(Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.9f * b), Color.Transparent),
                    center = Offset(size.width / 2, 0f), radius = size.width * 0.9f))
                edgeGlow(GlowSoft, b * 1.6f, corner)
            }
            val r = rim.value
            if (r > 0.001f) {
                edgeGlow(RimRed, r * pulse, corner)
                drawRoundRect(RimRed.copy(alpha = 0.85f * r), cornerRadius = CornerRadius(corner), style = Stroke(6.dp.toPx()))
            }
        }

        val p = pill.value
        if (p > 0.001f) {
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val collapsedTop = ((statusTop - 34.dp) / 2).coerceAtLeast(6.dp)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = lerp(118.dp, maxWidth - 24.dp, p).coerceAtLeast(60.dp)
                val h = lerp(34.dp, 76.dp, p).coerceAtLeast(20.dp)
                val radius = lerp(17.dp, 38.dp, p).coerceAtLeast(10.dp)
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .offset(y = lerp(collapsedTop, collapsedTop + 4.dp, p))
                        .width(w).height(h)
                        .clip(RoundedCornerShape(radius)).background(Color.Black)
                ) {
                    val a = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp).alpha(a),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(peer?.name ?: "Nearby", 46.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(peer?.name ?: "Nearby phone", color = Color.White, fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(status, color = Color(0xFF737373), fontSize = 14.sp, maxLines = 1)
                        }
                        Box(Modifier.size(28.dp).border(1.5.dp, Color(0xFF8A8A8A), CircleShape), contentAlignment = Alignment.Center) {
                            Text("i", color = Color(0xFF8A8A8A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: Dp) {
    val hue = (name.hashCode().toUInt() % 360u).toFloat()
    val c1 = Color.hsv(hue, 0.55f, 0.95f)
    val c2 = Color.hsv((hue + 40f) % 360f, 0.7f, 0.75f)
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2)))
            .border(2.dp, Color(0xFFFFC107).copy(alpha = 0.0f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "S", color = Color.White,
            fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}
