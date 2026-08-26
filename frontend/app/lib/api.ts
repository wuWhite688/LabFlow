type ApiError = { code?: string; message?: string };

export type AuthSession = {
  accessToken: string;
  expiresIn: number;
  user: { id: number; username: string; displayName: string; role: string };
};

export type LoginResult = AuthSession;

/** null = logged out; non-null = the in-memory access session changed. */
export type SessionListener = (session: AuthSession | null) => void;

const LEGACY_AUTH_KEYS = ["labflow.accessToken", "labflow.refreshToken", "labflow.auth"];

// login, logout and refresh all rewrite the same HttpOnly refresh cookie. Two of them
// overlapping - across tabs, or a fast logout-then-login - can interleave so the loser's
// Set-Cookie lands last and resurrects or destroys the wrong session. Web Locks serialize
// them per origin, which covers tabs as well as one tab racing itself.
const AUTH_COOKIE_LOCK = "labflow-refresh-token";

let authSession: AuthSession | null = null;
let authGeneration = 0;
let logoutInFlight: Promise<void> | null = null;
const sessionListeners = new Set<SessionListener>();

function hasBrowserLocks(): boolean {
  return typeof navigator !== "undefined" && !!navigator.locks;
}

function withAuthCookieLock<T>(operation: () => Promise<T>): Promise<T> {
  if (hasBrowserLocks()) {
    return navigator.locks.request(AUTH_COOKIE_LOCK, operation);
  }
  return operation();
}

export function subscribeSession(listener: SessionListener): () => void {
  sessionListeners.add(listener);
  return () => {
    sessionListeners.delete(listener);
  };
}

function notifySessionListeners(session: AuthSession | null) {
  for (const listener of sessionListeners) {
    try {
      listener(session);
    } catch {
      /* listener errors must not break auth flow */
    }
  }
}

export function clearLegacyAuthStorage() {
  if (typeof window === "undefined") return;
  for (const storageName of ["sessionStorage", "localStorage"] as const) {
    try {
      const storage = window[storageName];
      for (const key of LEGACY_AUTH_KEYS) {
        storage.removeItem(key);
      }
    } catch {
      /* storage can be unavailable in privacy-restricted browsers */
    }
  }
}

export function setAuthSession(session: AuthSession) {
  invalidatePendingRefresh();
  authSession = session;
  clearLegacyAuthStorage();
  notifySessionListeners(session);
}

export function clearAuthSession() {
  invalidatePendingRefresh();
  authSession = null;
  clearLegacyAuthStorage();
  notifySessionListeners(null);
}

export async function loginRequest(username: string, password: string): Promise<LoginResult> {
  // A logout still on the wire would clear the cookie this login is about to set.
  if (logoutInFlight) await logoutInFlight;
  return withAuthCookieLock(async () => {
    const response = await fetch("/api/backend/api/auth/login", {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
      credentials: "same-origin",
      cache: "no-store",
    });
    if (!response.ok) {
      let detail: ApiError = {};
      try { detail = await response.json() as ApiError; } catch { /* empty */ }
      throw new Error(detail.message || `登录失败（${response.status}）`);
    }
    return response.json() as Promise<LoginResult>;
  });
}

export async function logoutRequest(): Promise<void> {
  // Concurrent logouts are the same intent; collapse them onto one request.
  if (logoutInFlight) return logoutInFlight;
  const request = (async () => {
    // Without Web Locks there is nothing to serialize against, so wait out a refresh
    // that already started - its Set-Cookie would otherwise arrive after this logout.
    if (!hasBrowserLocks() && refreshSettled) await refreshSettled;
    await withAuthCookieLock(() => fetch("/api/backend/api/auth/logout", {
      method: "POST",
      headers: { Accept: "application/json" },
      credentials: "same-origin",
      cache: "no-store",
    }).then(() => undefined));
  })().catch(() => { /* best-effort */ });
  logoutInFlight = request;
  try {
    await request;
  } finally {
    if (logoutInFlight === request) logoutInFlight = null;
  }
}

type RefreshOperation = {
  generation: number;
  controller: AbortController;
  promise: Promise<AuthSession | null>;
};

let refreshInFlight: RefreshOperation | null = null;
// Settles when the current refresh finishes, however it finishes. Only the
// no-Web-Locks fallback path in logoutRequest waits on it.
let refreshSettled: Promise<void> | null = null;

function invalidatePendingRefresh() {
  authGeneration += 1;
  const pending = refreshInFlight;
  refreshInFlight = null;
  pending?.controller.abort();
}

async function performRefresh(generation: number, signal: AbortSignal): Promise<AuthSession | null> {
  return withAuthCookieLock(async () => {
    try {
      const response = await fetch("/api/backend/api/auth/refresh", {
        method: "POST",
        headers: { Accept: "application/json" },
        credentials: "same-origin",
        cache: "no-store",
        signal,
      });
      if (response.status === 204 || !response.ok) {
        if (generation === authGeneration) {
          clearAuthSession();
        }
        return null;
      }
      const session = await response.json() as AuthSession;
      if (generation !== authGeneration) return null;
      setAuthSession(session);
      return session;
    } catch {
      if (signal.aborted || generation !== authGeneration) {
        return null;
      }
      return null;
    }
  });
}

export function refreshSession(): Promise<AuthSession | null> {
  // A refresh started after logout would hand back a session the user just ended.
  if (logoutInFlight) return logoutInFlight.then(() => null);
  const generation = authGeneration;
  if (refreshInFlight?.generation === generation) {
    return refreshInFlight.promise;
  }

  const controller = new AbortController();
  const promise = performRefresh(generation, controller.signal);
  const operation = { generation, controller, promise };
  refreshInFlight = operation;
  const settled = promise.then(() => undefined, () => undefined);
  refreshSettled = settled;
  void settled.finally(() => {
    if (refreshSettled === settled) refreshSettled = null;
  });
  void promise.finally(() => {
    if (refreshInFlight === operation) {
      refreshInFlight = null;
    }
  });
  return promise;
}

export async function api<T>(path: string, accessToken: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${accessToken}`);
  headers.set("Accept", "application/json");
  if (init?.body) headers.set("Content-Type", "application/json");

  let response = await fetch(`/api/backend${path}`, { ...init, headers, cache: "no-store" });

  if (response.status === 401) {
    const currentAccessToken = authSession?.accessToken;
    const refreshed = currentAccessToken && currentAccessToken !== accessToken
      ? authSession
      : await refreshSession();
    if (refreshed?.accessToken) {
      headers.set("Authorization", `Bearer ${refreshed.accessToken}`);
      response = await fetch(`/api/backend${path}`, { ...init, headers, cache: "no-store" });
    }
  }

  if (!response.ok) {
    let detail: ApiError = {};
    try { detail = await response.json() as ApiError; } catch { /* empty response */ }
    throw new Error(detail.message || `请求失败（${response.status}）`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}
