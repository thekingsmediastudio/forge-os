import type { ToolDefinition } from "../types";
import { SAMPLE_TOOLS } from "../mock/sampleTools";
import { loadJSON, saveJSON, Store } from "./store";

// Editable tool-definition source of truth for mock mode. In live mode the
// tools come from the server; this store is what ToolEditor edits and what
// mock mode serves.

const LS_KEY = "forge.webdev.tools";

export const toolsStore = new Store<ToolDefinition[]>(
  loadJSON<ToolDefinition[]>(LS_KEY, SAMPLE_TOOLS)
);

export function setTools(tools: ToolDefinition[]): void {
  toolsStore.set(tools);
  saveJSON(LS_KEY, tools);
}

export function resetTools(): void {
  setTools(SAMPLE_TOOLS);
}

/** Validate a JSON string as a ToolDefinition[]; returns error message or null. */
export function validateToolsJSON(text: string): string | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    return `Invalid JSON: ${(e as Error).message}`;
  }
  if (!Array.isArray(parsed)) return "Top-level value must be an array of tool definitions.";
  for (let i = 0; i < parsed.length; i++) {
    const t = parsed[i] as Partial<ToolDefinition>;
    if (!t || typeof t !== "object") return `Entry ${i} is not an object.`;
    if (!t.function || typeof t.function.name !== "string" || !t.function.name) {
      return `Entry ${i} is missing function.name.`;
    }
    if (!t.function.parameters || typeof t.function.parameters !== "object") {
      return `Entry ${i} (${t.function.name}) is missing function.parameters.`;
    }
  }
  return null;
}
