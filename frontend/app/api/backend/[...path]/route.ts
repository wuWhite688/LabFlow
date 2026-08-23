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

async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  if (!path.length || path.some((part) => part === ".." || part.includes("/"))) {
    return Response.json({ code: "INVALID_PROXY_PATH", message: "请求路径无效" }, { status: 400 });
  }

  const incomingUrl = new URL(request.url);
  const targetUrl = new URL(`/${path.join("/")}${incomingUrl.search}`, BACKEND_BASE_URL);
  const headers = new Headers();
  for (const name of ["authorization", "content-type", "accept", "cookie"]) {
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
