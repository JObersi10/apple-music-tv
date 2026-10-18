#!/usr/bin/env python3
"""
Decrypts Apple Music CENC audio and writes it to stdout.
Usage: stream_decrypt.py '<json_args>'
  json_args: {adamId, keyUri, streamUrl, bearer, mut}
"""
import sys, asyncio, base64, json, subprocess, os, tempfile, time

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

# Never inherit the OS proxy settings. httpx defaults to trust_env=True, which on
# Windows picks up the WinINET proxy (a VPN/accelerator's local MITM listener).
# That listener presents its own root CA; httpx validates against certifi and the
# handshake dies with CERTIFICATE_VERIFY_FAILED — so every stream 500s while
# browse/search (Bun, which ignores the system proxy) keeps working. Set
# AM_HTTP_PROXY=http://host:port to route through a proxy deliberately, and
# AM_CA_BUNDLE=/path/to/ca.pem if that proxy terminates TLS with a private CA.
_HTTP_PROXY = os.environ.get('AM_HTTP_PROXY', '')
_CA_BUNDLE = os.environ.get('AM_CA_BUNDLE', '')
HTTP_KW = {'trust_env': False}
if _HTTP_PROXY:
    HTTP_KW['proxy'] = _HTTP_PROXY
if _CA_BUNDLE:
    HTTP_KW['verify'] = _CA_BUNDLE

# Above this, the source is lossless (ALAC ~1.9 Mbps) and worth transcoding.
# At or below, it's already AAC and we just stream-copy it.
LOSSY_CEILING_KBPS = 400
TARGET_KBPS = os.environ.get('AAC_BITRATE', '256k')

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
        async with httpx.AsyncClient(**HTTP_KW) as client:
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
        data = resp.json()
        # Apple returns failureType 3077 when the content cannot be licensed via webPlayback
        if data.get('failureType') == '3077' or 'failureType' in data:
            msg = data.get('customerMessage', 'content unavailable')
            print(f'UNAVAILABLE:{data.get("failureType","?")} {msg}', file=sys.stderr, flush=True)
            sys.exit(2)  # exit code 2 = permanent DRM refusal (not a transient error)
        lic = data.get('license') or data.get('licenseCert') or data.get('lic') or data.get('licenseData')
        if not lic:
            print(f'[cdm] license response keys: {list(data.keys())}  body: {str(data)[:300]}', flush=True)
            raise KeyError(f"no license field in response: {list(data.keys())}")
        cdm.parse_license(session, lic)
        k = next(x for x in cdm.get_keys(session) if x.type == 'CONTENT')
        return k.kid.hex, k.key.hex()
    finally:
        cdm.close(session)

import re as _re

def _best_aac_encoder() -> str:
    """Prefer AudioToolbox AAC — roughly 2x faster than ffmpeg's native encoder."""
    try:
        out = subprocess.run([FFMPEG, '-hide_banner', '-encoders'],
                             capture_output=True, timeout=10).stdout.decode()
        if ' aac_at ' in out:
            return 'aac_at'
    except Exception:
        pass
    return 'aac'


AAC_ENCODER = _best_aac_encoder()
TRANSCODE_FLAGS = ['-af', 'aresample=async=1', '-c:a', AAC_ENCODER, '-b:a', TARGET_KBPS]


async def fetch_encrypted(stream_url: str, bearer: str, mut: str, enc_path: str):
    """Download encrypted audio. Returns (is_multi_seg, duration_s)."""
    headers = {'Authorization': f'Bearer {bearer}', 'Cookie': f'media-user-token={mut}'}

    async with httpx.AsyncClient(**HTTP_KW) as client:
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
        async with httpx.AsyncClient(**HTTP_KW) as client:
            pl_text = (await client.get(best_url, headers=headers, timeout=60.0)).text
        base = best_url.rsplit('/', 1)[0] + '/'

    # Apple's ctrp playlists are ONE mp4 addressed by 16+ #EXT-X-BYTERANGE slices,
    # not 16 separate files. Ignoring the range made every slice re-download the
    # whole file: 17 full copies (21.9 MB instead of 1.29 MB for ctrp64, 84.7 MB
    # for ctrp256). It also inflated the decrypted size, which then tripped the
    # lossless check below and forced a pointless transcode of a 256k AAC source.
    parts = []          # ordered (url, start, end); start/end None = whole resource
    duration_s = 0.0
    pending_range = None
    has_init = False
    seg_count = 0
    for line in pl_text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith('#EXT-X-MAP:'):
            m = _re.search(r'URI="([^"]+)"', line)
            if not m:
                continue
            url = m.group(1)
            url = url if url.startswith('http') else base + url
            br = _re.search(r'BYTERANGE="(\d+)@(\d+)"', line)
            if br:
                n, o = int(br.group(1)), int(br.group(2))
                parts.append((url, o, o + n - 1))
            else:
                parts.append((url, None, None))
            has_init = True
        elif line.startswith('#EXT-X-BYTERANGE:'):
            m = _re.match(r'#EXT-X-BYTERANGE:(\d+)(?:@(\d+))?', line)
            if m:
                n = int(m.group(1))
                if m.group(2) is not None:
                    o = int(m.group(2))
                else:
                    # Offset omitted: continues where the previous slice ended.
                    prev = parts[-1] if parts else None
                    o = (prev[2] + 1) if prev and prev[2] is not None else 0
                pending_range = (o, o + n - 1)
        elif line.startswith('#EXTINF:'):
            try:
                duration_s += float(line[len('#EXTINF:'):].split(',')[0])
            except ValueError:
                pass
        elif not line.startswith('#'):
            url = line if line.startswith('http') else base + line
            if pending_range:
                parts.append((url, pending_range[0], pending_range[1]))
                pending_range = None
            else:
                parts.append((url, None, None))
            seg_count += 1

    if not parts:
        raise ValueError(f"No media URLs in playlist:\n{pl_text[:500]}")

    # fMP4 multi-seg: explicit init segment + multiple audio segments.
    # Regular HLS has no init segment — single download, no re-encode needed.
    is_multi_seg = has_init and seg_count > 1

    async def _dl(client, url, retries=3):
        for attempt in range(retries):
            try:
                chunks = []
                t = httpx.Timeout(connect=15.0, read=120.0, write=30.0, pool=5.0)
                async with client.stream('GET', url, headers=headers, timeout=t) as resp:
                    resp.raise_for_status()
                    async for chunk in resp.aiter_bytes(65536):
                        chunks.append(chunk)
                return b''.join(chunks)
            except httpx.TransportError as e:
                if attempt == retries - 1:
                    raise
                await asyncio.sleep(2 ** attempt)

    # One request per unique URL; slice the ranges locally. A ranged playlist then
    # costs a single download instead of one per slice.
    unique_urls = list(dict.fromkeys(p[0] for p in parts))
    async with httpx.AsyncClient(http2=False, **HTTP_KW) as client:
        fetched = await asyncio.gather(*[_dl(client, u) for u in unique_urls])
    by_url = dict(zip(unique_urls, fetched))

    with open(enc_path, 'wb') as f:
        for url, start, end in parts:
            data = by_url[url]
            f.write(data if start is None else data[start:end + 1])

    return is_multi_seg, duration_s

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

    t0 = time.time()
    kid_hex, key_hex = await get_kid_and_key(adam_id, key_uri, bearer, mut)
    print(f'[timing] license: {time.time()-t0:.1f}s', flush=True)

    # tempfile.gettempdir() rather than a literal /tmp: on Windows that path does
    # not exist, and the download's open() would fail before decryption started.
    tmp_dir = tempfile.gettempdir()
    enc_path = os.path.join(tmp_dir, f'am_enc_{adam_id}.mp4')
    dec_path = out_path or os.path.join(tmp_dir, f'am_dec_{adam_id}.mp4')
    try:
        t1 = time.time()
        is_multi_seg, duration_s = await fetch_encrypted(stream_url, bearer, mut, enc_path)
        enc_size = os.path.getsize(enc_path)
        print(f'[timing] download: {time.time()-t1:.1f}s  multi_seg={is_multi_seg}  '
              f'enc={enc_size//1024}KB  dur={duration_s:.0f}s', flush=True)

        # Decrypt with mp4decrypt to a .part file.
        tmp_dec = dec_path + '.part'
        t2 = time.time()
        result = subprocess.run(
            [MP4DECRYPT, '--key', f'{kid_hex}:{key_hex}', enc_path, tmp_dec],
            capture_output=True,
        )
        print(f'[timing] mp4decrypt: {time.time()-t2:.1f}s  rc={result.returncode}', flush=True)
        if result.returncode != 0:
            sys.stderr.write(result.stderr.decode())
            sys.exit(1)

        # Apple's decrypted output is a FRAGMENTED mp4 (brand "hlsf", tiny moov +
        # moof fragments). ExoPlayer plays that unreliably as a progressive
        # download, so remux (no re-encode) into a standard progressive mp4 with
        # the moov moved to the front (+faststart). This is what makes seeking
        # instant and playback reliable on the Fire TV.
        tmp_remux = dec_path + '.remux.mp4'
        dec_size = os.path.getsize(tmp_dec)

        def _remux(flags):
            """Remux tmp_dec → tmp_remux with the given audio flags. Returns rc."""
            t = time.time()
            # DO NOT add -fflags +genpts/+igndts here. On a fragmented mp4 they
            # shift the timeline (~50ms per minute), and aresample=async=1 then
            # chases the bad timestamps by inserting/dropping samples for the
            # whole track — continuous micro-stretching, clearly audible as
            # warble on sustained vocals. Measured 43.6 dB SDR without them
            # vs -3.7 dB with them.
            r = subprocess.run(
                [FFMPEG, '-y', '-v', 'error', '-i', tmp_dec]
                + flags
                + ['-c:v', 'copy', '-movflags', '+faststart', tmp_remux],
                capture_output=True,
            )
            out_size = os.path.getsize(tmp_remux) if os.path.exists(tmp_remux) else 0
            print(f'[timing] ffmpeg: {time.time()-t:.1f}s  rc={r.returncode}  '
                  f'flags={flags}  in={dec_size//1024}KB out={out_size//1024}KB', flush=True)
            return r, out_size

        def _probe(entries, path):
            """ffprobe a file → {key: value}. Empty dict if ffprobe is unusable."""
            # Derive ffprobe from FFMPEG_BIN when it's a full path, so a Windows
            # install that only sets FFMPEG_BIN still works.
            # (FFMPEG ends in "ffmpeg.exe" there, not "ffmpeg".)
            base = FFMPEG[:-4] if FFMPEG.lower().endswith('.exe') else FFMPEG
            exe = '.exe' if FFMPEG.lower().endswith('.exe') else ''
            probe = (base[:-6] + 'ffprobe' + exe) if base.endswith('ffmpeg') else 'ffprobe'
            try:
                out = subprocess.run(
                    [probe, '-v', 'error', '-show_entries', entries,
                     '-of', 'default=noprint_wrappers=1', path],
                    capture_output=True, timeout=15,
                )
                return dict(
                    line.split('=', 1) for line in out.stdout.decode().splitlines()
                    if '=' in line
                )
            except Exception:
                return {}

        def _out_duration():
            """Duration of tmp_remux in seconds, or None if ffprobe isn't usable."""
            try:
                return float(_probe('format=duration', tmp_remux).get('duration', ''))
            except Exception:
                return None

        # Decide by the CODEC we actually got, not by average byte rate. Measuring
        # bitrate misclassified a 256k AAC source as lossless whenever the byte
        # count was inflated (duplicate BYTERANGE downloads did exactly that, and
        # the result was a 256k→256k transcode: a whole generation of lossy loss
        # plus ~10s of CPU for nothing). Only a real lossless source needs the
        # re-encode — its 65 MB would otherwise blow up the cache and the LAN.
        src = _probe('stream=codec_name,bit_rate', tmp_dec)
        src_codec = (src.get('codec_name') or '').lower()
        src_kbps = (dec_size * 8 / duration_s / 1000) if duration_s > 0 else 0
        t3 = time.time()

        if src_codec:
            lossless = src_codec in ('alac', 'flac')
        else:
            # Probe unavailable — keep the old measurement as a fallback.
            lossless = src_kbps > LOSSY_CEILING_KBPS

        if not lossless:
            # Already compressed — stream-copy. ~1s instead of a re-encode, and
            # no second generation of lossy loss.
            ff, out_size = _remux(['-c:a', 'copy'])
            strategy = 'copy'
            # Copy can't run aresample=async=1, so nothing repairs the timestamp
            # gaps between fMP4 segments. Two checks: the output should be about
            # the size it went in (a big shortfall means dropped samples), and its
            # duration should match the playlist's #EXTINF sum (a mismatch means
            # the gaps landed in the timeline). Either way, re-encode.
            out_dur = _out_duration() if ff.returncode == 0 else None
            tol = max(0.25, duration_s * 0.003)
            dur_bad = out_dur is not None and duration_s > 0 and abs(out_dur - duration_s) > tol
            if not (ff.returncode == 0 and out_size > dec_size * 0.85) or dur_bad:
                print(f'[remux] copy unusable (rc={ff.returncode} '
                      f'{out_size//1024}KB vs {dec_size//1024}KB '
                      f'dur={out_dur} vs {duration_s:.2f}) — re-encoding', flush=True)
                ff, out_size = _remux(TRANSCODE_FLAGS)
                strategy = 'copy→transcode'
        else:
            # Lossless source. Transcoding is the whole point — a 65 MB ALAC track
            # becomes ~9 MB, which is what makes the cache and the LAN transfer
            # sane. Explicit 256k: the old code omitted -b:a and silently landed
            # on ffmpeg's ~128 kbps default.
            ff, out_size = _remux(TRANSCODE_FLAGS)
            strategy = f'transcode({AAC_ENCODER})'

        print(f'[timing] remux total: {time.time()-t3:.1f}s  strategy={strategy}  '
              f'src={src_codec or "?"}/{src_kbps:.0f}kbps', flush=True)
        if ff.returncode == 0 and out_size > 0:
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
