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

let authSession: AuthSession | null = null;
let authGeneration = 0;
const sessionListeners = new Set<SessionListener>();

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
}

export async function logoutRequest(): Promise<void> {
  await fetch("/api/backend/api/auth/logout", {
    method: "POST",
    headers: { Accept: "application/json" },
    credentials: "same-origin",
    cache: "no-store",
  }).catch(() => { /* best-effort */ });
}

type RefreshOperation = {
  generation: number;
  controller: AbortController;
  promise: Promise<AuthSession | null>;
};

let refreshInFlight: RefreshOperation | null = null;

function invalidatePendingRefresh() {
  authGeneration += 1;
  const pending = refreshInFlight;
  refreshInFlight = null;
  pending?.controller.abort();
}

async function performRefresh(generation: number, signal: AbortSignal): Promise<AuthSession | null> {
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
    if (generation === authGeneration) {
      clearAuthSession();
    }
    return null;
  }
}

export function refreshSession(): Promise<AuthSession | null> {
  const generation = authGeneration;
  if (refreshInFlight?.generation === generation) {
    return refreshInFlight.promise;
  }

  const controller = new AbortController();
  const promise = performRefresh(generation, controller.signal);
  const operation = { generation, controller, promise };
  refreshInFlight = operation;
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
