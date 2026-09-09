#!/usr/bin/env python3
"""Identify the song currently playing on an internet-radio stream via Shazam.

Usage: shazam_identify.py <stream_url> <ffmpeg_bin>
Grabs ~6s of the stream with ffmpeg, runs it through shazamio, and prints a single
JSON line: {"title","artist","artwork"} on a hit, or {"title":null} on a miss.

Dependency (install once on the server host):
    pip install shazamio
shazamio pulls a small Rust core wheel; if it's missing this script prints
{"title": null, "error": "..."} and the radio just keeps playing with no song ID.
"""
import asyncio
import json
import subprocess
import sys
import tempfile
import os


def capture(url: str, ffmpeg: str, seconds: int = 8) -> str | None:
    out = tempfile.NamedTemporaryFile(suffix=".wav", delete=False).name
    try:
        subprocess.run(
            [ffmpeg, "-y", "-t", str(seconds), "-i", url,
             "-ac", "1", "-ar", "16000", "-f", "wav", out],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=seconds + 20,
        )
        return out if os.path.getsize(out) > 1024 else None
    except Exception:
        return None


async def identify(path: str) -> dict:
    from shazamio import Shazam  # imported lazily so a missing dep is a clean error
    shazam = Shazam()
    data = await shazam.recognize(path)
    track = (data or {}).get("track")
    if not track:
        return {"title": None}
    art = None
    images = track.get("images") or {}
    art = images.get("coverarthq") or images.get("coverart")
    return {"title": track.get("title"), "artist": track.get("subtitle"), "artwork": art}


def main() -> None:
    if len(sys.argv) < 3:
        print(json.dumps({"title": None, "error": "usage: url ffmpeg"}))
        return
    url, ffmpeg = sys.argv[1], sys.argv[2]
    wav = capture(url, ffmpeg)
    if not wav:
        print(json.dumps({"title": None, "error": "capture failed"}))
        return
    try:
        result = asyncio.run(identify(wav))
    except Exception as e:  # missing shazamio, network, etc. — never crash the caller
        result = {"title": None, "error": str(e)[:200]}
    finally:
        try:
            os.remove(wav)
        except OSError:
            pass
    print(json.dumps(result))


if __name__ == "__main__":
    main()
