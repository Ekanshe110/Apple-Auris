# Apple Auris server deployment

Public Apple addon traffic remains:

```text
nginx -> auris-legacy-gate -> per-slot legacy worker -> slot Apple credentials
```

`/opt/auris-app` is the secondary account/portal application. It has equivalent
manifest and library routes for a direct cutover, but nginx does not currently
send public `/a/` traffic to it.

## Deploy

1. Apply the exact patch from filesystem root:

   ```bash
   cd /
   patch -p1 < /root/apple-auris/docs/APPLE_SERVER_PATCH.diff
   ```

2. Parse-check Python and JSON without starting an app build:

   ```bash
   python3 - <<'PY'
   import ast
   for path in (
       "/opt/auris-legacy/app.py",
       "/opt/auris-legacy/gate.py",
       "/opt/auris-app/app.py",
       "/opt/auris-apple-addon/app.py",
   ):
       ast.parse(open(path, encoding="utf-8").read(), filename=path)
   PY
   python3 -m json.tool /opt/auris-legacy/manifest.json >/dev/null
   python3 -m json.tool /opt/auris-apple-addon/manifest.json >/dev/null
   ```

3. Restart the secondary app and active public gate:

   ```bash
   systemctl restart auris.service
   systemctl restart auris-legacy-gate.service
   ```

4. Confirm services and public manifest:

   ```bash
   systemctl is-active auris.service auris-legacy-gate.service auris-portal.service
   curl -fsS "https://music.csproject.org/a/${SLOT_TOKEN}/manifest.json"
   ```

The gate refreshes deployable worker files when a slot worker starts. It keeps
per-slot credentials, wrapper state, sessions, logs, and cached media intact.
An active worker is never overwritten underneath itself.

For media responses the gate deliberately does not forward the upstream
`Content-Length`. A player may seek or abandon a progressive stream before the
whole file arrives; the worker preserves the partial bytes for the next range.
`Content-Range` remains authoritative, while omitting the full-file length
prevents a normal early close from becoming a truncated-response error.

## Library authentication

The opaque path token selects the paid slot. The worker then reads that slot's
linked `DEV_TOKEN` and `MUSIC_TOKEN` from its protected wrapper data and sends
them to Apple only as outbound headers:

```text
Authorization: Bearer <DEV_TOKEN>
Music-User-Token: <MUSIC_TOKEN>
```

No Apple credential is accepted from the client, placed in a URL, or returned
in a response. Manifest library authentication is therefore `none`: the path
token already authenticates and selects the slot.

## Validation

Use a real paid slot without printing its token:

```bash
SLOT_TOKEN="$(python3 - <<'PY'
import sqlite3
con = sqlite3.connect('/var/lib/auris/subs.db')
print(con.execute("""
  SELECT s.config_token
  FROM upi_slots u
  JOIN subscriptions s ON s.discord_user_id = u.discord_id
  WHERE u.slot_no = 40 AND u.status = 'PAID'
""").fetchone()[0])
PY
)"

export BASE="https://music.csproject.org/a/${SLOT_TOKEN}"
curl -fsS "$BASE/manifest.json"
curl -fsS "$BASE/library/playlists?offset=0&limit=2"
curl -fsS "$BASE/library/albums?offset=0&limit=2"
curl -fsS "$BASE/library/songs?offset=0&limit=2"
```

Validate the recommendation route without printing the slot token:

```bash
python3 - <<'PY'
import json
import os
import urllib.request

base = os.environ['BASE']
with urllib.request.urlopen(base + '/recommendations?limit=8', timeout=45) as response:
    assert response.status == 200
    tracks = json.load(response).get('tracks') or []
assert len(tracks) == 8, tracks
for track in tracks:
    assert track.get('id'), track
    assert track.get('isrc'), track
print('recommendations=8 exact-ids=ok isrcs=ok')
PY
```

The route returns exact Apple catalogue ids and recording ISRCs. A rating
absent from Apple's catalogue response stays `null`; it is never guessed to
be clean. The route is recommendation-only and does not change the Apple
playback backend.

Track routes use the collection id returned by the collection response:

```text
/library/playlists/{id}/tracks
/library/albums/{id}/tracks
/library/songs/{id}/tracks
```

Successful pages are normalized as:

```json
{
  "items": [],
  "offset": 0,
  "limit": 25,
  "total": null,
  "hasMore": false,
  "nextOffset": null
}
```

Errors are distinct from empty pages:

```json
{
  "error": {
    "code": "signed_out",
    "message": "Apple Music sign-in required"
  }
}
```

Codes include `invalid_request`, `signed_out`, `forbidden`, `not_found`,
`rate_limited`, and `apple_upstream_error`.

## Playback proof

Run this against a real paid slot without printing its path token. It checks
catalogue identity, ALAC byte length, and a media range request:

```bash
python3 - <<'PY'
import json
import re
import sqlite3
import subprocess

con = sqlite3.connect('/var/lib/auris/subs.db')
token = con.execute("""
  SELECT s.config_token
  FROM upi_slots u
  JOIN subscriptions s ON s.discord_user_id = u.discord_id
  WHERE u.slot_no = 40 AND u.status = 'PAID'
""").fetchone()[0]
base = f"https://music.csproject.org/a/{token}"

def get_json(path):
    return json.loads(subprocess.run(
        ['curl', '-fsS', base + path],
        check=True,
        capture_output=True,
        text=True,
    ).stdout)

expected = {
    '1824400214': 39483240,  # Saiyaara Reprise - Female
    '1529555769': 23788126,  # Makhna
    '1529447366': 33390742,  # Ud - Daa Punjab
}
for catalogue_id, expected_length in expected.items():
    descriptor = get_json(
        f'/stream/{catalogue_id}?q=lossless&atmos=none'
    )
    assert descriptor['codec'] == 'alac', descriptor
    headers = subprocess.run(
        [
            'curl', '-fsS', '-o', '/dev/null', '-D', '-',
            '-H', 'Range: bytes=0-1023', descriptor['url'],
        ],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    content_range = re.search(
        r'(?im)^content-range:\s*bytes\s+\d+-\d+/(\d+)',
        headers,
    )
    assert content_range, headers
    actual_length = int(content_range.group(1))
    assert actual_length == expected_length, (
        catalogue_id, actual_length, expected_length
    )
    print(catalogue_id, descriptor['codec'], actual_length, 'range=ok')
PY
```

Expected output includes:

```text
1824400214 alac 39483240 range=ok
1529555769 alac 23788126 range=ok
1529447366 alac 33390742 range=ok
```

Normal playback and range requests must not carry a download-intent header or
`download=1`; download quota applies only to explicit downloads.
