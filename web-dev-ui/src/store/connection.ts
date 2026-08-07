import type { ConnectionConfig } from "../types";
import { loadJSON, saveJSON, Store } from "./store";

export type Mode = "live" | "mock";
export type ConnStatus = "disconnected" | "connecting" | "connected" | "error";

export interface ConnectionState {
  mode: Mode;
  status: ConnStatus;
  error: string | null;
  cfg: ConnectionConfig;
}

const LS_KEY = "forge.webdev.connection";

const persisted = loadJSON<Omit<ConnectionState, "status" | "error">>(LS_KEY, {
  mode: "mock",
  cfg: { host: "127.0.0.1", port: 8789, token: "test-token" },
});

export const connectionStore = new Store<ConnectionState>({
  mode: persisted.mode,
  status: "disconnected",
  error: null,
  cfg: persisted.cfg,
});

export function setConnection(patch: Partial<ConnectionState>): void {
  connectionStore.update((s) => {
    const next = { ...s, ...patch };
    saveJSON(LS_KEY, { mode: next.mode, cfg: next.cfg });
    return next;
  });
}
