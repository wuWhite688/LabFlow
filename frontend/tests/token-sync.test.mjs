import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import ts from "typescript";

async function authSources() {
  return Promise.all([
    readFile(new URL("../app/lib/api.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/api/backend/[...path]/route.ts", import.meta.url), "utf8"),
  ]);
}

async function loadAuthModule() {
  const source = await readFile(new URL("../app/lib/api.ts", import.meta.url), "utf8");
  const output = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const encoded = Buffer.from(output).toString("base64");
  return import(`data:text/javascript;base64,${encoded}#${Date.now()}-${Math.random()}`);
}

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

test("access tokens stay in memory and legacy browser storage is only cleared", async () => {
  const [apiSrc, pageSrc] = await authSources();

  assert.match(apiSrc, /let authSession: AuthSession \| null = null/);
  assert.match(apiSrc, /export function setAuthSession/);
  assert.match(apiSrc, /export function clearAuthSession/);
  assert.match(apiSrc, /storage\.removeItem\(key\)/);
  assert.doesNotMatch(apiSrc, /(?:sessionStorage|localStorage)\.(?:getItem|setItem)\(/);
  assert.doesNotMatch(apiSrc, /refreshToken\s*:/);
  assert.doesNotMatch(pageSrc, /(?:sessionStorage|localStorage|loadTokens|saveTokens)/);
  assert.doesNotMatch(pageSrc, /\.refreshToken\b/);
});

test("cookie-backed auth uses bodyless same-origin refresh and logout requests", async () => {
  const [apiSrc, pageSrc] = await authSources();
  const refreshBlock = apiSrc.match(
    /async function performRefresh[\s\S]*?\n\}/,
  )?.[0];
  const logoutBlock = apiSrc.match(
    /export async function logoutRequest[\s\S]*?\n\}/,
  )?.[0];

  assert.ok(refreshBlock, "refreshSession implementation not found");
  assert.ok(logoutBlock, "logoutRequest implementation not found");
  assert.match(refreshBlock, /credentials: "same-origin"/);
  assert.match(logoutBlock, /credentials: "same-origin"/);
  assert.doesNotMatch(refreshBlock, /\bbody:/);
  assert.doesNotMatch(logoutBlock, /\bbody:/);
  assert.match(apiSrc, /let refreshInFlight: RefreshOperation \| null = null/);
  assert.match(pageSrc, /clearLegacyAuthStorage\(\);\s+void refreshSession\(\)/);
  assert.match(pageSrc, /clearAuthSession\(\);\s+await logoutRequest\(\)/);
});

test("a late bootstrap refresh cannot clear a newer login session", async () => {
  for (const status of [204, 401]) {
    const api = await loadAuthModule();
    const response = deferred();
    const events = [];
    const originalFetch = globalThis.fetch;
    let refreshSignal;
    globalThis.fetch = (_url, init) => {
      refreshSignal = init.signal;
      return response.promise;
    };
    api.subscribeSession((session) => events.push(session?.accessToken ?? null));

    try {
      const staleRefresh = api.refreshSession();
      api.setAuthSession({
        accessToken: `login-wins-${status}`,
        expiresIn: 900,
        user: { id: 1, username: "student", displayName: "Student", role: "STUDENT" },
      });
      assert.equal(refreshSignal.aborted, true);
      response.resolve(new Response(null, { status }));

      assert.equal(await staleRefresh, null);
      assert.equal(events.at(-1), `login-wins-${status}`);
      assert.doesNotMatch(events.slice(1).join(","), /null/);
    } finally {
      globalThis.fetch = originalFetch;
    }
  }
});

test("logout invalidates an in-flight refresh before its response can restore auth", async () => {
  const api = await loadAuthModule();
  const response = deferred();
  const events = [];
  const originalFetch = globalThis.fetch;
  let refreshSignal;
  globalThis.fetch = (_url, init) => {
    refreshSignal = init.signal;
    return response.promise;
  };
  api.subscribeSession((session) => events.push(session?.accessToken ?? null));

  try {
    api.setAuthSession({
      accessToken: "before-logout",
      expiresIn: 900,
      user: { id: 1, username: "student", displayName: "Student", role: "STUDENT" },
    });
    const staleRefresh = api.refreshSession();
    api.clearAuthSession();
    assert.equal(refreshSignal.aborted, true);
    response.resolve(Response.json({
      accessToken: "must-not-return",
      expiresIn: 900,
      user: { id: 1, username: "student", displayName: "Student", role: "STUDENT" },
    }));

    assert.equal(await staleRefresh, null);
    assert.equal(events.at(-1), null);
    assert.doesNotMatch(events.join(","), /must-not-return/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("parallel refresh calls in one auth generation still share one request", async () => {
  const api = await loadAuthModule();
  const response = deferred();
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = (_url, init) => {
    calls += 1;
    assert.equal(init.credentials, "same-origin");
    assert.equal(init.body, undefined);
    return response.promise;
  };

  try {
    const first = api.refreshSession();
    const second = api.refreshSession();
    assert.equal(calls, 1);
    response.resolve(Response.json({
      accessToken: "one-refresh",
      expiresIn: 900,
      user: { id: 1, username: "student", displayName: "Student", role: "STUDENT" },
    }));

    assert.equal((await first)?.accessToken, "one-refresh");
    assert.equal((await second)?.accessToken, "one-refresh");
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("backend proxy forwards the cookie and rewrites the refresh cookie path", async () => {
  const [, , proxySrc] = await authSources();

  assert.match(proxySrc, /\["authorization", "content-type", "accept", "cookie"\]/);
  assert.match(proxySrc, /response\.headers\.get\("set-cookie"\)/);
  assert.match(
    proxySrc,
    /setCookie\.replace\(\/Path=\\\/api\\\/auth\(\?=;\|\$\)\/i, "Path=\/api\/backend\/api\/auth"\)/,
  );
});
