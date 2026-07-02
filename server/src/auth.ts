import { existsSync, readFileSync, writeFileSync } from "fs";
import axios from "axios";

const STATE_FILE = "./auth-state.json";

interface AuthState {
  mut: string;
  bearerToken: string;
  mutSetAt: number;
}

let state: AuthState = { mut: "", bearerToken: "", mutSetAt: 0 };
let storefront = "us";

// Persist across restarts
if (existsSync(STATE_FILE)) {
  try { state = JSON.parse(readFileSync(STATE_FILE, "utf8")); } catch {}
}

function save() {
  try { writeFileSync(STATE_FILE, JSON.stringify(state)); } catch {}
}

export const setStorefront = (sf: string) => { storefront = sf; };
export const getStorefront = () => storefront;
export const setMUT = (token: string) => { state.mut = token; state.mutSetAt = Date.now(); save(); };
export const getMUT = () => state.mut;
export const hasMUT = () => state.mut.length > 0;
export const setBearerToken = (t: string) => { state.bearerToken = t; save(); };
export const getBearerToken = () => state.bearerToken;
export async function scrapeBearerFromWeb(): Promise<string> {
  const ax = axios.create({
    baseURL: "https://music.apple.com",
    headers: { "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15" },
  });
  const html: string = (await ax.get("/")).data;
  const scriptMatch = html.match(/crossorigin src="(\/assets\/index.+?\.js)"/);
  if (!scriptMatch) throw new Error("Could not find script asset URL");
  const js: string = (await ax.get(scriptMatch[1])).data;
  const tokenMatch = js.match(/(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)/);
  if (!tokenMatch) throw new Error("Could not find JWT in script");
  return tokenMatch[1];
}

let bearerScrapePromise: Promise<void> | null = null;

export function ensureBearer(): Promise<void> {
  if (state.bearerToken) return Promise.resolve();
  if (bearerScrapePromise) return bearerScrapePromise;
  bearerScrapePromise = scrapeBearerFromWeb()
    .then((t) => { if (t) setBearerToken(t); })
    .catch((e) => { console.warn("[auth] Bearer scrape failed:", e); })
    .finally(() => { bearerScrapePromise = null; });
  return bearerScrapePromise;
}

export const getStatus = () => ({
  hasMUT: hasMUT(),
  mutSetAt: state.mutSetAt ? new Date(state.mutSetAt).toISOString() : null,
  hasBearer: state.bearerToken.length > 0,
});
