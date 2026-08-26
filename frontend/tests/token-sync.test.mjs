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

async function loadProxyModule() {
  const source = await readFile(new URL("../app/api/backend/[...path]/route.ts", import.meta.url), "utf8");
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

test("a network error during refresh does not clear an existing session", async () => {
  const api = await loadAuthModule();
  const events = [];
  const originalFetch = globalThis.fetch;
  api.subscribeSession((session) => events.push(session?.accessToken ?? null));
  api.setAuthSession({
    accessToken: "keep-me",
    expiresIn: 900,
    user: { id: 1, username: "student", displayName: "Student", role: "STUDENT" },
  });
  globalThis.fetch = () => Promise.reject(new TypeError("network down"));

  try {
    assert.equal(await api.refreshSession(), null);
    assert.equal(events.at(-1), "keep-me");
    assert.equal(events.filter((token) => token === null).length, 0);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("logout waits for an already-started refresh before clearing the cookie", async () => {
  const api = await loadAuthModule();
  const refreshResponse = deferred();
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = (url, init) => {
    calls.push(String(url));
    if (String(url).endsWith("/api/auth/refresh")) {
      return refreshResponse.promise;
    }
    assert.equal(init.credentials, "same-origin");
    return Promise.resolve(new Response(null, { status: 204 }));
  };

  try {
    const refresh = api.refreshSession();
    api.clearAuthSession();
    const logout = api.logoutRequest();
    await Promise.resolve();
    assert.deepEqual(calls, ["/api/backend/api/auth/refresh"]);

    refreshResponse.resolve(Response.json({
      accessToken: "stale-refresh",
      expiresIn: 900,
      user: { id: 1, username: "student", displayName: "Student", role: "STUDENT" },
    }));
    assert.equal(await refresh, null);
    await logout;
    assert.deepEqual(calls, [
      "/api/backend/api/auth/refresh",
      "/api/backend/api/auth/logout",
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("a quick new login waits until the old logout response finishes", async () => {
  const api = await loadAuthModule();
  const logoutResponse = deferred();
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = (url) => {
    calls.push(String(url));
    if (String(url).endsWith("/api/auth/logout")) return logoutResponse.promise;
    return Promise.resolve(Response.json({
      accessToken: "new-login",
      expiresIn: 900,
      user: { id: 2, username: "teacher", displayName: "Teacher", role: "TEACHER" },
    }));
  };

  try {
    const logout = api.logoutRequest();
    const login = api.loginRequest("teacher", "teacher123");
    await Promise.resolve();
    assert.deepEqual(calls, ["/api/backend/api/auth/logout"]);

    logoutResponse.resolve(new Response(null, { status: 204 }));
    await logout;
    assert.equal((await login).accessToken, "new-login");
    assert.deepEqual(calls, [
      "/api/backend/api/auth/logout",
      "/api/backend/api/auth/login",
    ]);
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

test("backend proxy replaces caller-supplied client identity with the edge address", async () => {
  const proxy = await loadProxyModule();
  const originalFetch = globalThis.fetch;
  let forwardedHeaders;
  globalThis.fetch = async (_url, init) => {
    forwardedHeaders = new Headers(init.headers);
    return new Response("{}", { status: 200, headers: { "content-type": "application/json" } });
  };

  try {
    const request = new Request("http://localhost/api/backend/api/auth/login", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.42",
        "x-forwarded-for": "198.51.100.8, 198.51.100.9",
        "x-bff-client-ip": "192.0.2.99",
      },
      body: "{}",
    });
    await proxy.POST(request, { params: Promise.resolve({ path: ["api", "auth", "login"] }) });

    assert.equal(proxy.clientIpFromHeaders(request.headers), "203.0.113.42");
    assert.equal(forwardedHeaders.get("x-bff-client-ip"), "203.0.113.42");
    assert.equal(forwardedHeaders.has("cf-connecting-ip"), false);
    assert.equal(forwardedHeaders.has("x-forwarded-for"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
