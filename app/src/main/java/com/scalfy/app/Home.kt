package com.scalfy.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Accent = Color(0xFFFF3B5C)
private val Bg = Color(0xFF0A0A0C)
private val Card = Color(0xFF16161A)

@Composable
fun ScalfyTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card, onSurface = Color.White, onBackground = Color.White),
    content = content
)

@Composable
fun Home(
    nfcOk: Boolean, onNfcSettings: () -> Unit,
    onPhotos: () -> Unit, onFiles: () -> Unit, onContact: () -> Unit, onText: () -> Unit,
    onEditName: () -> Unit, onOpen: (ShareItem) -> Unit,
    onToggleBg: (Boolean) -> Unit, onAddTile: () -> Unit, onIphone: (Boolean) -> Unit
) {
    val out by ScalfyState.outbox.collectAsState()
    val inbox by ScalfyState.received.collectAsState()
    val name by ScalfyState.myName.collectAsState()
    val view = LocalView.current
    val bgOn by ScalfyState.serviceOn.collectAsState()
    val iurl by ScalfyState.iphoneUrl.collectAsState()
    fun tick(f: () -> Unit): () -> Unit = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); f() }

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text("Scalfy", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Sharing as $name  ✎", color = Color(0xFF8A8A8A), modifier = Modifier.clickable { onEditName() }.padding(4.dp))
        }
        item { Hero() }
        item {
            Text(if (nfcOk) "Hold phones back to back to share" else "NFC is off", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (!nfcOk) Button(onClick = onNfcSettings, modifier = Modifier.padding(top = 8.dp)) { Text("Turn on NFC") }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tap to share anywhere", color = Color.White, fontWeight = FontWeight.Medium)
                        Text("Runs in the background, no need to open the app", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                    }
                    Switch(bgOn, { onToggleBg(it) })
                }
                TextButton(onAddTile) { Text("Add Quick Settings tile", color = Accent) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)) {
                Text("Send to iPhone (no app needed)", color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    if (iurl == null) "Pick items above, tap Start, then hold the iPhone to the back of this phone and tap the banner on the iPhone. Both phones must share Wi-Fi, or the iPhone joins this phone's hotspot."
                    else "Ready. Hold the iPhone to the back of this phone, then tap the banner on the iPhone.\n$iurl",
                    color = Color(0xFF8A8A8A), fontSize = 12.sp
                )
                Button({ onIphone(iurl == null) }, Modifier.padding(top = 8.dp)) { Text(if (iurl == null) "Start" else "Stop") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, act) in listOf("Photos" to tick(onPhotos), "Files" to tick(onFiles), "Contact" to tick(onContact), "Text" to tick(onText)))
                    FilledTonalButton(act, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(label, fontSize = 13.sp) }
            }
        }
        if (out.isNotEmpty()) {
            item { Section("Ready to share") }
            items(out, key = { it.id }) { ItemRow(it, "Remove") { ScalfyState.outbox.value = ScalfyState.outbox.value - it } }
        }
        if (inbox.isNotEmpty()) {
            item { Section("Received") }
            items(inbox, key = { it.id }) { ItemRow(it, null) { onOpen(it) } }
        }
    }
}

@Composable private fun Section(t: String) =
    Text(t, color = Color(0xFF8A8A8A), fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

@Composable
private fun ItemRow(item: ShareItem, action: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
            .clickable(enabled = action == null) { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(item.kind.name.take(1), 36.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title.ifBlank { item.kind.name.lowercase() }, color = Color.White, maxLines = 1)
            Text(item.kind.name.lowercase().replaceFirstChar { it.uppercase() }, color = Color(0xFF8A8A8A), fontSize = 12.sp)
        }
        if (action != null) TextButton(onClick) { Text(action, color = Accent) }
    }
}

@Composable
private fun Hero() {
    val t by rememberInfiniteTransition(label = "hero").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    Canvas(Modifier.size(220.dp)) {
        val c = center
        for (i in 0 until 3) {
            val f = (t + i / 3f) % 1f
            drawCircle(Accent.copy(alpha = (1f - f) * 0.5f), radius = size.minDimension / 2 * (0.35f + 0.65f * f), center = c, style = Stroke(3.dp.toPx()))
        }
        val w = 56.dp.toPx(); val h = 96.dp.toPx()
        drawRoundRect(Color(0xFFEDEDED), Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(14.dp.toPx()))
        drawRoundRect(Color(0xFF111114), Offset(c.x - w / 2 + 3.dp.toPx(), c.y - h / 2 + 3.dp.toPx()), Size(w - 6.dp.toPx(), h - 6.dp.toPx()), CornerRadius(11.dp.toPx()))
        drawRoundRect(Color.Black, Offset(c.x - 9.dp.toPx(), c.y - h / 2 + 9.dp.toPx()), Size(18.dp.toPx(), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
    }
}
