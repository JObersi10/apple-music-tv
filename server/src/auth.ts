import { existsSync, readFileSync, writeFileSync } from "fs";

const STATE_FILE = "./auth-state.json";

interface AuthState {
  mut: string;
  bearerToken: string;
  mutSetAt: number;
}

let state: AuthState = { mut: "", bearerToken: "", mutSetAt: 0 };
let storefront = "us";

// Persist across restarts
if (existsSync(STATE_FILE)) {
  try { state = JSON.parse(readFileSync(STATE_FILE, "utf8")); } catch {}
}

function save() {
  try { writeFileSync(STATE_FILE, JSON.stringify(state)); } catch {}
}

export const setStorefront = (sf: string) => { storefront = sf; };
export const getStorefront = () => storefront;
export const setMUT = (token: string) => { state.mut = token; state.mutSetAt = Date.now(); save(); };
export const getMUT = () => state.mut;
export const hasMUT = () => state.mut.length > 0;
export const setBearerToken = (t: string) => { state.bearerToken = t; save(); };
export const getBearerToken = () => state.bearerToken;
export const getStatus = () => ({
  hasMUT: hasMUT(),
  mutSetAt: state.mutSetAt ? new Date(state.mutSetAt).toISOString() : null,
  hasBearer: state.bearerToken.length > 0,
});
