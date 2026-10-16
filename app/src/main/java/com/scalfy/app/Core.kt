package com.scalfy.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

enum class Kind { PHOTO, FILE, CONTACT, TEXT }
enum class Phase { IDLE, BLOOM, CONNECTED, DISMISS }

data class ShareItem(
    val kind: Kind,
    val title: String,
    val uri: Uri? = null,
    val text: String? = null,
    val mime: String? = null,
    val id: Long = System.nanoTime()
)

data class Peer(val token: String, val name: String)

object ScalfyState {
    val outbox = MutableStateFlow<List<ShareItem>>(emptyList())
    val received = MutableStateFlow<List<ShareItem>>(emptyList())
    val phase = MutableStateFlow(Phase.IDLE)
    val peer = MutableStateFlow<Peer?>(null)
    val status = MutableStateFlow("Connected")
    val myName = MutableStateFlow(Build.MODEL ?: "My phone")

    @Volatile var token: String = newToken()
    @Volatile private var tappedAt = 0L

    fun newToken() = (1..8).map { "0123456789abcdef".random() }.joinToString("")
    fun markTapped() { tappedAt = SystemClock.elapsedRealtime() }
    fun tappedRecently() = tappedAt != 0L && SystemClock.elapsedRealtime() - tappedAt < 20_000

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("scalfy", Context.MODE_PRIVATE)
        myName.value = p.getString("name", null) ?: (Build.MODEL ?: "My phone")
    }
    fun saveName(ctx: Context, name: String) {
        if (name.isBlank()) return
        myName.value = name.trim()
        ctx.getSharedPreferences("scalfy", Context.MODE_PRIVATE).edit().putString("name", name.trim()).apply()
    }
}

/** Drives the tap timeline: bloom -> connected pill -> dismiss. */
object TapController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    lateinit var app: Context
    lateinit var transfer: TransferManager
    @Volatile var busy = false

    fun onTap(peer: Peer?) = main.post {
        val s = ScalfyState
        s.markTapped()
        if (peer != null) s.peer.value = peer
        if (s.phase.value != Phase.IDLE) return@post
        busy = true
        s.status.value = "Connected"
        s.phase.value = Phase.BLOOM
        Haptics.tap()
        scope.launch {
            delay(1100)
            s.phase.value = Phase.CONNECTED
            Haptics.connected()
            delay(2600)
            withTimeoutOrNull(20_000) { while (busy) delay(150) }
            s.phase.value = Phase.DISMISS
            delay(700)
            s.phase.value = Phase.IDLE
            s.peer.value = null
            busy = false
            transfer.reset()
        }
    }

    fun setName(name: String) = main.post { ScalfyState.peer.value = Peer("", name) }
    fun finish(label: String) = main.post {
        ScalfyState.status.value = label; busy = false
        if (label == "Done") Haptics.success() else Haptics.error()
    }
}

/** Haptic cues for each stage of a share. */
object Haptics {
    private fun v(): Vibrator? = TapController.app.getSystemService(Vibrator::class.java)
    private fun wave(t: LongArray, a: IntArray) = v()?.vibrate(VibrationEffect.createWaveform(t, a, -1))

    /** Phones touched: soft double bump as the glow blooms. */
    fun tap() = wave(longArrayOf(0, 18, 60, 28), intArrayOf(0, 140, 0, 220))
    /** Pill springs out: crisp click. */
    fun connected() = v()?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    /** Transfer finished: rising double tick. */
    fun success() = wave(longArrayOf(0, 25, 70, 45), intArrayOf(0, 120, 0, 255))
    /** Failed / no connection: long low buzz. */
    fun error() = wave(longArrayOf(0, 60, 50, 60, 50, 120), intArrayOf(0, 200, 0, 200, 0, 255))
}
