import axios from "axios";

export interface AppleServiceStatus {
  name: string;
  status: "operational" | "issue" | "unknown";
}

export interface AppleStatusResult {
  ok: boolean;
  services: AppleServiceStatus[];
  checkedAt: string;
}

let cached: AppleStatusResult | null = null;
let cachedAt = 0;
const CACHE_TTL_MS = 2 * 60 * 1000; // re-check at most every 2 minutes

const MUSIC_KEYWORDS = ["Apple Music", "iTunes Store", "iTunes Match"];

export async function checkAppleStatus(): Promise<AppleStatusResult> {
  if (cached && Date.now() - cachedAt < CACHE_TTL_MS) return cached;

  try {
    const res = await axios.get(
      "https://www.apple.com/support/systemstatus/data/system_status_en_US.js",
      { timeout: 8_000, responseType: "text", transformResponse: [(d) => d] }
    );
    // Historically JSONP (`setStatus({...})`); Apple now serves bare JSON. Strip
    // a wrapper only if one is actually present, and never assume axios left the
    // body as a string — its default transform JSON.parses anything JSON-shaped.
    const data =
      typeof res.data === "string"
        ? JSON.parse(
            res.data.trim().startsWith("{")
              ? res.data
              : res.data.replace(/^[^(]*\(/, "").replace(/\)\s*;?\s*$/, "")
          )
        : res.data;

    // An event is only a live problem while it has no end date, or its end date
    // is still in the future. Apple leaves resolved incidents in the feed for
    // days ("We performed routine maintenance") — those must not read as down.
    const now = Date.now();
    const isOngoing = (e: any) => {
      const end = e.epochEndDate;
      if (end == null) return true;
      return Number(end) > now;
    };

    const services: AppleServiceStatus[] = [];
    for (const svc of data.services ?? []) {
      if (!MUSIC_KEYWORDS.some((k) => svc.serviceName?.includes(k))) continue;
      const events: any[] = svc.events ?? [];
      const live = events.filter(isOngoing);
      services.push({
        name: svc.serviceName,
        status: live.length > 0 ? "issue" : "operational",
      });
      for (const e of live) {
        console.warn(`[apple-status] ${svc.serviceName}: ${e.statusType ?? "?"} — ${e.message ?? ""}`);
      }
    }

    const ok = services.every((s) => s.status === "operational");
    cached = { ok, services, checkedAt: new Date().toISOString() };
    cachedAt = Date.now();
    return cached;
  } catch (e: any) {
    console.warn("[apple-status] check failed:", e.message);
    cached = { ok: true, services: [], checkedAt: new Date().toISOString() };
    cachedAt = Date.now();
    return cached;
  }
}

/** Log Apple status when a library 500 occurs. Returns true if Apple is down. */
export async function logAppleStatusOnError(): Promise<boolean> {
  try {
    const s = await checkAppleStatus();
    if (!s.ok) {
      const affected = s.services.filter((x) => x.status !== "operational").map((x) => x.name).join(", ");
      console.warn(`[apple-status] SERVICE ISSUE DETECTED — affected: ${affected}`);
    } else if (s.services.length > 0) {
      console.log("[apple-status] All monitored Apple Music services operational");
    }
    return !s.ok;
  } catch {
    return false;
  }
}
