package com.applemusicktv.media

import android.content.Context
import android.net.wifi.WifiManager
import com.applemusicktv.data.LyricsOffsetPreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.NetworkLog
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InAppWebServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: MutPreferences,
    private val repo: MusicRepository,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
) {
    private val logs = ArrayDeque<String>(300)
    private var job: Job? = null
    private var appScope: CoroutineScope? = null
    val port = 8080

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        appScope = scope
        job = scope.launch(Dispatchers.IO) {
            val server = ServerSocket(port)
            while (isActive) {
                try { val s = server.accept(); launch { handle(s) } } catch (_: Exception) {}
            }
            server.close()
        }
        addLog("OK", "Web server started — open ${serverUrl()} on your phone")
    }

    fun stop() { job?.cancel() }

    fun addLog(level: String, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logs) {
            if (logs.size >= 300) logs.removeFirst()
            logs.addLast("[$t][$level] $msg")
        }
    }

    fun getLogs(): List<String> = synchronized(logs) { logs.toList() }

    fun serverUrl(): String {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return "http://${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}:$port"
    }

    private fun handle(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = BufferedOutputStream(socket.getOutputStream())
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path   = parts.getOrNull(1) ?: "/"
            val hdrs = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                val idx = line.indexOf(": ")
                if (idx > 0) hdrs[line.substring(0, idx).lowercase()] = line.substring(idx + 2)
                line = reader.readLine()
            }
            var body = ""
            if (method == "POST") {
                val len = hdrs["content-length"]?.toIntOrNull() ?: 0
                val buf = CharArray(len); reader.read(buf, 0, len); body = String(buf)
            }
            when {
                method == "GET"  && path == "/"            -> send(out, 200, "text/html", html())
                method == "GET"  && path == "/status"      -> send(out, 200, "application/json", status())
                method == "GET"  && path == "/logs"        -> send(out, 200, "application/json", logsJson())
                method == "GET"  && path == "/netlogs"     -> send(out, 200, "application/json", netLogsJson())
                method == "POST" && path == "/set-token"          -> { applyToken(parseField(body, "mut")); redirect(out, "/") }
                method == "POST" && path == "/clear-token"         -> { prefs.setMUT(""); addLog("WARN","Token cleared"); redirect(out, "/") }
                method == "POST" && path == "/set-lyrics-offset"   -> { applyLyricsOffset(parseField(body, "offset")); redirect(out, "/") }
                else -> send(out, 404, "text/plain", "Not found")
            }
            out.flush(); socket.close()
        } catch (_: Exception) {}
    }

    private fun applyLyricsOffset(raw: String) {
        val ms = raw.trim().toLongOrNull()
        if (ms == null) { addLog("ERROR", "Invalid offset: $raw"); return }
        lyricsOffsetPrefs.setOffset(ms)
        addLog("OK", "Lyrics offset set to ${ms}ms")
    }

    private fun applyToken(mut: String) {
        if (mut.length < 20) { addLog("ERROR", "Token too short"); return }
        prefs.setMUT(mut)
        addLog("OK", "Music-User-Token saved (${mut.length} chars)")
        appScope?.launch {
            runCatching { repo.syncMUTToServer(mut) }.onFailure { addLog("WARN", "Server MUT sync failed: ${it.message}") }
        }
    }

    private fun send(out: BufferedOutputStream, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray()
        val status = mapOf(200 to "OK", 302 to "Found", 303 to "See Other", 400 to "Bad Request", 404 to "Not Found")[code] ?: "Error"
        val resp = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n"
        out.write(resp.toByteArray()); out.write(bytes)
    }

    private fun redirect(out: BufferedOutputStream, to: String) = send(out, 303, "text/plain", "", "Location: $to\r\n")

    private fun parseField(body: String, field: String) =
        body.split("&").find { it.startsWith("$field=") }?.removePrefix("$field=")
            ?.replace("+", " ")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

    private fun status() = """{"hasMUT":${prefs.hasMUT()},"mutLen":${prefs.getMUT().length},"url":"${serverUrl()}"}"""
    private fun logsJson() = "[${getLogs().joinToString(",") { "\"${it.replace("\"","'")}\"" }}]"
    private fun netLogsJson() = "[${NetworkLog.getAll().joinToString(",") { "\"${it.replace("\"","'")}\"" }}]"

    private fun html(): String {
        val has = prefs.hasMUT()
        val preview = if (has) prefs.getMUT().take(32) + "…" else ""
        val currentOffset = lyricsOffsetPrefs.getOffset()
        val logRows = getLogs().reversed().take(80).joinToString("") { log ->
            val cls = when { log.contains("[OK]") -> "g"; log.contains("[ERROR]") -> "r"; log.contains("[WARN]") -> "y"; else -> "d" }
            "<div class=$cls>${log.replace("<","&lt;")}</div>"
        }.ifEmpty { "<div class=d>No logs yet.</div>" }
        val netRows = NetworkLog.getAll().reversed().take(100).joinToString("") { log ->
            val cls = when { log.contains("✓") -> "g"; log.contains("✗") -> "r"; else -> "d" }
            "<div class=$cls>${log.replace("<","&lt;")}</div>"
        }.ifEmpty { "<div class=d>No requests yet.</div>" }
        return """<!DOCTYPE html><html lang=en><head><meta charset=UTF-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Apple Music TV</title><style>
*{box-sizing:border-box;margin:0;padding:0}body{font-family:-apple-system,sans-serif;background:#0a0a0a;color:#e5e5e7;padding:20px;max-width:620px;margin:auto}
h1{font-size:20px;font-weight:700;margin-bottom:2px}
.sub{font-size:11px;color:#444;margin-bottom:20px;font-family:monospace}
.card{background:#111;border-radius:12px;padding:16px;margin-bottom:12px}
h2{font-size:10px;font-weight:600;color:#555;text-transform:uppercase;letter-spacing:.8px;margin-bottom:12px}
.row{display:flex;align-items:center;gap:10px;margin-bottom:10px}
.dot{width:9px;height:9px;border-radius:50%;flex-shrink:0}
.dot-g{background:#34c759}.dot-r{background:#ff3b30}
.g{color:#34c759}.r{color:#ff3b30}.y{color:#ffd60a}.d{color:#555}
.label{font-size:13px;color:#ccc}
.sub2{font-size:10px;color:#555;font-family:monospace;word-break:break-all;margin-top:2px}
textarea{width:100%;background:#1c1c1e;border:1.5px solid #2c2c2e;border-radius:8px;color:#e5e5e7;font-family:monospace;font-size:11px;padding:10px;min-height:80px;outline:none;resize:vertical}
textarea:focus{border-color:#fa233b}
.btn{display:block;width:100%;padding:12px;border-radius:8px;font-size:14px;font-weight:600;border:none;cursor:pointer;margin-top:8px;text-align:center}
.btn-p{background:#fa233b;color:#fff}.btn-s{background:#1c1c1e;color:#888}
.btn:active{opacity:.7}
ol{font-size:12px;color:#666;line-height:2.4;padding-left:18px}code{color:#fa233b;font-family:monospace;font-size:11px}
.logs{background:#080808;border-radius:8px;padding:10px;max-height:240px;overflow-y:auto;font-family:monospace;font-size:10px;line-height:1.8}
.rbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}
.rbtn{background:none;border:1px solid #222;color:#555;padding:4px 12px;border-radius:6px;font-size:11px;cursor:pointer}
.rbtn:active{color:#aaa}
</style>
<script>
function refresh(){
  fetch('/status').then(r=>r.json()).then(s=>{
    const el=document.getElementById('status');
    if(el)el.innerHTML=s.hasMUT?'<div class="row"><div class="dot dot-g"></div><div><div class=label>Music-User-Token active</div></div></div>':'<div class="row"><div class="dot dot-r"></div><div><div class=label>No token</div></div></div>';
  });
  fetch('/logs').then(r=>r.json()).then(logs=>{
    const el=document.getElementById('applogs');
    if(el)el.innerHTML=logs.reverse().slice(0,80).map(l=>{const c=l.includes('[OK]')?'g':l.includes('[ERROR]')?'r':l.includes('[WARN]')?'y':'d';return'<div class='+c+'>'+l.replace(/</g,'&lt;')+'</div>';}).join('')||'<div class=d>No logs.</div>';
  });
  fetch('/netlogs').then(r=>r.json()).then(logs=>{
    const el=document.getElementById('netlogs');
    if(el)el.innerHTML=logs.reverse().slice(0,100).map(l=>{const c=l.includes('✓')?'g':l.includes('✗')?'r':'d';return'<div class='+c+'>'+l.replace(/</g,'&lt;')+'</div>';}).join('')||'<div class=d>No requests.</div>';
  });
}
setInterval(refresh,3000);
</script>
</head><body>
<h1>Apple Music TV</h1>
<p class=sub>${serverUrl()}</p>

<div class=card>
<h2>Status</h2>
<div id=status>
<div class=row><div class="dot ${if(has)"dot-g" else "dot-r"}"></div><div><div class=label>Music-User-Token</div>${if(has)"<div class=sub2>$preview</div>" else "<div class=sub2 style=color:#555>Not set — paste below</div>"}</div></div>
</div>
</div>

<div class=card>
<h2>Set Token</h2>
<form method=POST action=/set-token>
<textarea name=mut placeholder="eyJra… paste your Music-User-Token here"></textarea>
<button class="btn btn-p" type=submit>Save Token</button>
</form>
${if(has)"<form method=POST action=/clear-token><button class='btn btn-s' type=submit>Clear Token</button></form>" else ""}
</div>

<div class=card>
<h2>Lyrics Offset</h2>
<div class=row><div class=label>Current offset</div><div class=sub2 style=color:#aaa>${currentOffset}ms</div></div>
<form method=POST action=/set-lyrics-offset>
<input name=offset type=number value="$currentOffset" placeholder="0" style="width:100%;background:#1c1c1e;border:1.5px solid #2c2c2e;border-radius:8px;color:#e5e5e7;font-family:monospace;font-size:13px;padding:10px;outline:none;margin-top:4px">
<button class="btn btn-p" type=submit style=margin-top:8px>Set Offset (ms)</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">Positive = lyrics show earlier. Negative = later. Try +200 if lyrics feel late.</div>
</div>

<div class=card>
<h2>How to get your token</h2>
<ol>
<li>Open <strong style=color:#ccc>music.apple.com</strong> in Chrome · sign in</li>
<li>DevTools (F12) → Network tab</li>
<li>Play any song</li>
<li>Find request to <code>amp-api-edge.music.apple.com</code></li>
<li>Copy the <code>Media-User-Token</code> request header value</li>
</ol>
</div>

<div class=card>
<div class=rbar><h2 style=margin:0>Network Activity</h2><button class=rbtn onclick=refresh()>↻</button></div>
<div class=logs id=netlogs>$netRows</div>
</div>

<div class=card>
<div class=rbar><h2 style=margin:0>App Logs</h2><button class=rbtn onclick=refresh()>↻</button></div>
<div class=logs id=applogs>$logRows</div>
</div>
</body></html>"""
    }
}
