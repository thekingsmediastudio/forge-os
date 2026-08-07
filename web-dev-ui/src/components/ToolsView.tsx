import { useEffect, useMemo, useState } from "react";
import { getTools, runTool } from "../service";
import { connectionStore } from "../store/connection";
import { useStore } from "../store/store";
import { toolsStore } from "../store/tools";
import type { ToolDefinition } from "../types";
import { Badge, EmptyState, inputCls, Panel, Spinner } from "../components/ui";
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
        title={`Tools`}
        right={
          loading ? (
            <span className="flex items-center gap-1.5 text-[11px] text-forge-accentSoft">
              <Spinner size={11} /> refreshing
            </span>
          ) : (
            <Badge tone="muted">{filtered.length} · {conn.mode}</Badge>
          )
        }
      >
        <div className="relative">
          <input
            className={`${inputCls} pl-9`}
            placeholder="Search tools…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-forge-faint">
            ⌕
          </span>
        </div>
        <ul className="mt-3 max-h-[54vh] space-y-1 overflow-y-auto pr-1">
          {filtered.map((t) => {
            const isActive = active?.function.name === t.function.name;
            return (
              <li key={t.function.name}>
                <button
                  onClick={() => {
                    setSelected(t.function.name);
                    setResult(null);
                  }}
                  className={`relative w-full rounded-lg border px-3 py-2 text-left transition-all duration-150 ${
                    isActive
                      ? "border-forge-accent/30 bg-forge-accent/[0.07]"
                      : "border-transparent hover:bg-forge-panel2/60"
                  }`}
                >
                  {/* Active accent bar */}
                  <span
                    className={`absolute left-0 top-1/2 h-[60%] w-[3px] -translate-y-1/2 rounded-full bg-accent-grad transition-opacity duration-150 ${
                      isActive ? "opacity-100" : "opacity-0"
                    }`}
                  />
                  <div className={`font-mono text-[13px] ${isActive ? "text-forge-accentSoft" : "text-forge-text"}`}>
                    {t.function.name}
                  </div>
                  <div className="mt-0.5 line-clamp-2 text-xs leading-relaxed text-forge-muted">
                    {t.function.description}
                  </div>
                </button>
              </li>
            );
          })}
          {filtered.length === 0 && (
            <li>
              <EmptyState title={`No tools match “${query}”`} hint="Try a different search term." />
            </li>
          )}
        </ul>
      </Panel>

      <Panel className="lg:col-span-3" title={active ? active.function.name : "Select a tool"}>
        {active ? (
          <div className="space-y-4">
            <p className="text-sm leading-relaxed text-forge-muted">{active.function.description}</p>
            <ToolForm tool={active} onRun={onRun} running={running} />
            {result && (
              <div
                className={`animate-fade-up overflow-hidden rounded-xl border ${
                  result.ok ? "border-forge-ok/25" : "border-forge-danger/25"
                }`}
              >
                <div
                  className={`flex items-center gap-2 border-b px-3 py-2 ${
                    result.ok
                      ? "border-forge-ok/20 bg-forge-ok/[0.08]"
                      : "border-forge-danger/20 bg-forge-danger/[0.08]"
                  }`}
                >
                  <Badge tone={result.ok ? "ok" : "err"}>{result.ok ? "✓ ok" : "✕ error"}</Badge>
                  <span className="font-mono text-[11px] text-forge-faint">{active.function.name}</span>
                </div>
                <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words bg-forge-bg/60 p-3 font-mono text-xs leading-relaxed text-forge-body">
                  {result.text}
                </pre>
              </div>
            )}
          </div>
        ) : (
          <EmptyState
            title="Pick a tool"
            hint="Select a tool from the list to inspect its schema and run it with arguments."
          />
        )}
      </Panel>
    </div>
  );
}
