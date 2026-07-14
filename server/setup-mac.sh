#!/usr/bin/env bash
# One-shot setup for the Apple Music TV proxy server on macOS.
# Installs everything into normal locations (Homebrew + a local Python venv),
# then writes server/.env with the resolved paths.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Apple Music TV — server setup (macOS)"

# 1. Homebrew
if ! command -v brew >/dev/null 2>&1; then
  echo "--> Installing Homebrew..."
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  eval "$(/opt/homebrew/bin/brew shellenv 2>/dev/null || /usr/local/bin/brew shellenv)"
fi

# 2. Bun, ffmpeg, mp4decrypt (Bento4), Python
echo "--> Installing bun, ffmpeg, bento4, python..."
brew install oven-sh/bun/bun ffmpeg bento4 python@3.12

# 3. Python venv + decrypt deps (gamdl ships its own Widevine test device)
echo "--> Creating Python venv and installing gamdl + pywidevine..."
python3 -m venv .venv
./.venv/bin/python -m pip install --upgrade pip >/dev/null
./.venv/bin/python -m pip install gamdl pywidevine httpx

# 4. Resolve paths
PYBIN="$(pwd)/.venv/bin/python"
MP4D="$(command -v mp4decrypt)"
FFMPEG="$(command -v ffmpeg)"

# 5. Write .env (the venv python already imports gamdl, so GAMDL_SITE is empty)
cat > .env <<EOF
GAMDL_SITE=
PYTHON_BIN=$PYBIN
MP4DECRYPT_BIN=$MP4D
FFMPEG_BIN=$FFMPEG
EOF
echo "--> Wrote server/.env"

# 6. Bun deps
echo "--> Installing server dependencies..."
bun install

echo ""
echo "==> Setup complete!"
echo ""
echo "--- Music User Token (optional, needed for library/full streams) ---"
echo "1. Open music.apple.com in your browser"
echo "2. DevTools → Network tab → click anything → find a request to amp-api-edge.music.apple.com"
echo "3. Copy the Music-User-Token request header value"
echo ""
read -r -p "Paste your Music-User-Token (or press Enter to skip): " MUT
if [ -n "$MUT" ]; then
  python3 -c "
import json, os
f = 'auth-state.json'
state = {}
if os.path.exists(f):
    try: state = json.load(open(f))
    except: pass
state['mut'] = '$MUT'
json.dump(state, open(f, 'w'))
print('Saved MUT to auth-state.json')
"
fi

echo ""
echo "Start the server with:"
echo "      cd server && bun run src/index.ts"
