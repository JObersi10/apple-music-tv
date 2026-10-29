import { Hono } from "hono";
import { setMUT, getBearerToken, getStatus, setBearerToken, setStorefront, getMUT, getStorefront, scrapeBearerFromWeb } from "../auth";
import axios from "axios";

const auth = new Hono();

auth.get("/status", async (c) => {
  if (!getBearerToken()) {
    try { const token = await scrapeBearerFromWeb(); if (token) setBearerToken(token); } catch (e) { console.warn("Bearer scrape failed:", e); }
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

// MusicKit JS on the Fire TV needs a developer token to init. The bearer we scrape off
// music.apple.com IS Apple's own web MusicKit developer token, so hand it back for the on-TV
// "Connect to Apple Music" page (which then calls music.authorize() to get the user's MUT).
auth.get("/developer-token", async (c) => {
  if (!getBearerToken()) {
    try { const token = await scrapeBearerFromWeb(); if (token) setBearerToken(token); } catch (e) { console.warn("Bearer scrape failed:", e); }
  }
  const token = getBearerToken();
  if (!token) return c.json({ error: "No developer token available" }, 503);
  return c.json({ token });
});

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
