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

val SCALFY_AID = byteArrayOf(0xF0.toByte(), 0x53, 0x43, 0x41, 0x4C, 0x46, 0x59)
private val OK = byteArrayOf(0x90.toByte(), 0x00)

/** Card side: answers the other phone's SELECT with our session token. */
class ScalfyHceService : HostApduService() {
    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size >= 4 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()) {
            TapController.onTap(null)
            return "SCALFY|${ScalfyState.token}|${ScalfyState.myName.value}".toByteArray() + OK
        }
        if (apdu.size >= 5 && apdu[0] == 0x80.toByte() && apdu[1] == 0x01.toByte()) {
            val n = apdu[4].toInt() and 0xFF
            if (apdu.size >= 5 + n) TapController.setName(String(apdu, 5, n))
            return OK
        }
        return byteArrayOf(0x6A.toByte(), 0x82.toByte())
    }
    override fun onDeactivated(reason: Int) {}
}

/** Reader side. Both phones alternate reader / card roles with random jitter so one tap always pairs them. */
class NfcTapManager(private val activity: Activity, private val onPeer: (Peer) -> Unit) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var readerOn = false

    private val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    private val callback = NfcAdapter.ReaderCallback { tag -> read(tag) }

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
        val delay = (if (readerOn) 260L else 300L) + Random.nextLong(0, 140)
        handler.postDelayed({ cycle() }, delay)
    }

    private fun read(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return
        try {
            iso.connect(); iso.timeout = 1500
            val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, SCALFY_AID.size.toByte()) + SCALFY_AID + 0x00
            val r = iso.transceive(select)
            if (r.size < 2 || r[r.size - 2] != 0x90.toByte()) return
            val p = String(r, 0, r.size - 2).split("|", limit = 3)
            if (p.size == 3 && p[0] == "SCALFY") {
                val name = ScalfyState.myName.value.toByteArray().take(200).toByteArray()
                try { iso.transceive(byteArrayOf(0x80.toByte(), 0x01, 0x00, 0x00, name.size.toByte()) + name) } catch (_: Exception) {}
                handler.post { onPeer(Peer(p[1], p[2])) }
            }
        } catch (_: Exception) {
        } finally { try { iso.close() } catch (_: Exception) {} }
    }
}
