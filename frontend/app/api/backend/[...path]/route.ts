const BACKEND_BASE_URL = process.env.BACKEND_BASE_URL ?? "http://127.0.0.1:18080";

const BFF_CLIENT_IP_HEADER = "x-bff-client-ip";
const CLIENT_IP_SOURCE_HEADERS = [
  "cf-connecting-ip",
  "x-vercel-forwarded-for",
  "x-forwarded-for",
  "x-real-ip",
] as const;

export function clientIpFromHeaders(headers: Headers): string | null {
  for (const name of CLIENT_IP_SOURCE_HEADERS) {
    const value = headers.get(name);
    if (!value) continue;

    const candidate = value.split(",", 1)[0].trim();
    const unwrapped = candidate.startsWith("[") && candidate.endsWith("]")
      ? candidate.slice(1, -1)
      : candidate;
    if (unwrapped.length > 0 && unwrapped.length <= 45 && /^[0-9a-f:.]+$/i.test(unwrapped)) {
      return unwrapped;
    }
  }
  return null;
}

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * CSRF 防护：写请求必须来自本站页面。
 *
 * refresh cookie 由浏览器自动携带。SameSite=Lax 按「站点」判断，兄弟子域
 * （same-site 但不同源）发起的 POST 仍会带上它，所以只放行 same-origin。
 * Sec-Fetch-Site 由浏览器填写，页面脚本改不了；none 是用户直接输入地址或书签。
 * 老浏览器没有它时退回 Origin：比较的是完整 origin（协议、主机、端口），
 * http://app 发往 https://app 不算同源。可信来源取 TRUSTED_ORIGINS（逗号分隔，
 * 与后端共用）；未配置时取本次请求自身的 origin。部署在终止 TLS 的反向代理后面时，
 * BFF 看到的是内网地址，必须用 TRUSTED_ORIGINS 写明对外 origin。
 * 两个头都没有说明不是浏览器（脚本、健康检查），不构成 CSRF，放行。
 * GET/HEAD 不改状态，不拦。
 */
export function isCrossSiteWrite(request: Request): boolean {
  if (!UNSAFE_METHODS.has(request.method.toUpperCase())) return false;

  const site = request.headers.get("sec-fetch-site");
  if (site) return site !== "same-origin" && site !== "none";

  const origin = request.headers.get("origin");
  if (!origin) return false;
  const presented = normalizeOrigin(origin);
  return presented === null || !trustedOrigins(request).has(presented);
}

function trustedOrigins(request: Request): Set<string> {
  const configured = (process.env.TRUSTED_ORIGINS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const candidates = configured.length > 0 ? configured : [request.url];
  return new Set(candidates.map(normalizeOrigin).filter((value): value is string => value !== null));
}

/** 按浏览器序列化 Origin 的方式规范化：小写、省略默认端口。"null" 与非法值返回 null。 */
function normalizeOrigin(value: string): string | null {
  if (value === "null") return null;
  try {
    const origin = new URL(value).origin;
    return origin === "null" ? null : origin;
  } catch {
    return null;
  }
}

async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  if (isCrossSiteWrite(request)) {
    return Response.json(
      { code: "CROSS_SITE_REQUEST_BLOCKED", message: "跨站请求已被拒绝，请从本站页面发起操作" },
      { status: 403 },
    );
  }
  const { path } = await context.params;
  if (!path.length || path.some((part) => part === ".." || part.includes("/"))) {
    return Response.json({ code: "INVALID_PROXY_PATH", message: "请求路径无效" }, { status: 400 });
  }

  const incomingUrl = new URL(request.url);
  const targetUrl = new URL(`/${path.join("/")}${incomingUrl.search}`, BACKEND_BASE_URL);
  const headers = new Headers();
  for (const name of ["authorization", "content-type", "accept", "cookie", "origin", "sec-fetch-site"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const clientIp = clientIpFromHeaders(request.headers);
  if (clientIp) headers.set(BFF_CLIENT_IP_HEADER, clientIp);

  try {
    const response = await fetch(targetUrl, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
      cache: "no-store",
    });
    const responseHeaders = new Headers({
      "content-type": response.headers.get("content-type") ?? "application/json; charset=utf-8",
    });
    const setCookie = response.headers.get("set-cookie");
    if (setCookie) {
      responseHeaders.set(
        "set-cookie",
        setCookie.replace(/Path=\/api\/auth(?=;|$)/i, "Path=/api/backend/api/auth"),
      );
    }
    return new Response(response.body, {
      status: response.status,
      headers: responseHeaders,
    });
  } catch {
    return Response.json(
      { code: "BACKEND_UNAVAILABLE", message: "后端服务暂时不可用，请确认 Spring Boot 已启动" },
      { status: 502 },
    );
  }
}

export const GET = proxy;
export const POST = proxy;
export const PATCH = proxy;
export const PUT = proxy;
export const DELETE = proxy;
