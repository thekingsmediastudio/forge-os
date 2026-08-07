import type { ChatMessage, StatusResponse, ToolDefinition } from "../types";

// In-browser mock of the Forge OS on-device API. Returns byte-compatible
// responses with ForgeHttpServer / mock_server.py so the whole UI is usable
// with no server running, and tool definitions can be edited live for the
// ~1s iterate loop (edit JSON -> Tools view updates immediately).

export interface MockState {
  tools: ToolDefinition[];
}

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

function jitter(base: number): number {
  return base + Math.floor(Math.random() * base);
}

export async function status(): Promise<StatusResponse> {
  await delay(jitter(60));
  return { status: "ok", port: 0, running: true, server: "Forge OS HTTP (in-browser mock)" };
}

export async function listTools(state: MockState): Promise<ToolDefinition[]> {
  await delay(jitter(80));
  return state.tools;
}

export async function callTool(
  state: MockState,
  name: string,
  args: Record<string, unknown>
): Promise<{ ok: boolean; output: string }> {
  await delay(jitter(200));
  const def = state.tools.find((t) => t.function.name === name);
  if (!def) {
    return { ok: false, output: `unknown tool '${name}'` };
  }

  // A couple of realistic canned outputs; everything else echoes like the
  // python mock_server does.
  if (name === "android_battery") {
    return {
      ok: true,
      output: JSON.stringify({ level: 87, charging: false, health: "good" }, null, 2),
    };
  }
  if (name === "ddg_search") {
    const q = String(args.query ?? "");
    return {
      ok: true,
      output: JSON.stringify(
        {
          query: q,
          results: [
            { title: `${q} — result 1`, url: "https://example.org/1" },
            { title: `${q} — result 2`, url: "https://example.org/2" },
          ],
        },
        null,
        2
      ),
    };
  }
  return { ok: true, output: `[mock] ${name} executed with args: ${JSON.stringify(args)}` };
}

const KEYWORD_TOOLS: Array<{ match: RegExp; tool: string }> = [
  { match: /alarm|remind/i, tool: "alarm_set" },
  { match: /battery/i, tool: "android_battery" },
  { match: /search|look ?up/i, tool: "ddg_search" },
  { match: /read|file/i, tool: "file_read" },
];

export async function chat(
  state: MockState,
  _history: ChatMessage[],
  message: string
): Promise<string> {
  await delay(jitter(300));
  const hit = KEYWORD_TOOLS.find((k) => k.match.test(message));
  const lines: string[] = [`[mock reply] You said: ${message}`];
  if (hit && state.tools.some((t) => t.function.name === hit.tool)) {
    // Mirror ForgeHttpServer.runChatTurn: tool calls fold into the reply as
    // "⚙ <tool>" markers on their own line.
    lines.push("", `⚙ ${hit.tool}`);
  }
  lines.push(
    "",
    "This is a simulated agent response from the in-browser mock — the real reply comes from ReActAgent on your device."
  );
  return lines.join("\n");
}
