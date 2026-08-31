# Security

## Your tokens

- Your **Music-User-Token (MUT)** grants full access to your Apple Music library and account. **Treat it like a password.** Never share it, paste it into untrusted tools, or commit it.
- The app stores the MUT on-device and (only if you use the proxy) in `server/auth-state.json`. The **bearer token** is scraped from `music.apple.com` automatically.
- These files are gitignored and must never be committed: `server/auth-state.json`, `server/.env`, `android/local.properties`, and any `*.keystore`.

## Running safely

- The optional proxy binds to `0.0.0.0` — run it **only on a trusted LAN**, never exposed to the internet.
- The phone setup page (port 8080) and the proxy have no authentication; anyone on your network who reaches them can read status/logs and set the token. Keep them on a private network.
- Streams are decrypted **locally for playback only**. Don't use this to redistribute content.

## Reporting a vulnerability

This is a personal / educational project with no formal support. If you find a security issue (e.g. token leakage, an unsafe network default), please **open a GitHub issue** describing it, or if it's sensitive, avoid posting the details publicly and note that you'd like to share them privately.

Please **do not** include real tokens, credentials, or personal data in issues or logs you attach.
