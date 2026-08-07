import * as api from "./api";
import * as mock from "./mock/mockService";
import type { ChatMessage, StatusResponse, ToolDefinition } from "./types";
import { connectionStore, setConnection } from "./store/connection";
import { addHistory } from "./store/history";
import { toolsStore } from "./store/tools";

// Service facade: routes every operation to the live device server or the
// in-browser mock depending on connection mode, and records execution history.

function mode(): "live" | "mock" {
  return connectionStore.get().mode;
}

function mockState(): mock.MockState {
  return { tools: toolsStore.get() };
}

export async function connect(): Promise<void> {
  const { mode: m, cfg } = connectionStore.get();
  setConnection({ status: "connecting", error: null });
  try {
    if (m === "live") {
      await api.checkStatus(cfg);
    } else {
      await mock.status();
    }
    setConnection({ status: "connected", error: null });
  } catch (e) {
    setConnection({ status: "error", error: (e as Error).message });
    throw e;
  }
}

export function disconnect(): void {
  setConnection({ status: "disconnected", error: null });
}

export async function getStatus(): Promise<StatusResponse> {
  return mode() === "live" ? api.checkStatus(connectionStore.get().cfg) : mock.status();
}

export async function getTools(): Promise<ToolDefinition[]> {
  if (mode() === "live") {
    const tools = await api.listTools(connectionStore.get().cfg);
    toolsStore.set(tools); // keep ToolEditor in sync with the live surface
    return tools;
  }
  return mock.listTools(mockState());
}

export async function runTool(name: string, args: Record<string, unknown>): Promise<string> {
  const started = performance.now();
  try {
    let output: string;
    if (mode() === "live") {
      output = await api.callTool(connectionStore.get().cfg, name, args);
    } else {
      const r = await mock.callTool(mockState(), name, args);
      if (!r.ok) throw new Error(r.output);
      output = r.output;
    }
    addHistory({
      kind: "tool",
      label: name,
      input: args,
      output,
      ok: true,
      durationMs: Math.round(performance.now() - started),
    });
    return output;
  } catch (e) {
    addHistory({
      kind: "tool",
      label: name,
      input: args,
      output: (e as Error).message,
      ok: false,
      durationMs: Math.round(performance.now() - started),
    });
    throw e;
  }
}

export async function sendChat(history: ChatMessage[], message: string, sessionId?: string) {
  const started = performance.now();
  try {
    let reply: string;
    let sid: string;
    if (mode() === "live") {
      const r = await api.sendChat(connectionStore.get().cfg, message, sessionId);
      reply = r.reply;
      sid = r.sessionId;
    } else {
      reply = await mock.chat(mockState(), history, message);
      sid = sessionId ?? "mock-session";
    }
    addHistory({
      kind: "chat",
      label: message.slice(0, 60),
      input: { message, session_id: sid },
      output: reply,
      ok: true,
      durationMs: Math.round(performance.now() - started),
    });
    return { reply, sessionId: sid };
  } catch (e) {
    addHistory({
      kind: "chat",
      label: message.slice(0, 60),
      input: { message, session_id: sessionId },
      output: (e as Error).message,
      ok: false,
      durationMs: Math.round(performance.now() - started),
    });
    throw e;
  }
}
