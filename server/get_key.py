#!/usr/bin/env python3
"""Gets the AES content key for an Apple Music CENC stream. Prints hex key to stdout."""
import sys, asyncio, base64, json, os

GAMDL_SITE = os.environ.get('GAMDL_SITE', '')
if GAMDL_SITE:
    sys.path.insert(0, GAMDL_SITE)

from pywidevine import PSSH, Cdm, Device
from pywidevine.license_protocol_pb2 import WidevinePsshData
from gamdl.interface.wvd import WVD
import httpx

LICENSE_URL = 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense'

def reconstruct_pssh(key_uri: str) -> bytes:
    b64 = key_uri.split(',')[-1]
    raw = base64.b64decode(b64)
    if len(raw) > 30:
        return raw  # already a full PSSH
    pssh_data = WidevinePsshData(algorithm=1, key_ids=[raw])
    return pssh_data.SerializeToString()

async def get_key(adam_id: str, key_uri: str, bearer: str, mut: str) -> str:
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
                json={
                    'challenge': challenge,
                    'key-system': 'com.widevine.alpha',
                    'uri': key_uri,
                    'adamId': adam_id,
                    'isLibrary': False,
                    'user-initiated': True,
                },
                headers={
                    'Authorization': f'Bearer {bearer}',
                    'Cookie': f'media-user-token={mut}',
                    'Origin': 'https://music.apple.com',
                    'Content-Type': 'application/json',
                },
                timeout=30.0,
            )
        resp.raise_for_status()
        lic = resp.json()
        cdm.parse_license(session, lic['license'])
        content_key = next(k for k in cdm.get_keys(session) if k.type == 'CONTENT')
        return content_key.key.hex()
    finally:
        cdm.close(session)

if __name__ == '__main__':
    args = json.loads(sys.argv[1])
    key = asyncio.run(get_key(args['adamId'], args['keyUri'], args['bearer'], args['mut']))
    print(key, end='')
