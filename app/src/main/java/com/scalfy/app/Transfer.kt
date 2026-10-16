package com.scalfy.app

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject

/** NFC tap gives a one-time token; Nearby Connections then moves the data (Wi-Fi/Bluetooth). */
class TransferManager(private val ctx: Context) {
    private val client by lazy { Nearby.getConnectionsClient(ctx) }
    private val handler = Handler(Looper.getMainLooper())
    private val SERVICE = "com.scalfy.app.share"

    @Volatile var connecting = false
    private var connected = false
    private var sentAll = false
    private var peerDone = false
    private var finished = false
    private var expectedIn = 0
    private var doneIn = 0
    private val metas = HashMap<Long, JSONObject>()
    private val incoming = HashMap<Long, Payload>()
    private val outgoing = HashSet<Long>()

    fun startAdvertising() {
        client.startAdvertising(
            ScalfyState.token, SERVICE, connCb,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        ).addOnFailureListener { }
    }

    fun connectTo(peerToken: String) {
        if (connecting) return
        connecting = true
        client.stopAdvertising()
        client.startDiscovery(SERVICE, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                if (info.endpointName == peerToken) {
                    client.stopDiscovery()
                    client.requestConnection(ScalfyState.myName.value, id, connCb)
                        .addOnFailureListener { fail() }
                }
            }
            override fun onEndpointLost(id: String) {}
        }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
            .addOnFailureListener { fail() }
        handler.postDelayed({ if (connecting && !connected) fail() }, 8000)
    }

    fun reset() {
        handler.removeCallbacksAndMessages(null)
        try { client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery() } catch (_: Exception) {}
        metas.clear(); incoming.clear(); outgoing.clear(); ScalfyState.sessionReceived.clear()
        sentAll = false; peerDone = false; finished = false; expectedIn = 0; doneIn = 0
        connecting = false; connected = false
        ScalfyState.token = ScalfyState.newToken()
        startAdvertising()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try { client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery() } catch (_: Exception) {}
    }

    private fun fail() = TapController.finish("Couldn't connect")

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            // Only accept right after a physical NFC tap.
            if (!ScalfyState.tappedRecently()) { client.rejectConnection(id); return }
            if (ScalfyState.peer.value == null) TapController.setName(info.endpointName)
            client.acceptConnection(id, payloadCb)
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                connected = true
                client.stopAdvertising(); client.stopDiscovery()
                sendAll(id)
            } else fail()
        }
        override fun onDisconnected(id: String) {}
    }

    private fun sendJson(id: String, j: JSONObject) =
        client.sendPayload(id, Payload.fromBytes(j.toString().toByteArray()))

    private fun sendAll(id: String) {
        val items = ScalfyState.outbox.value
        if (items.isNotEmpty()) ScalfyState.status.value = "Sharing ${items.size} item${if (items.size > 1) "s" else ""}…"
        var files = 0
        for (item in items) {
            try {
                when (item.kind) {
                    Kind.TEXT, Kind.CONTACT -> sendJson(id, JSONObject()
                        .put("t", if (item.kind == Kind.TEXT) "text" else "contact")
                        .put("title", item.title).put("body", (item.text ?: "").take(30000)))
                    else -> {
                        val pfd = ctx.contentResolver.openFileDescriptor(item.uri!!, "r") ?: continue
                        val p = Payload.fromFile(pfd)
                        outgoing.add(p.id); files++
                        sendJson(id, JSONObject().put("t", "file").put("pid", p.id)
                            .put("name", item.title).put("mime", item.mime ?: "application/octet-stream"))
                        client.sendPayload(id, p)
                    }
                }
            } catch (_: Exception) {}
        }
        sendJson(id, JSONObject().put("t", "done").put("files", files))
        sentAll = true
        maybeFinish()
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            when (p.type) {
                Payload.Type.BYTES -> p.asBytes()?.let { handleBytes(String(it)) }
                Payload.Type.FILE -> incoming[p.id] = p
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            val pid = u.payloadId
            when (u.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (!outgoing.remove(pid)) incoming.remove(pid)?.let { save(it); doneIn++ }
                    maybeFinish()
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    if (!outgoing.remove(pid) && incoming.remove(pid) != null) doneIn++
                    maybeFinish()
                }
            }
        }
    }

    private fun handleBytes(s: String) {
        val j = try { JSONObject(s) } catch (_: Exception) { return }
        when (j.optString("t")) {
            "file" -> metas[j.optLong("pid")] = j
            "text" -> addReceived(ShareItem(Kind.TEXT, j.optString("title"), text = j.optString("body")))
            "contact" -> addReceived(ShareItem(Kind.CONTACT, j.optString("title"), text = j.optString("body")))
            "done" -> { peerDone = true; expectedIn = j.optInt("files"); maybeFinish() }
        }
    }

    private fun addReceived(i: ShareItem) {
        ScalfyState.received.value = listOf(i) + ScalfyState.received.value
        ScalfyState.sessionReceived.add(i)
    }

    private fun maybeFinish() {
        if (finished) return
        if (sentAll && peerDone && outgoing.isEmpty() && doneIn >= expectedIn) {
            finished = true
            ScalfyState.outbox.value = emptyList()
            TapController.finish("Done")
        }
    }

    private fun save(p: Payload) {
        try {
            val meta = metas[p.id]
            val name = meta?.optString("name")?.ifBlank { null } ?: "scalfy_${p.id}"
            val mime = meta?.optString("mime")?.ifBlank { null } ?: "application/octet-stream"
            val pfd: ParcelFileDescriptor = p.asFile()?.asParcelFileDescriptor() ?: return
            val (collection, dir) = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Scalfy"
                mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Scalfy"
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Scalfy"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            }
            val uri = ctx.contentResolver.insert(collection, values) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.copyTo(out) }
            }
            val kind = if (mime.startsWith("image/") || mime.startsWith("video/")) Kind.PHOTO else Kind.FILE
            addReceived(ShareItem(kind, name, uri = uri, mime = mime))
        } catch (_: Exception) {}
    }
}
