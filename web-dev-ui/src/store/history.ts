import { loadJSON, saveJSON, Store } from "./store";

export interface HistoryEntry {
  id: string;
  kind: "tool" | "chat";
  /** tool name for kind=tool, else the user message preview */
  label: string;
  input: unknown;
  output: string;
  ok: boolean;
  durationMs: number;
  at: number;
}

const LS_KEY = "forge.webdev.history";
const MAX = 200;

export const historyStore = new Store<HistoryEntry[]>(loadJSON<HistoryEntry[]>(LS_KEY, []));

export function addHistory(entry: Omit<HistoryEntry, "id" | "at">): void {
  historyStore.update((list) => {
    const next: HistoryEntry[] = [
      { ...entry, id: crypto.randomUUID(), at: Date.now() },
      ...list,
    ].slice(0, MAX);
    saveJSON(LS_KEY, next);
    return next;
  });
}

export function clearHistory(): void {
  historyStore.set([]);
  saveJSON(LS_KEY, []);
}
