type ApiError = { code?: string; message?: string };

export type AuthTokens = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
};

export type LoginResult = AuthTokens & {
  tokenType: string;
  user: { id: number; username: string; displayName: string; role: string };
};

/** null = logged out / tokens cleared; non-null = storage was updated (login or refresh). */
export type TokenListener = (tokens: AuthTokens | null) => void;

const ACCESS_KEY = "labflow.accessToken";
const REFRESH_KEY = "labflow.refreshToken";

const tokenListeners = new Set<TokenListener>();

export function subscribeTokens(listener: TokenListener): () => void {
  tokenListeners.add(listener);
  return () => {
    tokenListeners.delete(listener);
  };
}

function notifyTokenListeners(tokens: AuthTokens | null) {
  for (const listener of tokenListeners) {
    try {
      listener(tokens);
    } catch {
      /* listener errors must not break auth flow */
    }
  }
}

export function loadTokens(): AuthTokens | null {
  if (typeof sessionStorage === "undefined") return null;
  const accessToken = sessionStorage.getItem(ACCESS_KEY);
  const refreshToken = sessionStorage.getItem(REFRESH_KEY);
  if (!accessToken || !refreshToken) return null;
  return { accessToken, refreshToken, expiresIn: 0 };
}

export function saveTokens(tokens: AuthTokens) {
  sessionStorage.setItem(ACCESS_KEY, tokens.accessToken);
  sessionStorage.setItem(REFRESH_KEY, tokens.refreshToken);
  notifyTokenListeners(tokens);
}

export function clearTokens() {
  if (typeof sessionStorage !== "undefined") {
    sessionStorage.removeItem(ACCESS_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    sessionStorage.removeItem("labflow.auth"); // legacy Basic Auth cleanup
  }
  notifyTokenListeners(null);
}

export async function loginRequest(username: string, password: string): Promise<LoginResult> {
  const response = await fetch("/api/backend/api/auth/login", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    cache: "no-store",
  });
  if (!response.ok) {
    let detail: ApiError = {};
    try { detail = await response.json() as ApiError; } catch { /* empty */ }
    throw new Error(detail.message || `登录失败（${response.status}）`);
  }
  return response.json() as Promise<LoginResult>;
}

export async function logoutRequest(refreshToken: string): Promise<void> {
  await fetch("/api/backend/api/auth/logout", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  }).catch(() => { /* best-effort */ });
}

let refreshInFlight: Promise<AuthTokens | null> | null = null;

async function refreshTokens(refreshToken: string): Promise<AuthTokens | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await fetch("/api/backend/api/auth/refresh", {
          method: "POST",
          headers: { Accept: "application/json", "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken }),
          cache: "no-store",
        });
        if (!response.ok) {
          clearTokens();
          return null;
        }
        const body = await response.json() as LoginResult;
        const next = { accessToken: body.accessToken, refreshToken: body.refreshToken, expiresIn: body.expiresIn };
        saveTokens(next); // notifies React to sync accessToken in memory
        return next;
      } catch {
        clearTokens();
        return null;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

export async function api<T>(path: string, accessToken: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${accessToken}`);
  headers.set("Accept", "application/json");
  if (init?.body) headers.set("Content-Type", "application/json");

  let response = await fetch(`/api/backend${path}`, { ...init, headers, cache: "no-store" });

  if (response.status === 401) {
    const stored = loadTokens();
    if (stored?.refreshToken) {
      const refreshed = await refreshTokens(stored.refreshToken);
      if (refreshed?.accessToken) {
        headers.set("Authorization", `Bearer ${refreshed.accessToken}`);
        response = await fetch(`/api/backend${path}`, { ...init, headers, cache: "no-store" });
      }
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
