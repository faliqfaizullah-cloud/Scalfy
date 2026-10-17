package com.scalfy.app

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

const val SCALFY_MIME = "application/vnd.scalfy.tap"
val SCALFY_AID = byteArrayOf(0xF0.toByte(), 0x53, 0x43, 0x41, 0x4C, 0x46, 0x59)
private val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
private val OK = byteArrayOf(0x90.toByte(), 0x00)
private val NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
private val CC = byteArrayOf(0x00, 0x0F, 0x20, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
    0x04, 0x06, 0xE1.toByte(), 0x04, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte())

/**
 * Card side (works with the app closed). It speaks two languages:
 *  - Scalfy's own AID, used when Scalfy is open on the reading phone.
 *  - A standard NFC Forum Type 4 tag carrying one private MIME record, so Android opens Scalfy
 *    directly on the reading phone (no "Choose app" list, no other app involved).
 */
class ScalfyHceService : HostApduService() {
    private var file = 0 // 1 = capability container, 2 = NDEF file

    private fun session() = "SCALFY|${ScalfyState.token}|${ScalfyState.myName.value}".toByteArray().take(190).toByteArray()

    private fun ndefFile(): ByteArray {
        ScalfyState.iphoneUrl.value?.let { url ->
            // iPhone mode: a plain web-link record that iOS reads natively (prefix code 0x03 = "http://").
            val rest = url.removePrefix("http://").toByteArray()
            val payload = byteArrayOf(0x03) + rest
            val rec = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 0x55) + payload
            return byteArrayOf((rec.size shr 8).toByte(), rec.size.toByte()) + rec
        }
        val type = SCALFY_MIME.toByteArray(); val body = session()
        val rec = byteArrayOf(0xD2.toByte(), type.size.toByte(), body.size.toByte()) + type + body
        return byteArrayOf((rec.size shr 8).toByte(), rec.size.toByte()) + rec
    }

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return NOT_FOUND
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        if (cla == 0x00 && ins == 0xA4 && p1 == 0x04 && apdu.size >= 5) {
            val lc = apdu[4].toInt() and 0xFF
            val aid = apdu.copyOfRange(5, minOf(5 + lc, apdu.size))
            return when {
                aid.contentEquals(SCALFY_AID) -> { TapController.onTap(null); session() + OK }
                aid.contentEquals(NDEF_AID) -> { TapController.onTap(null); file = 0; OK }
                else -> NOT_FOUND
            }
        }
        if (cla == 0x00 && ins == 0xA4 && p1 == 0x00 && apdu.size >= 7) {
            val id = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
            file = when (id) { 0xE103 -> 1; 0xE104 -> 2; else -> 0 }
            return if (file != 0) OK else NOT_FOUND
        }
        if (cla == 0x00 && ins == 0xB0) {
            val off = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            val le = if (apdu.size > 4) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
            val data = when (file) { 1 -> CC; 2 -> ndefFile(); else -> return NOT_FOUND }
            if (off > data.size) return byteArrayOf(0x6B, 0x00)
            return data.copyOfRange(off, minOf(data.size, off + le)) + OK
        }
        if (cla == 0x80 && ins == 0x01 && apdu.size >= 5) {
            val n = apdu[4].toInt() and 0xFF
            if (apdu.size >= 5 + n) TapController.setName(String(apdu, 5, n))
            return OK
        }
        return NOT_FOUND
    }
    override fun onDeactivated(reason: Int) { file = 0 }
}

/** Shared tag reader (used by the foreground reader mode and the background tag-dispatch activity). */
object NfcReader {
    fun read(tag: Tag): Peer? {
        val iso = IsoDep.get(tag) ?: return null
        try {
            iso.connect(); iso.timeout = 1500
            val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, SCALFY_AID.size.toByte()) + SCALFY_AID + 0x00
            val r = iso.transceive(select)
            if (r.size < 2 || r[r.size - 2] != 0x90.toByte()) return null
            val p = String(r, 0, r.size - 2).split("|", limit = 3)
            if (p.size == 3 && p[0] == "SCALFY") {
                val name = ScalfyState.myName.value.toByteArray().take(200).toByteArray()
                try { iso.transceive(byteArrayOf(0x80.toByte(), 0x01, 0x00, 0x00, name.size.toByte()) + name) } catch (_: Exception) {}
                return Peer(p[1], p[2])
            }
        } catch (_: Exception) {
        } finally { try { iso.close() } catch (_: Exception) {} }
        return null
    }
}

object TapActions {
    private val main = Handler(Looper.getMainLooper())
    fun peerRead(peer: Peer) = main.post {
        val first = ScalfyState.phase.value == Phase.IDLE
        TapController.onTap(peer)
        if (first || ScalfyState.token < peer.token) TapController.transfer.connectTo(peer.token)
    }
}

/** Foreground reader. Both phones alternate reader / card roles with random jitter so one tap always pairs them. */
class NfcTapManager(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var readerOn = false

    private val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    private val callback = NfcAdapter.ReaderCallback { tag -> NfcReader.read(tag)?.let { TapActions.peerRead(it) } }

    fun start() { if (adapter == null || running) return; running = true; readerOn = false; cycle() }
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { adapter?.disableReaderMode(activity) } catch (_: Exception) {}
    }

    private fun cycle() {
        if (!running) return
        try {
            if (readerOn) adapter?.disableReaderMode(activity)
            else adapter?.enableReaderMode(activity, callback, flags, null)
        } catch (_: Exception) {}
        readerOn = !readerOn
        handler.postDelayed({ cycle() }, (if (readerOn) 260L else 300L) + Random.nextLong(0, 140))
    }
}
