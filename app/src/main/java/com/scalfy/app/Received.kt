package com.scalfy.app

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AccentR = Color(0xFFFF3B5C)
private val Gray = Color(0xFF8A8A8A)

fun vcardField(v: String, key: String) =
    v.lineSequence().firstOrNull { it.startsWith(key, true) }?.substringAfter(':')?.trim()

/** Opens / applies a received item: view a photo or file, save a contact, copy text. */
fun openShareItem(ctx: Context, i: ShareItem) {
    try {
        when (i.kind) {
            Kind.PHOTO, Kind.FILE -> ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(i.uri, i.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            Kind.CONTACT -> ctx.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ContactsContract.Intents.Insert.NAME, i.title)
                vcardField(i.text ?: "", "TEL")?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                vcardField(i.text ?: "", "EMAIL")?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            })
            Kind.TEXT -> {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Scalfy", i.text ?: ""))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (_: Exception) { Toast.makeText(ctx, "No app can open this", Toast.LENGTH_SHORT).show() }
}

/** Shown automatically the moment a transfer completes. */
class ReceivedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(8)
        setContent {
            ScalfyTheme {
                val got by ScalfyState.lastReceived.collectAsState()
                val from by ScalfyState.lastFrom.collectAsState()
                ReceivedScreen(got, from) { ScalfyState.lastReceived.value = emptyList(); finish() }
            }
        }
    }
}

@Composable
fun ReceivedScreen(all: List<ShareItem>, from: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f)) }
    val photos = all.filter { it.kind == Kind.PHOTO }
    val others = all.filter { it.kind != Kind.PHOTO }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * t.value.coerceIn(0f, 1f)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() }
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                .offset { IntOffset(0, ((1f - t.value) * 420.dp.toPx()).toInt()) }
                .clip(RoundedCornerShape(32.dp)).background(Color(0xFF16161A))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()).padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(from, 44.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Received from $from", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("${all.size} item${if (all.size > 1) "s" else ""} saved", color = Gray, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))

            if (photos.size == 1) {
                Thumb(photos[0], 1080, fit = true,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { openShareItem(ctx, photos[0]) })
            } else if (photos.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos, key = { it.id }) { p ->
                        Thumb(p, 400, fit = false,
                            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)).clickable { openShareItem(ctx, p) })
                    }
                }
            }

            for (o in others) {
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF202026)).padding(14.dp)) {
                    when (o.kind) {
                        Kind.TEXT -> {
                            Text(o.text ?: "", color = Color.White, maxLines = 8)
                            TextButton({ openShareItem(ctx, o) }) { Text("Copy", color = AccentR) }
                        }
                        Kind.CONTACT -> {
                            Text(o.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            vcardField(o.text ?: "", "TEL")?.let { Text(it, color = Gray, fontSize = 14.sp) }
                            vcardField(o.text ?: "", "EMAIL")?.let { Text(it, color = Gray, fontSize = 14.sp) }
                            TextButton({ openShareItem(ctx, o) }) { Text("Save contact", color = AccentR) }
                        }
                        else -> {
                            Text(o.title, color = Color.White, maxLines = 1)
                            TextButton({ openShareItem(ctx, o) }) { Text("Open", color = AccentR) }
                        }
                    }
                }
            }
            Button(onDone, Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("Done") }
        }
    }
}

@Composable
private fun Thumb(item: ShareItem, px: Int, fit: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            try { ctx.contentResolver.loadThumbnail(item.uri!!, Size(px, px), null).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    Box(modifier.background(Color(0xFF222226)), contentAlignment = Alignment.Center) {
        bmp?.let {
            if (fit) Image(it, null, Modifier.fillMaxWidth().heightIn(max = 380.dp), contentScale = ContentScale.Fit)
            else Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
