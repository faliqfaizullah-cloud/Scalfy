package com.scalfy.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android -> iPhone with no app on the iPhone.
 * The tap hands the iPhone an NFC "web link" (iPhones read these natively); the link opens a small
 * page served by this phone over Wi-Fi / hotspot where the photos, files, contacts and text appear.
 */
object IphoneShare {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var token = ""
    private var items: List<ShareItem> = emptyList()
    private val fetched = HashSet<Long>()
    private var indexed = false
    private var reported = false
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    /** Returns an error message, or null when sharing started. */
    fun start(ctx: Context): String? {
        stop()
        val list = ScalfyState.outbox.value
        if (list.isEmpty()) return "Add something to share first"
        val ip = localIp() ?: return "Connect to Wi-Fi, or turn on your hotspot, first"
        val ss = try { ServerSocket(0, 20) } catch (_: Exception) { return "Couldn't start sharing" }
        server = ss; items = list; fetched.clear(); indexed = false; reported = false
        token = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val p = Executors.newCachedThreadPool(); pool = p
        val app = ctx.applicationContext
        Thread {
            while (!ss.isClosed) {
                try { val c = ss.accept(); p.execute { handle(app, c) } } catch (_: Exception) { break }
            }
        }.start()
        ScalfyState.iphoneUrl.value = "http://$ip:${ss.localPort}/$token"
        val t = Runnable { stop() }; timeout = t
        main.postDelayed(t, 5 * 60_000L)
        return null
    }

    fun stop() {
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        try { server?.close() } catch (_: Exception) {}
        pool?.shutdownNow()
        server = null; pool = null
        ScalfyState.iphoneUrl.value = null
    }

    private fun localIp(): String? {
        val all = try { NetworkInterface.getNetworkInterfaces().toList() } catch (_: Exception) { return null }
        for (ni in all) {
            val n = ni.name.lowercase()
            if (!ni.isUp || ni.isLoopback) continue
            if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth"))) continue
            for (a in ni.inetAddresses.toList())
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) return a.hostAddress
        }
        return null
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun head(out: OutputStream, code: Int, type: String, len: Long = -1, extra: String = "") {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val h = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\n" +
            (if (len >= 0) "Content-Length: $len\r\n" else "") + extra + "Connection: close\r\n\r\n"
        out.write(h.toByteArray())
    }

    private fun handle(ctx: Context, sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 15000
                val r = s.getInputStream().bufferedReader()
                val line = r.readLine() ?: return
                while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
                val parts = line.split(" ")
                val out = s.getOutputStream()
                if (parts.size < 2 || parts[0] != "GET") { head(out, 404, "text/plain", 0); return }
                val full = parts[1]
                val seg = full.substringBefore('?').trim('/').split('/')
                if (seg.firstOrNull() != token) { head(out, 404, "text/plain", 0); return }
                when {
                    seg.size == 1 -> {
                        val body = page().toByteArray()
                        head(out, 200, "text/html; charset=utf-8", body.size.toLong()); out.write(body)
                        indexed = true
                        main.post { ScalfyState.status.value = "Sending…" }
                        check()
                    }
                    seg.size == 3 && seg[1] == "f" -> serveFile(ctx, out, seg[2].toLongOrNull(), full.contains("dl=1"))
                    seg.size == 3 && seg[1] == "c" -> {
                        val it = items.firstOrNull { x -> x.id == seg[2].toLongOrNull() }
                        val body = (it?.text ?: "").toByteArray()
                        head(out, 200, "text/x-vcard; charset=utf-8", body.size.toLong(),
                            "Content-Disposition: attachment; filename=\"contact.vcf\"\r\n")
                        out.write(body)
                        it?.let { x -> fetched.add(x.id) }; check()
                    }
                    else -> head(out, 404, "text/plain", 0)
                }
                out.flush()
            } catch (_: Exception) {}
        }
    }

    private fun serveFile(ctx: Context, out: OutputStream, id: Long?, download: Boolean) {
        val it = items.firstOrNull { x -> x.id == id && x.uri != null }
        if (it == null) { head(out, 404, "text/plain", 0); return }
        val len = try { ctx.contentResolver.openAssetFileDescriptor(it.uri!!, "r")?.use { d -> d.length } ?: -1L } catch (_: Exception) { -1L }
        val safe = it.title.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val disp = if (download || it.kind == Kind.FILE) "attachment" else "inline"
        head(out, 200, it.mime ?: "application/octet-stream", len, "Content-Disposition: $disp; filename=\"$safe\"\r\n")
        ctx.contentResolver.openInputStream(it.uri!!)?.use { ins -> ins.copyTo(out) }
        fetched.add(it.id); check()
    }

    /** Done once the page was opened and every photo / file / contact was fetched. */
    private fun check() {
        if (reported || !indexed) return
        if (items.filter { it.kind != Kind.TEXT }.all { it.id in fetched }) {
            reported = true
            TapController.finish("Sent")
        }
    }

    private fun page(): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Scalfy</title>")
        sb.append("<style>body{background:#0a0a0c;color:#fff;font-family:-apple-system,sans-serif;margin:0;padding:20px}h1{font-size:22px;margin:0 0 4px}")
        sb.append(".c{background:#16161a;border-radius:18px;padding:14px;margin:12px 0}img,video{width:100%;border-radius:12px}")
        sb.append("a.b,button{display:inline-block;background:#ff3b5c;color:#fff;border:0;border-radius:12px;padding:10px 16px;margin-top:10px;font-size:16px;text-decoration:none}")
        sb.append("pre{white-space:pre-wrap;word-break:break-word;font:16px -apple-system,sans-serif;margin:0}.g{color:#8a8a8a;font-size:13px}</style></head><body>")
        sb.append("<h1>From ${esc(ScalfyState.myName.value)}</h1><div class=g>Shared with Scalfy &middot; touch and hold a photo to save it</div>")
        for (i in items) {
            val f = "/$token/f/${i.id}"
            sb.append("<div class=c>")
            when {
                i.kind == Kind.TEXT -> sb.append("<pre>${esc(i.text ?: "")}</pre><button onclick=\"c(this)\">Copy</button>")
                i.kind == Kind.CONTACT -> sb.append("<b>${esc(i.title)}</b><br><a class=b href=\"/$token/c/${i.id}\">Add contact</a>")
                i.mime?.startsWith("image/") == true -> sb.append("<img src=\"$f\"><a class=b href=\"$f?dl=1\">Save</a>")
                i.mime?.startsWith("video/") == true -> sb.append("<video controls playsinline src=\"$f\"></video><a class=b href=\"$f?dl=1\">Save</a>")
                else -> sb.append("${esc(i.title)}<br><a class=b href=\"$f?dl=1\">Download</a>")
            }
            sb.append("</div>")
        }
        sb.append("<script>function c(b){var t=document.createElement('textarea');t.value=b.previousElementSibling.innerText;document.body.appendChild(t);t.select();document.execCommand('copy');document.body.removeChild(t);b.innerText='Copied'}</script></body></html>")
        return sb.toString()
    }
}
