/**
 * Apple MusicKit Developer Token Generator
 * Run once to get a token, or expose as a /token endpoint.
 *
 * Setup:
 *   npm install jsonwebtoken
 *   node generate-token.js
 *
 * ENV vars required:
 *   APPLE_TEAM_ID             — 10-char team ID from developer.apple.com
 *   APPLE_KEY_ID              — Key ID shown next to the .p8 download
 *   APPLE_PRIVATE_KEY_PATH    — path to your AuthKey_XXXXXXXX.p8 file
 */

const jwt  = require('jsonwebtoken');
const fs   = require('fs');
const path = require('path');

const TEAM_ID  = process.env.APPLE_TEAM_ID            || 'XXXXXXXXXX';
const KEY_ID   = process.env.APPLE_KEY_ID             || 'XXXXXXXXXX';
const KEY_PATH = process.env.APPLE_PRIVATE_KEY_PATH   || './AuthKey.p8';

// Apple maximum token lifetime is 6 months
const EXPIRY_SECONDS = 15_777_000;

function generateDeveloperToken() {
  const privateKey = fs.readFileSync(path.resolve(KEY_PATH));

  return jwt.sign({}, privateKey, {
    algorithm: 'ES256',
    expiresIn: EXPIRY_SECONDS,
    issuer:    TEAM_ID,
    header: {
      alg: 'ES256',
      kid: KEY_ID,
    },
  });
}

const token = generateDeveloperToken();
console.log('\nDeveloper Token (paste into REACT_APP_MUSICKIT_TOKEN):\n');
console.log(token);
console.log('\nExpires in 6 months — regenerate before then.\n');
