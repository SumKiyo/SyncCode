/**
 * SyncCode - Cloudflare Worker (sms-worker)
 * 跨端验证码极速同步系统 - 云端中转站
 *
 * 功能：
 *   POST /api/push  - 接收 Android 端上报的验证码（存入 Upstash Redis）
 *   GET  /api/pull  - PC 端拉取验证码（阅后即焚，返回后立即删除）
 *
 * 鉴权：所有 API 请求必须在 Header 中携带 Authorization: Bearer <API_SECRET>
 *
 * 存储：使用 Upstash Redis（REST API），全局读写，解决边缘节点数据孤岛问题
 *
 * 安全：所有敏感配置通过 Wrangler env/secrets 注入，不硬编码
 */

// ============================================================
// 类型定义
// ============================================================
interface Env {
  API_SECRET: string;
  UPSTASH_URL: string;
  UPSTASH_TOKEN: string;
}

// ============================================================
// 配置常量（非敏感项）
// ============================================================
const REDIS_KEY = "latest_code";
const CODE_TTL = 120; // 验证码过期时间（秒），2 分钟后自动删除

// ============================================================
// 辅助函数
// ============================================================

/**
 * 验证请求的 Authorization Header
 */
function isAuthorized(request: Request, apiSecret: string): boolean {
  const authHeader = request.headers.get("Authorization");
  if (!authHeader) return false;
  const parts = authHeader.split(" ");
  if (parts.length !== 2) return false;
  if (parts[0].toLowerCase() !== "bearer") return false;
  return parts[1] === apiSecret;
}

/**
 * 返回 JSON 响应
 */
function jsonResponse(data: object, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    },
  });
}

/**
 * 返回未授权响应
 */
function unauthorizedResponse(): Response {
  return jsonResponse({ status: "error", msg: "Unauthorized" }, 401);
}

/**
 * 调用 Upstash Redis REST API
 * 文档：https://upstash.com/docs/redis/overview/restapi
 */
async function redisCommand(
  upstashUrl: string,
  upstashToken: string,
  path: string,
  options: { method?: string; body?: string } = {}
): Promise<any> {
  const resp = await fetch(`${upstashUrl}${path}`, {
    method: options.method || "GET",
    headers: {
      Authorization: `Bearer ${upstashToken}`,
      ...(options.body ? { "Content-Type": "text/plain" } : {}),
    },
    body: options.body || undefined,
  });

  if (!resp.ok) {
    console.error(`[Redis] HTTP ${resp.status} on ${path}`);
    return null;
  }

  const data = await resp.json<{ result: string | null }>();
  return data.result;
}

// ============================================================
// 路由处理
// ============================================================

/**
 * POST /api/push
 * Android 端上报验证码 → SETEX 写入 Redis（带 2 分钟过期）
 */
async function handlePush(request: Request, env: Env): Promise<Response> {
  if (!isAuthorized(request, env.API_SECRET)) {
    return unauthorizedResponse();
  }

  let body: { app?: string; code?: string; raw_text?: string };
  try {
    body = await request.json();
  } catch {
    return jsonResponse({ status: "error", msg: "Invalid JSON body" }, 400);
  }

  if (!body.app || !body.code || !body.raw_text) {
    return jsonResponse(
      { status: "error", msg: "Missing required fields: app, code, raw_text" },
      400
    );
  }

  // 构造 JSON 写入 Redis
  const value = JSON.stringify({
    app: body.app,
    code: body.code,
    raw_text: body.raw_text,
  });

  // SETEX latest_code 120 <json_value>
  const result = await redisCommand(
    env.UPSTASH_URL, env.UPSTASH_TOKEN,
    `/setex/${REDIS_KEY}/${CODE_TTL}`,
    { method: "POST", body: value }
  );

  if (result === null) {
    return jsonResponse({ status: "error", msg: "Redis write failed" }, 500);
  }

  console.log(`[Push] Stored code from "${body.app}": ${body.code}`);

  return jsonResponse({ status: "success", msg: "Code stored" });
}

/**
 * GET /api/pull
 * PC 端拉取并消费验证码（阅后即焚：GET 后立即 DEL）
 */
async function handlePull(request: Request, env: Env): Promise<Response> {
  if (!isAuthorized(request, env.API_SECRET)) {
    return unauthorizedResponse();
  }

  // 1. GET 读取 Redis
  const raw = await redisCommand(
    env.UPSTASH_URL, env.UPSTASH_TOKEN,
    `/get/${REDIS_KEY}`
  );

  if (raw === null || raw === undefined) {
    return jsonResponse({ has_data: false });
  }

  // 2. DEL 立即删除（阅后即焚）
  await redisCommand(
    env.UPSTASH_URL, env.UPSTASH_TOKEN,
    `/del/${REDIS_KEY}`,
    { method: "POST" }
  );

  // 3. 解析 JSON 并返回
  let data: { app: string; code: string; raw_text: string };
  try {
    data = JSON.parse(raw as string);
  } catch {
    console.error(`[Pull] Failed to parse Redis value: ${raw}`);
    return jsonResponse({ has_data: false });
  }

  console.log(`[Pull] Delivered code from "${data.app}": ${data.code}`);

  return jsonResponse({
    has_data: true,
    data: {
      app: data.app,
      code: data.code,
      raw_text: data.raw_text,
    },
  });
}

// ============================================================
// Worker 入口
// ============================================================
export default {
  async fetch(request, env, ctx): Promise<Response> {
    // 处理 CORS 预检请求
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, Authorization",
        },
      });
    }

    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method.toUpperCase();

    // 路由分发
    if (path === "/api/push" && method === "POST") {
      return handlePush(request, env);
    }

    if (path === "/api/pull" && method === "GET") {
      return handlePull(request, env);
    }

    // 根路径：返回基本状态信息
    if (path === "/") {
      return jsonResponse({
        service: "SyncCode SMS Worker",
        version: "1.0",
        storage: "Upstash Redis",
        status: "running",
      });
    }

    // 未匹配的路由
    return jsonResponse({ status: "error", msg: "Not Found" }, 404);
  },
} satisfies ExportedHandler<Env>;