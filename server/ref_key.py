#!/usr/bin/env python3
"""Ground-truth content key for a song, mirroring AppleDirectClient.getWebPlayback +
resolveMediaPlaylist + get_key. Usage: python3 ref_key.py <songId>  (adamId or i.xxx)

Reads bearer+mut from auth-state.json. Prints 'kid:key' hex — compare to the device's
AMKEY log line to prove the on-device Kotlin CDM derives the correct key."""
import sys, os, re, json, base64, asyncio
GAMDL_SITE = os.environ.get('GAMDL_SITE', '/Users/jobersi10/.local/pipx/venvs/gamdl/lib/python3.14/site-packages')
sys.path.insert(0, GAMDL_SITE)
import httpx
from pywidevine import PSSH, Cdm, Device
from pywidevine.license_protocol_pb2 import WidevinePsshData
from gamdl.interface.wvd import WVD

LIC = 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense'
WP = 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback'

def reconstruct_pssh(key_uri):
    raw = base64.b64decode(key_uri.split(',')[-1])
    if len(raw) > 30:
        return raw
    return WidevinePsshData(algorithm=1, key_ids=[raw]).SerializeToString()

async def main(song_id):
    st = json.load(open(os.path.join(os.path.dirname(__file__), 'auth-state.json')))
    bearer, mut = st['bearerToken'], st['mut']
    H = {'Authorization': f'Bearer {bearer}', 'Cookie': f'media-user-token={mut}',
         'Origin': 'https://music.apple.com'}
    async with httpx.AsyncClient(headers=H, timeout=30) as c:
        is_lib = song_id.startswith('i.')
        forms = ['universalLibraryId', 'salableAdamId'] if is_lib else ['salableAdamId', 'universalLibraryId']
        entry = None
        for f in forms:
            r = await c.post(WP, json={f: song_id, 'language': 'en-US'})
            j = r.json()
            if j.get('songList'):
                entry = j['songList'][0]; break
        if not entry:
            print('webPlayback rejected', file=sys.stderr); return
        adam = entry['songId']
        assets = entry['assets']
        pick = {a['flavor']: a['URL'] for a in assets if 'URL' in a}
        url = pick.get('28:ctrp256') or pick.get('32:ctrp64') or next((v for k, v in pick.items() if 'ctrp' in k), None)
        if not url:
            print('no ctrp asset', file=sys.stderr); return
        text = (await c.get(url)).text
        if '#EXT-X-STREAM-INF' in text:
            best, bw = None, -1
            lines = text.splitlines()
            for i, ln in enumerate(lines):
                if ln.startswith('#EXT-X-STREAM-INF'):
                    m = re.search(r'BANDWIDTH=(\d+)', ln)
                    b = int(m.group(1)) if m else 0
                    nxt = lines[i+1].strip() if i+1 < len(lines) else ''
                    if nxt and not nxt.startswith('#') and b >= bw:
                        bw = b; best = nxt if nxt.startswith('http') else url.rsplit('/', 1)[0] + '/' + nxt
            url = best
            text = (await c.get(url)).text
        m = re.search(r'URI="(data:[^"]+)"', text)
        key_uri = m.group(1)

        cdm = Cdm.from_device(Device.loads(WVD))
        s = cdm.open()
        chal = base64.b64encode(cdm.get_license_challenge(s, PSSH(reconstruct_pssh(key_uri)))).decode()
        r = await c.post(LIC, json={'challenge': chal, 'key-system': 'com.widevine.alpha',
                                    'uri': key_uri, 'adamId': adam, 'isLibrary': False, 'user-initiated': True})
        cdm.parse_license(s, r.json()['license'])
        k = next(x for x in cdm.get_keys(s) if x.type == 'CONTENT')
        cdm.close(s)
        print(f'adamId={adam} kid={k.kid.hex} key={k.key.hex()}')

if __name__ == '__main__':
    asyncio.run(main(sys.argv[1]))
