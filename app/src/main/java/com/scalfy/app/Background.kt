package com.scalfy.app

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.graphics.drawable.Icon
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.ServiceCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*

/** Keeps Scalfy alive so a tap works with the app closed; owns the glow overlay. */
class ShareService : Service() {
    companion object { const val ACTION_STOP = "com.scalfy.app.STOP"; const val CH = "scalfy_bg"; const val ID = 7 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayHost? = null
    private var job: Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Tap to share", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Scalfy is ready")
            .setContentText("Tap another phone to share")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), "Turn off", stop).build())
            .build()
        try {
            ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }

        ScalfyState.serviceOn.value = true
        if (overlay == null) overlay = OverlayHost(this)
        if (ScalfyState.phase.value == Phase.IDLE) TapController.transfer.reset()
        job?.cancel()
        job = scope.launch {
            ScalfyState.phase.collect { p -> if (p != Phase.IDLE) overlay?.show() else overlay?.hide() }
        }
        refreshTile(this)
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); overlay?.hide()
        TapController.transfer.stop()
        ScalfyState.serviceOn.value = false
        refreshTile(this)
        super.onDestroy()
    }
}

fun refreshTile(ctx: Context) =
    TileService.requestListeningState(ctx, ComponentName(ctx, ScalfyTileService::class.java))

/** Quick Settings tile: one tap turns background tap-to-share on/off. */
class ScalfyTileService : TileService() {
    override fun onStartListening() { setState(ScalfyState.serviceOn.value) }

    override fun onClick() {
        if (ScalfyState.serviceOn.value) {
            try { startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP)); setState(false) }
            catch (_: Exception) { openSetup() }
            return
        }
        if (!Setup.ready(this)) { openSetup(); return }
        try { startForegroundService(Intent(this, ShareService::class.java)); setState(true) }
        catch (_: Exception) { openSetup() }
    }

    private fun setState(on: Boolean) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Scalfy"
            subtitle = if (on) "Ready" else "Off"
            updateTile()
        }
    }

    private fun openSetup() {
        val i = Intent(this, MainActivity::class.java).putExtra("setup", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}

/** Invisible activity the system launches when another Scalfy phone is tapped and Scalfy isn't open. */
class TapReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") val tag = intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag == null || !ScalfyState.serviceOn.value) { finish(); return }
        Thread {
            NfcReader.read(tag)?.let { TapActions.peerRead(it) }
            runOnUiThread { finish() }
        }.start()
    }
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lr = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lr
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    init {
        ssc.performRestore(null)
        lr.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lr.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lr.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun destroy() {
        lr.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lr.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lr.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

/** Draws the glow + pill over whatever app is on screen (needs "Display over other apps"). */
class OverlayHost(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var lp: WindowManager.LayoutParams? = null
    private var lastBlur = -1

    fun show() {
        if (view != null || ScalfyState.appVisible || !Settings.canDrawOverlays(ctx)) return
        val o = OverlayOwner(); owner = o
        val res = ctx.resources
        val sid = res.getIdentifier("status_bar_height", "dimen", "android")
        val topDp = ((if (sid > 0) res.getDimensionPixelSize(sid) else 0) / res.displayMetrics.density).dp
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Android 12+ blocks touches through overlays more opaque than this, even with FLAG_NOT_TOUCHABLE.
            alpha = if (Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 0.8f
        }
        lp = params
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { ScalfyTheme { TapExperience(blurContent = false, topInset = topDp, onBloom = { blurBehind(it) }) {} } }
        }
        view = v
        try { wm.addView(v, params) } catch (_: Exception) { view = null; owner?.destroy(); owner = null }
    }

    private fun blurBehind(b: Float) {
        val p = lp ?: return; val v = view ?: return
        if (Build.VERSION.SDK_INT < 31 || !wm.isCrossWindowBlurEnabled) return
        val r = (b * 40).toInt()
        if (r == lastBlur) return
        lastBlur = r
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        p.blurBehindRadius = r
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null; lp = null; lastBlur = -1
        owner?.destroy(); owner = null
    }
}

class ScalfyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScalfyState.load(this)
        TapController.app = this
        TapController.transfer = TransferManager(this)
    }
}
