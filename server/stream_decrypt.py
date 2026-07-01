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

async def get_mp4_url(stream_url: str, bearer: str, mut: str) -> str:
    """Resolve the actual MP4 file URL from the HLS playlist (follows master → media)."""
    headers = {'Authorization': f'Bearer {bearer}', 'Cookie': f'media-user-token={mut}'}
    async with httpx.AsyncClient() as client:
        pl = (await client.get(stream_url, headers=headers)).text

    base = stream_url.rsplit('/', 1)[0] + '/'

    # If master playlist, follow the best-bandwidth variant
    if '#EXT-X-STREAM-INF' in pl:
        best_bw, best_url = -1, ''
        lines = pl.splitlines()
        for i, line in enumerate(lines):
            if line.startswith('#EXT-X-STREAM-INF'):
                import re
                bw_m = re.search(r'BANDWIDTH=(\d+)', line)
                bw = int(bw_m.group(1)) if bw_m else 0
                if i + 1 < len(lines):
                    next_line = lines[i + 1].strip()
                    if next_line and not next_line.startswith('#') and bw >= best_bw:
                        best_bw = bw
                        best_url = next_line if next_line.startswith('http') else base + next_line
        if not best_url:
            raise ValueError("No variant in master playlist")
        async with httpx.AsyncClient() as client:
            pl = (await client.get(best_url, headers=headers)).text
        base = best_url.rsplit('/', 1)[0] + '/'

    for line in pl.splitlines():
        if line.startswith('#EXT-X-MAP:URI="'):
            uri = line.split('"')[1]
            return uri if uri.startswith('http') else base + uri
        if line and not line.startswith('#'):
            return line if line.startswith('http') else base + line
    raise ValueError(f"No media URL found in playlist:\n{pl[:500]}")

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
    mp4_url = await get_mp4_url(stream_url, bearer, mut)

    enc_path = f'/tmp/am_enc_{adam_id}.mp4'
    dec_path = out_path or f'/tmp/am_dec_{adam_id}.mp4'
    try:
        # Download encrypted MP4
        async with httpx.AsyncClient() as client:
            async with client.stream('GET', mp4_url, timeout=120.0,
                                     headers={'Authorization': f'Bearer {bearer}',
                                              'Cookie': f'media-user-token={mut}'}) as resp:
                resp.raise_for_status()
                with open(enc_path, 'wb') as f:
                    async for chunk in resp.aiter_bytes(65536):
                        f.write(chunk)

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
        ff = subprocess.run(
            [FFMPEG, '-y', '-v', 'error', '-i', tmp_dec,
             '-c', 'copy', '-movflags', '+faststart', tmp_remux],
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
