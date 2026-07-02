#!/usr/bin/env python3
"""
Decrypts Apple Music CENC audio and writes it to stdout.
Usage: stream_decrypt.py '<json_args>'
  json_args: {adamId, keyUri, streamUrl, bearer, mut}
"""
import sys, asyncio, base64, json, subprocess, os, tempfile

# Path to the gamdl venv site-packages (provides gamdl + pywidevine). Set via
# GAMDL_SITE env var in your local .env — never hard-code a personal path here.
GAMDL_SITE = os.environ.get('GAMDL_SITE', '')
if GAMDL_SITE:
    sys.path.insert(0, GAMDL_SITE)

from pywidevine import PSSH, Cdm, Device
from pywidevine.license_protocol_pb2 import WidevinePsshData
from gamdl.interface.wvd import WVD
import httpx

LICENSE_URL = 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense'
# Binaries resolve from PATH by default; override with env vars if needed.
MP4DECRYPT  = os.environ.get('MP4DECRYPT_BIN', 'mp4decrypt')
FFMPEG      = os.environ.get('FFMPEG_BIN', 'ffmpeg')

def reconstruct_pssh(key_uri: str) -> bytes:
    raw = base64.b64decode(key_uri.split(',')[-1])
    if len(raw) > 30:
        return raw
    pssh_data = WidevinePsshData(algorithm=1, key_ids=[raw])
    return pssh_data.SerializeToString()

async def get_kid_and_key(adam_id: str, key_uri: str, bearer: str, mut: str):
    pssh_bytes = reconstruct_pssh(key_uri)
    cdm = Cdm.from_device(Device.loads(WVD))
    session = cdm.open()
    try:
        challenge = base64.b64encode(
            cdm.get_license_challenge(session, PSSH(pssh_bytes))
        ).decode()
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                LICENSE_URL,
                json={'challenge': challenge, 'key-system': 'com.widevine.alpha',
                      'uri': key_uri, 'adamId': adam_id, 'isLibrary': False,
                      'user-initiated': True},
                headers={'Authorization': f'Bearer {bearer}',
                         'Cookie': f'media-user-token={mut}',
                         'Origin': 'https://music.apple.com'},
                timeout=30.0,
            )
        resp.raise_for_status()
        cdm.parse_license(session, resp.json()['license'])
        k = next(x for x in cdm.get_keys(session) if x.type == 'CONTENT')
        return k.kid.hex, k.key.hex()
    finally:
        cdm.close(session)

import re as _re

async def fetch_encrypted(stream_url: str, bearer: str, mut: str, enc_path: str) -> bool:
    """Download encrypted audio; returns True if fMP4 multi-seg (needs AAC re-encode)."""
    headers = {'Authorization': f'Bearer {bearer}', 'Cookie': f'media-user-token={mut}'}

    async with httpx.AsyncClient() as client:
        pl_text = (await client.get(stream_url, headers=headers, timeout=60.0)).text
    base = stream_url.rsplit('/', 1)[0] + '/'

    # Follow master playlist; cap at 500 kbps to skip lossless ALAC variants.
    MAX_BW = 500_000
    if '#EXT-X-STREAM-INF' in pl_text:
        best_bw, best_url = -1, ''
        fallback_bw, fallback_url = 999_999_999, ''
        lines = pl_text.splitlines()
        for i, line in enumerate(lines):
            if line.startswith('#EXT-X-STREAM-INF'):
                bw_m = _re.search(r'BANDWIDTH=(\d+)', line)
                bw = int(bw_m.group(1)) if bw_m else 0
                if i + 1 < len(lines):
                    nxt = lines[i + 1].strip()
                    if not nxt or nxt.startswith('#'):
                        continue
                    url = nxt if nxt.startswith('http') else base + nxt
                    if bw <= MAX_BW and bw >= best_bw:
                        best_bw, best_url = bw, url
                    if bw < fallback_bw:
                        fallback_bw, fallback_url = bw, url
        best_url = best_url or fallback_url
        if not best_url:
            raise ValueError("No variant in master playlist")
        async with httpx.AsyncClient() as client:
            pl_text = (await client.get(best_url, headers=headers, timeout=60.0)).text
        base = best_url.rsplit('/', 1)[0] + '/'

    init_url = None
    seg_urls = []
    for line in pl_text.splitlines():
        line = line.strip()
        if line.startswith('#EXT-X-MAP:URI="'):
            uri = line.split('"')[1]
            init_url = uri if uri.startswith('http') else base + uri
        elif line and not line.startswith('#'):
            seg_urls.append(line if line.startswith('http') else base + line)

    if not init_url and not seg_urls:
        raise ValueError(f"No media URLs in playlist:\n{pl_text[:500]}")

    # fMP4 multi-seg: explicit init segment + multiple audio segments.
    # Regular HLS has no init segment — single download, no re-encode needed.
    is_multi_seg = init_url is not None and len(seg_urls) > 1
    urls = ([init_url] if init_url else []) + seg_urls

    async with httpx.AsyncClient() as client:
        with open(enc_path, 'wb') as f:
            for url in urls:
                async with client.stream('GET', url, headers=headers, timeout=120.0) as resp:
                    resp.raise_for_status()
                    async for chunk in resp.aiter_bytes(65536):
                        f.write(chunk)

    return is_multi_seg

async def run(args: dict):
    adam_id    = args['adamId']
    key_uri    = args['keyUri']
    stream_url = args['streamUrl']
    bearer     = args['bearer']
    mut        = args['mut']
    # If outPath is given, decrypt to that file and leave it in place (a
    # seekable cache the caller serves with HTTP Range support). Otherwise
    # stream to stdout (legacy, non-seekable) and clean up.
    out_path   = args.get('outPath')

    kid_hex, key_hex = await get_kid_and_key(adam_id, key_uri, bearer, mut)

    enc_path = f'/tmp/am_enc_{adam_id}.mp4'
    dec_path = out_path or f'/tmp/am_dec_{adam_id}.mp4'
    try:
        is_multi_seg = await fetch_encrypted(stream_url, bearer, mut, enc_path)

        # Decrypt with mp4decrypt to a .part file.
        tmp_dec = dec_path + '.part'
        result = subprocess.run(
            [MP4DECRYPT, '--key', f'{kid_hex}:{key_hex}', enc_path, tmp_dec],
            capture_output=True,
        )
        if result.returncode != 0:
            sys.stderr.write(result.stderr.decode())
            sys.exit(1)

        # Apple's decrypted output is a FRAGMENTED mp4 (brand "hlsf", tiny moov +
        # moof fragments). ExoPlayer plays that unreliably as a progressive
        # download, so remux (no re-encode) into a standard progressive mp4 with
        # the moov moved to the front (+faststart). This is what makes seeking
        # instant and playback reliable on the Fire TV.
        tmp_remux = dec_path + '.remux.mp4'
        audio_flags = ['-vn', '-c:a', 'aac', '-b:a', '256k'] if is_multi_seg else ['-c', 'copy']
        ff = subprocess.run(
            [FFMPEG, '-y', '-v', 'error', '-i', tmp_dec]
            + audio_flags
            + ['-movflags', '+faststart', tmp_remux],
            capture_output=True,
        )
        if ff.returncode == 0 and os.path.exists(tmp_remux) and os.path.getsize(tmp_remux) > 0:
            try:
                os.remove(tmp_dec)
            except:
                pass
            tmp_dec = tmp_remux
        else:
            # Remux failed — fall back to the raw decrypted file so playback
            # still has a chance rather than failing outright.
            sys.stderr.write('ffmpeg remux failed: ' + ff.stderr.decode()[:500])

        if out_path:
            os.replace(tmp_dec, out_path)
            sys.stdout.write('ok')
            sys.stdout.flush()
        else:
            with open(tmp_dec, 'rb') as f:
                while chunk := f.read(65536):
                    sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
            try:
                os.remove(tmp_dec)
            except:
                pass
    finally:
        try:
            os.remove(enc_path)
        except:
            pass
        if not out_path:
            try:
                os.remove(dec_path)
            except:
                pass

if __name__ == '__main__':
    args = json.loads(sys.argv[1])
    asyncio.run(run(args))
