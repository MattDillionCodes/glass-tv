package io.github.mattdillioncodes.glass

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.asKotlinRandom

/**
 * A tiny web server for sending photos from a phone to the TV. TVs have no photos and usually no
 * file picker, but every phone has a browser: while Glass Settings shows "Add your photos", this
 * serves an upload page at http://<tv-ip>:8765/<code> on the local network.
 *
 * Safety, in layers:
 *  - Only while that screen is open, and it stops itself after [IDLE_LIMIT_MS] without activity.
 *  - Only answers under a random 6-character [code]; after [MAX_WRONG] wrong addresses (someone
 *    guessing) it shuts down for good.
 *  - Only answers devices on the local network (private, link-local or loopback addresses).
 *  - Every device must be allowed on the TV first ([onAsk]), like AirDrop; denied devices stay out.
 *  - Only real images (checked by their first bytes), within size, pixel, count and free-space
 *    limits; each is re-encoded by [Wallpapers.importPhoto] before Glass shows it.
 *  - Strict browser security headers, nothing cached, no referrer (the code is in the URL).
 * Not HTTPS: a TV can only make a self-signed certificate, which every phone flags as "not
 * private"; on a password-protected home network the Wi-Fi itself is encrypted.
 *
 * Callbacks run on server threads.
 */
class UploadServer(
    private val context: Context,
    private val onPhoto: () -> Unit,
    /** A new device wants to send photos; call answer(true) to allow it. */
    private val onAsk: (device: String, answer: (Boolean) -> Unit) -> Unit,
    /** The server stopped on its own; [reason] is shown on the TV. */
    private val onStopped: (reason: String) -> Unit,
) {
    val code = SecureRandom().asKotlinRandom().let { r -> (1..6).map { ALPHABET.random(r) }.joinToString("") }
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(3)
    private val approved: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val denied: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var asking: String? = null // one question on the TV at a time
    private val wrong = AtomicInteger()
    private val received = AtomicInteger()
    @Volatile private var lastActivity = System.currentTimeMillis()

    /** The address to type on the phone, or null when the TV isn't on a network. */
    val url: String?
        get() {
            val port = socket?.localPort ?: return null
            return lanAddress()?.let { "http://$it:$port/$code" }
        }

    fun start(): Boolean = runCatching {
        val s = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) } // 8765 is easy to type
        s.soTimeout = 30_000 // wake up regularly to check for idleness
        socket = s
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastActivity > IDLE_LIMIT_MS) shutDown("Stopped after 10 minutes without photos. Start again when you are ready.")
                    continue
                } catch (_: Exception) { break }
                if (!isLocal(client.inetAddress)) { runCatching { client.close() }; continue }
                pool.execute { runCatching { handle(client) } }
            }
        }, "glass-upload").apply { isDaemon = true }.start()
        true
    }.getOrDefault(false)

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun shutDown(reason: String) {
        if (socket == null) return
        stop()
        onStopped(reason)
    }

    private fun handle(client: Socket) = client.use {
        client.soTimeout = 30_000
        val ip = client.inetAddress.hostAddress ?: return
        val input = client.getInputStream().buffered()
        val out = client.getOutputStream()
        val request = readLine(input)?.split(' ') ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (headers.size >= 50) return // not a browser
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val method = request.getOrElse(0) { "" }
        val path = request.getOrElse(1) { "/" }.substringBefore('?')

        if (path != "/$code" && !path.startsWith("/$code/")) {
            // Browsers ask for icons on their own; anything else is a wrong address, maybe a guess.
            val icon = path.endsWith(".ico") || path.startsWith("/apple-touch-icon")
            if (!icon && wrong.incrementAndGet() >= MAX_WRONG) {
                respond(out, 404, "text/plain", "Not found")
                shutDown("Stopped for safety: someone tried $MAX_WRONG wrong addresses, which looks like guessing. Start again for a new address.")
                return
            }
            return respond(out, 404, "text/plain", "Not found")
        }
        lastActivity = System.currentTimeMillis()
        if (ip in denied) return respond(out, 403, "application/json", """{"ok":false,"reason":"denied"}""")

        when {
            method == "GET" && path == "/$code" -> respond(out, 200, "text/html; charset=utf-8", PAGE)
            method == "POST" && path == "/$code/hello" -> {
                val ok = ip in approved || ask(ip, describe(headers["user-agent"].orEmpty()))
                respond(out, if (ok) 200 else 403, "application/json", """{"ok":$ok}""")
            }
            method == "POST" && path == "/$code/upload" -> upload(ip, headers, input, out)
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    /** Puts the question on the TV and waits up to a minute for the answer. */
    private fun ask(ip: String, device: String): Boolean {
        synchronized(this) {
            if (asking != null && asking != ip) return false // someone else is being asked; the page can retry
            asking = ip
        }
        val decided = CountDownLatch(1)
        var allowed = false
        onAsk(device) { yes -> allowed = yes; decided.countDown() }
        val answered = decided.await(60, TimeUnit.SECONDS)
        synchronized(this) {
            asking = null
            if (answered && allowed) approved += ip else if (answered) denied += ip
        }
        return answered && allowed
    }

    private fun upload(ip: String, headers: Map<String, String>, input: InputStream, out: OutputStream) {
        val length = headers["content-length"]?.toLongOrNull()
        val freeBytes = context.getExternalFilesDir(null)?.usableSpace ?: 0L
        when {
            ip !in approved -> return respond(out, 403, "application/json", """{"ok":false,"reason":"not allowed"}""")
            length == null -> return respond(out, 411, "text/plain", "Length required")
            length > MAX_BYTES -> return respond(out, 413, "application/json", """{"ok":false,"reason":"too big"}""")
            received.get() >= MAX_PHOTOS -> return respond(out, 429, "application/json", """{"ok":false,"reason":"limit"}""")
            freeBytes < MIN_FREE_BYTES -> return respond(out, 507, "application/json", """{"ok":false,"reason":"tv full"}""")
        }
        val temp = File.createTempFile("upload", null, context.cacheDir)
        try {
            temp.outputStream().use { copy(input, it, length!!) }
            val ok = isImage(temp) && Wallpapers.importPhoto(context, temp) != null
            if (ok) { received.incrementAndGet(); lastActivity = System.currentTimeMillis(); onPhoto() }
            respond(out, if (ok) 200 else 415, "application/json", """{"ok":$ok}""")
        } finally {
            temp.delete()
        }
    }

    /** JPEG, PNG, WebP or HEIC/HEIF, by their first bytes: whatever the name or type claims. */
    private fun isImage(file: File): Boolean {
        val head = ByteArray(12)
        val n = file.inputStream().use { it.read(head) }
        if (n < 12) return false
        fun at(i: Int, s: String) = s.indices.all { head[i + it] == s[it].code.toByte() }
        return (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) ||
            (head[0] == 0x89.toByte() && at(1, "PNG")) ||
            (at(0, "RIFF") && at(8, "WEBP")) ||
            (at(4, "ftyp") && listOf("heic", "heix", "hevc", "mif1", "msf1").any { at(8, it) })
    }

    /** "An iPhone", "A Mac"...: enough for the TV owner to recognise their own device. */
    private fun describe(userAgent: String) = when {
        "iPhone" in userAgent -> "An iPhone"
        "iPad" in userAgent -> "An iPad"
        "Android" in userAgent -> "An Android phone"
        "Macintosh" in userAgent -> "A Mac"
        "Windows" in userAgent -> "A Windows PC"
        "CrOS" in userAgent -> "A Chromebook"
        else -> "A device"
    }

    private fun isLocal(address: InetAddress) =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8192) return null // not HTTP
            line.append(b.toChar())
        }
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n == -1) break
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: DENY\r\nContent-Security-Policy: $CSP\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private companion object {
        const val PORT = 8765
        const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // no 0/o, 1/l/i to misread
        const val MAX_WRONG = 10
        const val MAX_BYTES = 40L * 1024 * 1024
        const val MAX_PHOTOS = 300
        const val MIN_FREE_BYTES = 200L * 1024 * 1024
        const val IDLE_LIMIT_MS = 10 * 60 * 1000L

        /** The page may only talk to this TV: no outside scripts, frames or form targets. */
        const val CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
            "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"

        /** The phone-side page: wait to be allowed on the TV, then pick photos; sent one at a time. */
        val PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Glass · Add photos</title>
<style>
:root{color-scheme:dark}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box;
font:17px -apple-system,system-ui,sans-serif;color:#fff;background:linear-gradient(135deg,#1b2a4a,#3a1d4a 55%,#0f3b3f)}
.card{width:100%;max-width:420px;padding:28px;border-radius:28px;background:rgba(255,255,255,.12);
border:1px solid rgba(255,255,255,.25);backdrop-filter:blur(30px);-webkit-backdrop-filter:blur(30px);text-align:center}
h1{margin:0 0 6px;font-size:26px}p{margin:0 0 20px;opacity:.8;line-height:1.4}
label{display:none;padding:16px;border-radius:999px;background:#fff;color:#111;font-weight:600;cursor:pointer}
input{display:none}#status{margin-top:18px;min-height:1.4em}
</style></head><body><div class="card">
<h1>Add photos to Glass</h1>
<p id="msg">Look at your TV: choose <b>Allow</b> to let this device send photos.</p>
<label id="pick">Choose photos<input id="f" type="file" accept="image/*" multiple></label>
<div id="status"></div></div>
<script>
const f=document.getElementById('f'),s=document.getElementById('status'),m=document.getElementById('msg'),pick=document.getElementById('pick');
const base=location.pathname.replace(/\/$/,'');
async function hello(){try{const r=await fetch(base+'/hello',{method:'POST'});const j=await r.json();
if(j.ok){m.textContent='Allowed. Pick photos from this device; they\'ll appear on your TV as wallpapers.';pick.style.display='block';}
else{m.textContent='The TV didn\'t allow this device. Open “Add your photos” on the TV again to retry.';}}
catch(e){m.textContent='Can\'t reach the TV. Is the “Add your photos” screen still open?';}}
hello();
f.onchange=async()=>{const files=[...f.files];let ok=0,bad=0;
for(let i=0;i<files.length;i++){s.textContent='Sending '+(i+1)+' of '+files.length+'…';
try{const r=await fetch(base+'/upload',{method:'POST',body:files[i]});(r.ok?ok++:bad++)}catch(e){bad++}}
s.textContent=ok+' sent to your TV'+(bad?' · '+bad+' couldn’t be added':'')+'.';f.value='';};
</script></body></html>
""".trimIndent()
    }
}
