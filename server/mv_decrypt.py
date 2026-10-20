#!/usr/bin/env python3
"""
Decrypts an Apple Music MUSIC VIDEO (Widevine CENC) to a single progressive MP4.

On-device, MVs can only play through the secure Widevine video decoder
(OMX.MTK.VIDEO.DECODER.AVC.secure), which latches a protected surface plane that
bleeds across tabs on this Fire TV. Decrypting server-side and handing the app a
plain MP4 means a NON-secure decoder, so the bleed goes away entirely.

Pipeline (mirrors stream_decrypt.py's audio path, extended to two tracks):
  webPlayback(mvId) -> hls-playlist-url master
  pick best video variant <= maxHeight, best audio rendition (non-Atmos)
  for each track: read Widevine #EXT-X-KEY -> license via Apple -> content key;
                  download init+segments, mp4decrypt --key 1:<key>
  ffmpeg mux decrypted video + audio -> one +faststart MP4

Usage: mv_decrypt.py '<json_args>'
  json_args: {mvId, bearer, mut, outPath, maxHeight?}
"""
import sys, asyncio, base64, json, subprocess, os, re, time

GAMDL_SITE = os.environ.get('GAMDL_SITE', '')
if GAMDL_SITE:
    sys.path.insert(0, GAMDL_SITE)

from pywidevine import PSSH, Cdm, Device
from gamdl.interface.wvd import WVD
import httpx

WEB_PLAYBACK = 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback'
LICENSE_URL  = 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense'
MP4DECRYPT   = os.environ.get('MP4DECRYPT_BIN', 'mp4decrypt')
FFMPEG       = os.environ.get('FFMPEG_BIN', 'ffmpeg')
WIDEVINE_UUID = 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed'


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def _play_headers(bearer, mut):
    return {'Authorization': f'Bearer {bearer}', 'Cookie': f'media-user-token={mut}',
            'Origin': 'https://music.apple.com'}


async def get_master(client, mv_id, bearer, mut):
    """webPlayback -> (adamId, master playlist url)."""
    numeric = re.sub(r'^[a-z]+\.', '', mv_id)
    for field in ('salableAdamId', 'universalLibraryId'):
        id_val = mv_id if field == 'universalLibraryId' else numeric
        resp = await client.post(
            WEB_PLAYBACK,
            json={field: id_val, 'language': 'en-US'},
            headers={**_play_headers(bearer, mut), 'Content-Type': 'application/json'},
            timeout=30.0,
        )
        data = resp.json()
        song_list = data.get('songList') or []
        if song_list and song_list[0].get('hls-playlist-url'):
            e = song_list[0]
            return str(e.get('songId', numeric)), e['hls-playlist-url']
    raise RuntimeError('MV webPlayback rejected (no hls-playlist-url)')


def _abs(base, u):
    return u if u.startswith('http') else base + u


def pick_variants(master_text, master_url, max_height):
    """From the master, choose one video media-playlist URL and one audio URL."""
    base = master_url.rsplit('/', 1)[0] + '/'
    lines = master_text.splitlines()

    # Video variants: (height, uri)
    variants = []
    for i, ln in enumerate(lines):
        if ln.startswith('#EXT-X-STREAM-INF'):
            m = re.search(r'RESOLUTION=\d+x(\d+)', ln)
            h = int(m.group(1)) if m else 0
            uri = lines[i + 1].strip() if i + 1 < len(lines) else ''
            if uri and not uri.startswith('#'):
                variants.append((h, uri))
    if not variants:
        raise RuntimeError('No video variant in MV master')
    at_or_below = [v for v in variants if 0 < v[0] <= max_height]
    if at_or_below:
        v_height, v_uri = max(at_or_below, key=lambda v: v[0])
    else:
        pos = [v for v in variants if v[0] > 0]
        v_height, v_uri = (min(pos, key=lambda v: v[0]) if pos else max(variants, key=lambda v: v[0]))

    # Audio renditions: highest bitrate token in GROUP-ID/NAME, skipping Atmos/binaural.
    audio_lines = [ln for ln in lines if ln.startswith('#EXT-X-MEDIA:TYPE=AUDIO')]

    def score(ln):
        ident = ((re.search(r'GROUP-ID="([^"]+)"', ln) or [None, ''])[1] + ' ' +
                 (re.search(r'NAME="([^"]+)"', ln) or [None, ''])[1])
        if 'atmos' in ident.lower() or 'binaural' in ident.lower():
            return -1
        nums = [int(x) for x in re.findall(r'\d+', ident)]
        return max(nums) if nums else 0

    scored = [(score(ln), ln) for ln in audio_lines]
    scored = [s for s in scored if s[0] > 0] or scored
    if not scored:
        raise RuntimeError('No audio rendition in MV master')
    a_line = max(scored, key=lambda s: s[0])[1]
    a_uri = (re.search(r'URI="([^"]+)"', a_line) or [None, None])[1]
    if not a_uri:
        raise RuntimeError('Audio rendition has no URI')

    log(f'[mv] video {v_height}p (avail={sorted(set(v[0] for v in variants))}), audio picked')
    return _abs(base, v_uri), _abs(base, a_uri)


def widevine_key_uri(media_text):
    """Return the Widevine #EXT-X-KEY data: URI from a media playlist."""
    for ln in media_text.splitlines():
        if ln.startswith('#EXT-X-KEY') and WIDEVINE_UUID in ln:
            m = re.search(r'URI="([^"]+)"', ln)
            if m:
                return m.group(1)
    # Some masters only carry the key in the variant; fall back to any data: key line.
    for ln in media_text.splitlines():
        if ln.startswith('#EXT-X-KEY'):
            m = re.search(r'URI="(data:[^"]+)"', ln)
            if m:
                return m.group(1)
    raise RuntimeError('No Widevine key line in MV media playlist')


def reconstruct_pssh(key_uri):
    raw = base64.b64decode(key_uri.split(',')[-1])
    return raw


async def license_key(client, key_uri, adam_id, bearer, mut):
    """Challenge Apple with the track's pssh, return the CONTENT key hex."""
    cdm = Cdm.from_device(Device.loads(WVD))
    session = cdm.open()
    try:
        challenge = base64.b64encode(
            cdm.get_license_challenge(session, PSSH(reconstruct_pssh(key_uri)))
        ).decode()
        resp = await client.post(
            LICENSE_URL,
            json={'challenge': challenge, 'key-system': 'com.widevine.alpha',
                  'uri': key_uri, 'adamId': adam_id, 'isLibrary': False,
                  'user-initiated': True},
            headers={**_play_headers(bearer, mut), 'Content-Type': 'application/json'},
            timeout=30.0,
        )
        resp.raise_for_status()
        data = resp.json()
        if data.get('failureType'):
            raise RuntimeError(f"license failureType {data['failureType']}: "
                               f"{data.get('customerMessage', '')}")
        lic = (data.get('license') or data.get('licenseCert') or data.get('lic')
               or data.get('licenseData'))
        if not lic:
            raise RuntimeError(f'no license field: {list(data.keys())}')
        cdm.parse_license(session, lic)
        keys = [k for k in cdm.get_keys(session) if k.type == 'CONTENT']
        if not keys:
            raise RuntimeError('license carried no CONTENT key')
        return keys[0].key.hex()
    finally:
        cdm.close(session)


async def download_track(client, media_url, headers, enc_path):
    """Download a media playlist's init + all segments, concatenated, to enc_path."""
    pl = (await client.get(media_url, headers=headers, timeout=60.0)).text
    base = media_url.rsplit('/', 1)[0] + '/'
    init_url, seg_urls = None, []
    for ln in pl.splitlines():
        ln = ln.strip()
        if ln.startswith('#EXT-X-MAP:URI="'):
            uri = ln.split('"')[1]
            init_url = _abs(base, uri)
        elif ln and not ln.startswith('#'):
            seg_urls.append(_abs(base, ln))
    urls = ([init_url] if init_url else []) + seg_urls
    if not urls:
        raise RuntimeError('No segments in MV media playlist')

    async def _dl(url, retries=3):
        for attempt in range(retries):
            try:
                chunks = []
                t = httpx.Timeout(connect=15.0, read=120.0, write=30.0, pool=5.0)
                async with client.stream('GET', url, headers=headers, timeout=t) as r:
                    r.raise_for_status()
                    async for c in r.aiter_bytes(65536):
                        chunks.append(c)
                return b''.join(chunks)
            except httpx.TransportError:
                if attempt == retries - 1:
                    raise
                await asyncio.sleep(2 ** attempt)

    data_list = await asyncio.gather(*[_dl(u) for u in urls])
    with open(enc_path, 'wb') as f:
        for d in data_list:
            f.write(d)
    return len(seg_urls)


async def process_track(client, media_url, adam_id, bearer, mut, tag, workdir):
    """License + download + decrypt one track. Returns the decrypted file path."""
    headers = {'Authorization': f'Bearer {bearer}', 'Cookie': f'media-user-token={mut}'}
    media_text = (await client.get(media_url, headers=headers, timeout=60.0)).text
    key_uri = widevine_key_uri(media_text)

    t0 = time.time()
    key_hex = await license_key(client, key_uri, adam_id, bearer, mut)
    log(f'[mv] {tag} license {time.time()-t0:.1f}s')

    enc = os.path.join(workdir, f'mv_{adam_id}_{tag}_enc.mp4')
    dec = os.path.join(workdir, f'mv_{adam_id}_{tag}_dec.mp4')
    t1 = time.time()
    segs = await download_track(client, media_url, headers, enc)
    log(f'[mv] {tag} download {time.time()-t1:.1f}s segs={segs} '
        f'enc={os.path.getsize(enc)//1024}KB')

    t2 = time.time()
    r = subprocess.run([MP4DECRYPT, '--key', f'1:{key_hex}', enc, dec],
                       capture_output=True)
    log(f'[mv] {tag} mp4decrypt {time.time()-t2:.1f}s rc={r.returncode}')
    if r.returncode != 0 or not os.path.exists(dec) or os.path.getsize(dec) == 0:
        raise RuntimeError(f'{tag} mp4decrypt failed: {r.stderr.decode()[:300]}')
    try:
        os.remove(enc)
    except OSError:
        pass
    return dec


async def run(args):
    mv_id     = args['mvId']
    bearer    = args['bearer']
    mut       = args['mut']
    out_path  = args['outPath']
    max_height = int(args.get('maxHeight', 1080))

    workdir = os.path.dirname(out_path) or '/tmp'
    async with httpx.AsyncClient(http2=False) as client:
        adam_id, master_url = await get_master(client, mv_id, bearer, mut)
        master_text = (await client.get(
            master_url, headers=_play_headers(bearer, mut), timeout=60.0)).text
        video_url, audio_url = pick_variants(master_text, master_url, max_height)

        video_dec, audio_dec = await asyncio.gather(
            process_track(client, video_url, adam_id, bearer, mut, 'v', workdir),
            process_track(client, audio_url, adam_id, bearer, mut, 'a', workdir),
        )

    tmp_out = out_path + '.tmp'
    t0 = time.time()
    r = subprocess.run(
        [FFMPEG, '-y', '-v', 'error', '-i', video_dec, '-i', audio_dec,
         '-map', '0:v:0', '-map', '1:a:0', '-c', 'copy',
         '-movflags', '+faststart', '-f', 'mp4', tmp_out],
        capture_output=True)
    log(f'[mv] mux {time.time()-t0:.1f}s rc={r.returncode}')
    if r.returncode != 0 or not os.path.exists(tmp_out) or os.path.getsize(tmp_out) == 0:
        raise RuntimeError(f'ffmpeg mux failed: {r.stderr.decode()[:400]}')
    for f in (video_dec, audio_dec):
        try:
            os.remove(f)
        except OSError:
            pass
    os.replace(tmp_out, out_path)
    log(f'[mv] done {os.path.getsize(out_path)//1024}KB')
    sys.stdout.write('ok')
    sys.stdout.flush()


if __name__ == '__main__':
    asyncio.run(run(json.loads(sys.argv[1])))
