// Simple in-memory log buffer + HTTP server on port 8081.
// Intercepts console.log/warn/error so every server log appears
// at http://<PC-IP>:8081 — useful for debugging from phone/TV.

const MAX = 500;

interface LogEntry { ts: string; level: string; msg: string }
const entries: LogEntry[] = [];

function stamp(): string {
  return new Date().toISOString().replace("T", " ").substring(0, 19);
}

function push(level: string, args: any[]) {
  const msg = args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" ");
  entries.push({ ts: stamp(), level, msg });
  if (entries.length > MAX) entries.shift();
}

// Intercept console methods BEFORE anything else imports this module.
const _log = console.log.bind(console);
const _warn = console.warn.bind(console);
const _err = console.error.bind(console);

console.log = (...a) => { _log(...a); push("INFO", a); };
console.warn = (...a) => { _warn(...a); push("WARN", a); };
console.error = (...a) => { _err(...a); push("ERROR", a); };

function html(): string {
  const rows = [...entries].reverse().map((e) => {
    const cls = e.level === "ERROR" ? "r" : e.level === "WARN" ? "y" : "g";
    const safe = e.msg.replace(/</g, "&lt;");
    return `<div class=${cls}><span class=ts>${e.ts}</span> <span class=lv>[${e.level}]</span> ${safe}</div>`;
  }).join("");

  return `<!DOCTYPE html><html lang=en><head><meta charset=UTF-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>Apple Music TV — Server Logs</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:monospace;background:#0a0a0a;color:#ccc;padding:16px;font-size:11px}
h1{font-size:14px;font-weight:700;color:#e5e5e7;margin-bottom:4px}
.sub{font-size:10px;color:#444;margin-bottom:14px}
.rbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}
.rbtn{background:none;border:1px solid #222;color:#555;padding:4px 12px;border-radius:6px;font-size:11px;cursor:pointer}
.rbtn:active{color:#aaa}
.logs{background:#080808;border-radius:8px;padding:10px;max-height:calc(100vh - 100px);overflow-y:auto;line-height:1.9}
.r{color:#ff3b30}.y{color:#ffd60a}.g{color:#4caf50}
.ts{color:#555}.lv{color:#888}
</style>
<script>
function refresh(){fetch('/api/logs').then(r=>r.json()).then(logs=>{
  const el=document.getElementById('logs');
  if(!el)return;
  el.innerHTML=logs.map(e=>{
    const c=e.level==='ERROR'?'r':e.level==='WARN'?'y':'g';
    return '<div class='+c+'><span class=ts>'+e.ts+'</span> <span class=lv>['+e.level+']</span> '+e.msg.replace(/</g,'&lt;')+'</div>';
  }).join('')||'<div style=color:#555>No logs yet.</div>';
});}
setInterval(refresh,2000);
</script>
</head><body>
<h1>Apple Music TV — Server Logs</h1>
<p class=sub>Auto-refreshes every 2s &nbsp;·&nbsp; ${entries.length} entries (max ${MAX})</p>
<div class=rbar>
  <span style=color:#555;font-size:10px>port 8081</span>
  <button class=rbtn onclick=refresh()>↻ refresh</button>
</div>
<div class=logs id=logs>${rows || '<div style=color:#555>No logs yet.</div>'}</div>
</body></html>`;
}

const streamers = new Set<ReadableStreamDefaultController>();

export function startLogServer(port = 8081) {
  // Patch push to also broadcast to SSE streamers.
  const _push = push;
  function broadcast(entry: LogEntry) {
    const data = `data: ${JSON.stringify(entry)}\n\n`;
    for (const c of streamers) { try { c.enqueue(data); } catch { streamers.delete(c); } }
  }
  console.log = (...a) => { _log(...a); const e = { ts: stamp(), level: "INFO", msg: a.map(x => typeof x === "string" ? x : JSON.stringify(x)).join(" ") }; entries.push(e); if (entries.length > MAX) entries.shift(); broadcast(e); };
  console.warn = (...a) => { _warn(...a); const e = { ts: stamp(), level: "WARN", msg: a.map(x => typeof x === "string" ? x : JSON.stringify(x)).join(" ") }; entries.push(e); if (entries.length > MAX) entries.shift(); broadcast(e); };
  console.error = (...a) => { _err(...a); const e = { ts: stamp(), level: "ERROR", msg: a.map(x => typeof x === "string" ? x : JSON.stringify(x)).join(" ") }; entries.push(e); if (entries.length > MAX) entries.shift(); broadcast(e); };

  Bun.serve({
    port,
    fetch(req) {
      const url = new URL(req.url);
      if (url.pathname === "/api/logs") {
        return new Response(JSON.stringify([...entries].reverse()), {
          headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
        });
      }
      if (url.pathname === "/stream") {
        let controller: ReadableStreamDefaultController;
        const stream = new ReadableStream({
          start(c) { controller = c; streamers.add(c); },
          cancel() { streamers.delete(controller); },
        });
        return new Response(stream, {
          headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", "Access-Control-Allow-Origin": "*" },
        });
      }
      return new Response(html(), { headers: { "Content-Type": "text/html; charset=utf-8" } });
    },
  });
  _log(`📋 Log server  → http://0.0.0.0:${port}`);
}
