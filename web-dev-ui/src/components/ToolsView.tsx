import { useEffect, useMemo, useState } from "react";
import { getTools, runTool } from "../service";
import { connectionStore } from "../store/connection";
import { useStore } from "../store/store";
import { toolsStore } from "../store/tools";
import type { ToolDefinition } from "../types";
import { Badge, inputCls, Panel } from "../components/ui";
import ToolForm from "./ToolForm";

export default function ToolsView() {
  const conn = useStore(connectionStore);
  const tools = useStore(toolsStore);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [loading, setLoading] = useState(false);

  // In live mode, refresh the tool list from the server when connected.
  useEffect(() => {
    if (conn.mode === "live" && conn.status === "connected") {
      setLoading(true);
      getTools()
        .catch(() => undefined)
        .finally(() => setLoading(false));
    }
  }, [conn.mode, conn.status]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return tools;
    return tools.filter(
      (t) =>
        t.function.name.toLowerCase().includes(q) ||
        (t.function.description ?? "").toLowerCase().includes(q)
    );
  }, [tools, query]);

  const active: ToolDefinition | null =
    filtered.find((t) => t.function.name === selected) ?? filtered[0] ?? null;

  const onRun = async (args: Record<string, unknown>) => {
    if (!active) return;
    setRunning(true);
    setResult(null);
    try {
      const out = await runTool(active.function.name, args);
      setResult({ ok: true, text: out });
    } catch (e) {
      setResult({ ok: false, text: (e as Error).message });
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-5">
      <Panel
        className="lg:col-span-2"
        title={`Tools (${filtered.length})`}
        right={loading ? <Badge tone="accent">refreshing…</Badge> : <Badge>{conn.mode}</Badge>}
      >
        <input
          className={inputCls}
          placeholder="Search tools…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <ul className="mt-3 max-h-[52vh] space-y-1 overflow-y-auto pr-1">
          {filtered.map((t) => (
            <li key={t.function.name}>
              <button
                onClick={() => {
                  setSelected(t.function.name);
                  setResult(null);
                }}
                className={`w-full rounded-lg border px-3 py-2 text-left transition-all duration-150 ${
                  active?.function.name === t.function.name
                    ? "border-forge-accent/30 bg-forge-accent/5"
                    : "border-transparent hover:bg-forge-panel2/60"
                }`}
              >
                <div className="font-mono text-sm text-forge-text">{t.function.name}</div>
                <div className="mt-0.5 line-clamp-2 text-xs text-forge-muted">{t.function.description}</div>
              </button>
            </li>
          ))}
          {filtered.length === 0 && (
            <li className="px-2 py-6 text-center text-sm text-forge-muted">No tools match “{query}”.</li>
          )}
        </ul>
      </Panel>

      <Panel className="lg:col-span-3" title={active ? active.function.name : "Select a tool"}>
        {active ? (
          <div className="space-y-4">
            <p className="text-sm text-forge-muted">{active.function.description}</p>
            <ToolForm tool={active} onRun={onRun} running={running} />
            {result && (
              <div
                className={`rounded-lg border px-3 py-2 ${
                  result.ok ? "border-forge-ok/20 bg-forge-ok/5" : "border-red-500/20 bg-red-500/5"
                }`}
              >
                <div className="mb-1">
                  <Badge tone={result.ok ? "ok" : "err"}>{result.ok ? "ok" : "error"}</Badge>
                </div>
                <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words font-mono text-xs text-forge-text">
                  {result.text}
                </pre>
              </div>
            )}
          </div>
        ) : (
          <p className="text-sm text-forge-muted">Pick a tool from the list to inspect and run it.</p>
        )}
      </Panel>
    </div>
  );
}
