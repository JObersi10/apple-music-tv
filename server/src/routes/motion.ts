import { Hono } from "hono";
import axios from "axios";
import { getMUT, getBearerToken, hasMUT, getStorefront } from "../auth";

const motion = new Hono();

/**
 * GET /api/motion/:songId → { video: string | null }
 *
 * Apple's "animated album art" lives on the ALBUM as editorialVideo (an HLS
 * m3u8 loop). Only a subset of albums have it. We resolve the song → its
 * album, request the album with `extend=editorialVideo`, and return the best
 * square motion variant if present.
 */
motion.get("/:songId", async (c) => {
  if (!hasMUT()) return c.json({ video: null });
  const songId = c.req.param("songId");

  const headers = {
    Authorization: `Bearer ${getBearerToken()}`,
    "Music-User-Token": getMUT(),
    Origin: "https://music.apple.com",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15",
  };
  const sf = getStorefront();

  try {
    const albumId = await resolveAlbumId(songId, headers, sf);
    if (!albumId) return c.json({ video: null });

    const res = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/albums/${albumId}?extend=editorialVideo`,
      { headers }
    );
    const ev = res.data?.data?.[0]?.attributes?.editorialVideo;
    const video = pickMotion(ev);
    return c.json({ video });
  } catch (e: any) {
    return c.json({ video: null });
  }
});

/** Pick a square motion video URL from an editorialVideo object. */
function pickMotion(ev: any): string | null {
  if (!ev) return null;
  const candidates = [
    ev.motionSquareVideo1x1,
    ev.motionDetailSquare,
    ev.motionSquareVideo3x4, // last resort — non-square but still a loop
    ev.motionDetailTall,
  ];
  for (const cand of candidates) {
    if (cand?.video) return cand.video;
  }
  return null;
}

async function resolveAlbumId(
  songId: string,
  headers: Record<string, string>,
  sf: string
): Promise<string | null> {
  // Library song → resolve to catalog song first.
  let catalogSongId = songId;
  if (songId.startsWith("i.")) {
    try {
      const rel = await axios.get(
        `https://amp-api-edge.music.apple.com/v1/me/library/songs/${songId}?include=catalog`,
        { headers }
      );
      catalogSongId = rel.data?.data?.[0]?.relationships?.catalog?.data?.[0]?.id ?? "";
    } catch (_) {
      return null;
    }
    if (!catalogSongId) return null;
  }

  try {
    const res = await axios.get(
      `https://amp-api-edge.music.apple.com/v1/catalog/${sf}/songs/${catalogSongId}?include=albums`,
      { headers }
    );
    return res.data?.data?.[0]?.relationships?.albums?.data?.[0]?.id ?? null;
  } catch (_) {
    return null;
  }
}

export default motion;
