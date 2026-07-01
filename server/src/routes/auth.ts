import { Hono } from "hono";
import { setMUT, getBearerToken, getStatus, setBearerToken, setStorefront, getMUT, getStorefront } from "../auth";
import axios from "axios";

const auth = new Hono();

// Scrape bearer JWT directly from music.apple.com (same method the library uses)
async function scrapeBearerToken(): Promise<string> {
  const ax = axios.create({ baseURL: "https://music.apple.com", headers: { "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15" } });
  const html: string = (await ax.get("/")).data;
  const scriptMatch = html.match(/crossorigin src="(\/assets\/index.+?\.js)"/);
  if (!scriptMatch) throw new Error("Could not find script asset URL");
  const js: string = (await ax.get(scriptMatch[1])).data;
  const tokenMatch = js.match(/(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)/);
  if (!tokenMatch) throw new Error("Could not find JWT in script");
  return tokenMatch[1];
}

auth.get("/status", async (c) => {
  if (!getBearerToken()) {
    try { const token = await scrapeBearerToken(); if (token) setBearerToken(token); } catch (e) { console.warn("Bearer scrape failed:", e); }
  }
  if (getMUT() && getBearerToken() && getStorefront() === "us") {
    detectStorefront(getMUT(), getBearerToken()).catch(() => {});
  }
  return c.json(getStatus());
});

async function detectStorefront(mut: string, bearer: string) {
  try {
    const res = await axios.get("https://amp-api-edge.music.apple.com/v1/me/storefront", {
      headers: { Authorization: `Bearer ${bearer}`, "Media-User-Token": mut, Origin: "https://music.apple.com" },
    });
    const sf = res.data?.data?.[0]?.id;
    if (sf) { setStorefront(sf); console.log("✓ Storefront:", sf); }
  } catch (e: any) { console.warn("Storefront detect failed:", e.message); }
}

auth.post("/token", async (c) => {
  const { mut } = await c.req.json<{ mut: string }>();
  if (!mut || typeof mut !== "string" || mut.trim().length < 20)
    return c.json({ error: "Invalid Music-User-Token" }, 400);
  const trimmed = mut.trim();
  setMUT(trimmed);
  detectStorefront(trimmed, getBearerToken()).catch(() => {});
  return c.json({ ok: true });
});

auth.delete("/token", (c) => {
  setMUT("");
  return c.json({ ok: true });
});

export default auth;
