import { createSign } from "crypto";
import { readFileSync } from "fs";

// ── Fill these in ──────────────────────────────────────────────
const KEY_ID   = "XXXXXXXXXX";          // 10-char Key ID from developer.apple.com
const TEAM_ID  = "XXXXXXXXXX";          // 10-char Team ID from developer.apple.com
const KEY_PATH = "./AuthKey.p8";        // path to your downloaded .p8 file
// ──────────────────────────────────────────────────────────────

function generateToken(): string {
  const header  = Buffer.from(JSON.stringify({ alg: "ES256", kid: KEY_ID })).toString("base64url");
  const now     = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(JSON.stringify({ iss: TEAM_ID, iat: now, exp: now + 3600 })).toString("base64url");
  const data    = `${header}.${payload}`;
  const key     = readFileSync(KEY_PATH, "utf8");
  const sign    = createSign("SHA256");
  sign.update(data);
  const sig = sign.sign({ key, dsaEncoding: "ieee-p1363" }).toString("base64url");
  return `${data}.${sig}`;
}

async function main() {
  console.log("🔑  Generating developer token...");

  let token: string;
  try {
    token = generateToken();
    console.log(`✅  Token generated (first 40 chars): ${token.slice(0, 40)}...`);
  } catch (e: any) {
    console.error(`❌  Failed to generate token: ${e.message}`);
    console.error("    → Check KEY_PATH points to your .p8 file and it's readable.");
    process.exit(1);
  }

  console.log("🌐  Pinging Apple Music API...");
  try {
    const res = await fetch("https://amp-api-edge.music.apple.com/v1/test", {
      headers: {
        Authorization: `Bearer ${token}`,
        Origin: "https://music.apple.com",
      },
    });

    if (res.ok) {
      console.log(`\n🎵  SUCCESS — status ${res.status}`);
      console.log("    Your MusicKit key is valid and working.");
      console.log(`\n    Developer token (save this):\n    ${token}`);
    } else {
      const body = await res.text();
      console.error(`\n❌  FAILED — status ${res.status}`);
      console.error(`    Response: ${body.slice(0, 200)}`);
      console.error("    → Double-check your KEY_ID and TEAM_ID.");
    }
  } catch (e: any) {
    console.error(`❌  Network error: ${e.message}`);
  }
}

main();
