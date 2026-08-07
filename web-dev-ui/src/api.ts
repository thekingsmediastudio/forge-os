import type { ConnectionConfig, StatusResponse, ToolDefinition } from "./types";

// Browser-only HTTP client for the Forge OS on-device API (ForgeHttpServer).
// Speaks the same minimal HTTP/JSON dialect as forge-desktop's browser
// fallback: Bearer auth, JSON bodies, `Connection: close` on the server side.

interface RawResponse {
  status: number;
  body: string;
}

async function rawRequest(
  cfg: ConnectionConfig,
  method: string,
  path: string,
  body?: unknown,
  timeoutSecs = 30
): Promise<RawResponse> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutSecs * 1000);
  try {
    const res = await fetch(`http://${cfg.host}:${cfg.port}${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${cfg.token}`,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
    return { status: res.status, body: await res.text() };
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw new Error(`Request timed out after ${timeoutSecs}s`);
    }
    throw new Error(
      `Network error reaching http://${cfg.host}:${cfg.port} — is the server running and CORS-enabled?`
    );
  } finally {
    clearTimeout(timer);
  }
}

function parse<T>(r: RawResponse): T {
  if (r.status === 401) throw new Error("Unauthorized — check the API key");
  if (r.status >= 400) {
    try {
      const j = JSON.parse(r.body);
      throw new Error(j.error || `HTTP ${r.status}`);
    } catch (e) {
      if (e instanceof SyntaxError) {
        throw new Error(`HTTP ${r.status}: ${r.body.slice(0, 200)}`);
      }
      throw e;
    }
  }
  return JSON.parse(r.body) as T;
}

export async function checkStatus(cfg: ConnectionConfig): Promise<StatusResponse> {
  const r = await rawRequest(cfg, "GET", "/api/status", undefined, 10);
  return parse<StatusResponse>(r);
}

export async function listTools(cfg: ConnectionConfig): Promise<ToolDefinition[]> {
  const r = await rawRequest(cfg, "GET", "/api/tools", undefined, 15);
  const j = parse<{ tools: ToolDefinition[] }>(r);
  return j.tools;
}

export async function callTool(
  cfg: ConnectionConfig,
  name: string,
  args: Record<string, unknown>
): Promise<string> {
  const r = await rawRequest(cfg, "POST", "/api/tool", { name, args }, 120);
  const j = parse<{ ok: boolean; output: string; error?: string }>(r);
  if (!j.ok) throw new Error(j.error || j.output || "tool failed");
  return j.output;
}

export async function sendChat(
  cfg: ConnectionConfig,
  message: string,
  sessionId?: string
): Promise<{ reply: string; sessionId: string }> {
  const r = await rawRequest(
    cfg,
    "POST",
    "/api/chat",
    { message, session_id: sessionId },
    600 // agent turns can take minutes
  );
  const j = parse<{ ok: boolean; reply: string; session_id: string }>(r);
  return { reply: j.reply, sessionId: j.session_id };
}
