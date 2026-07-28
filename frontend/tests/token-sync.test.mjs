import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";

/**
 * In-memory mirror of app/lib/api.ts token storage + listener semantics.
 * Keeps React-facing contract testable without a browser DOM.
 */
function createTokenStore() {
  const store = new Map();
  const sessionStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  const ACCESS_KEY = "labflow.accessToken";
  const REFRESH_KEY = "labflow.refreshToken";
  const listeners = new Set();

  function notify(tokens) {
    for (const l of listeners) l(tokens);
  }

  return {
    subscribeTokens(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    saveTokens(tokens) {
      sessionStorage.setItem(ACCESS_KEY, tokens.accessToken);
      sessionStorage.setItem(REFRESH_KEY, tokens.refreshToken);
      notify(tokens);
    },
    clearTokens() {
      sessionStorage.removeItem(ACCESS_KEY);
      sessionStorage.removeItem(REFRESH_KEY);
      notify(null);
    },
    loadTokens() {
      const accessToken = sessionStorage.getItem(ACCESS_KEY);
      const refreshToken = sessionStorage.getItem(REFRESH_KEY);
      if (!accessToken || !refreshToken) return null;
      return { accessToken, refreshToken, expiresIn: 0 };
    },
  };
}

test("token listeners receive save and clear so React memory can stay in sync", () => {
  const api = createTokenStore();
  /** @type {Array<any>} */
  const events = [];
  const unsub = api.subscribeTokens((t) => events.push(t));

  api.saveTokens({ accessToken: "access-1", refreshToken: "refresh-1", expiresIn: 900 });
  assert.equal(api.loadTokens()?.accessToken, "access-1");
  assert.equal(events.at(-1)?.accessToken, "access-1");

  // Silent refresh rotation updates both storage and listeners
  api.saveTokens({ accessToken: "access-2", refreshToken: "refresh-2", expiresIn: 900 });
  assert.equal(events.at(-1)?.accessToken, "access-2");
  assert.equal(api.loadTokens()?.accessToken, "access-2");

  // Refresh failure clears login state for all subscribers
  api.clearTokens();
  assert.equal(events.at(-1), null);
  assert.equal(api.loadTokens(), null);

  unsub();
  api.saveTokens({ accessToken: "access-3", refreshToken: "refresh-3", expiresIn: 900 });
  assert.equal(events.filter((e) => e?.accessToken === "access-3").length, 0);
});

test("api.ts and page.tsx wire subscribeTokens for post-refresh sync and logout", async () => {
  const apiSrc = await readFile(new URL("../app/lib/api.ts", import.meta.url), "utf8");
  const pageSrc = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");

  assert.match(apiSrc, /export function subscribeTokens/);
  assert.match(apiSrc, /notifyTokenListeners/);
  assert.match(apiSrc, /notifyTokenListeners\(tokens\)/);
  assert.match(apiSrc, /notifyTokenListeners\(null\)/);
  assert.match(apiSrc, /saveTokens\(next\)/);
  assert.match(apiSrc, /clearTokens\(\)/);

  assert.match(pageSrc, /subscribeTokens/);
  assert.match(pageSrc, /setAccessToken\(tokens\.accessToken\)/);
  assert.match(pageSrc, /setUser\(null\)/);
});
