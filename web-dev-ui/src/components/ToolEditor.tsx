import { useEffect, useMemo, useState } from "react";
import { getTools } from "../service";
import { connectionStore } from "../store/connection";
import { useStore } from "../store/store";
import { resetTools, setTools, toolsStore, validateToolsJSON } from "../store/tools";
import type { ToolDefinition } from "../types";
import { Badge, Button, Panel } from "../components/ui";

// ToolEditor edits the tool-definition JSON that mock mode serves and that
// ToolsView renders. Edit -> Apply -> the Tools view updates immediately,
// giving a ~1s iterate loop on the tool surface (no Android rebuild).

export default function ToolEditor() {
  const conn = useStore(connectionStore);
  const tools = useStore(toolsStore);
  const [text, setText] = useState(() => JSON.stringify(tools, null, 2));
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  // Keep the editor in sync if tools change elsewhere (e.g. live refresh).
  useEffect(() => {
    setText(JSON.stringify(tools, null, 2));
  }, [tools]);

  const apply = () => {
    const err = validateToolsJSON(text);
    setError(err);
    if (err) return;
    setTools(JSON.parse(text) as ToolDefinition[]);
    setSavedAt(Date.now());
  };

  const loadFromServer = async () => {
    setError(null);
    try {
      const t = await getTools();
      setText(JSON.stringify(t, null, 2));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const onReset = () => {
    resetTools();
    setError(null);
  };

  const lineCount = useMemo(() => text.split("\n").length, [text]);
  const dirty = useMemo(() => {
    try {
      return text !== JSON.stringify(tools, null, 2);
    } catch {
      return true;
    }
  }, [text, tools]);

  return (
    <Panel
      title="Tool definitions"
      right={
        <div className="flex items-center gap-2">
          {dirty && <Badge tone="accent">modified</Badge>}
          {savedAt && !dirty && <Badge tone="ok">applied</Badge>}
          <Badge tone={conn.mode === "mock" ? "accent" : "muted"}>
            {conn.mode === "mock" ? "mock source" : "editing local copy"}
          </Badge>
        </div>
      }
      bodyClassName="flex flex-col p-0"
    >
      <p className="border-b border-white/[0.06] px-4 py-3 text-xs leading-relaxed text-forge-muted">
        Edit the OpenAI-style tool definitions below and Apply. The Tools view and the mock agent use this exact
        JSON, so you can iterate on names, descriptions and parameter schemas in ~1 second.
      </p>

      <textarea
        className="h-[52vh] w-full resize-none bg-forge-bg/50 p-4 font-mono text-xs leading-relaxed text-forge-body focus:bg-forge-bg/70 focus:outline-none"
        value={text}
        onChange={(e) => setText(e.target.value)}
        spellCheck={false}
      />

      {error && (
        <p className="animate-fade-up border-t border-forge-danger/25 bg-forge-danger/[0.08] px-4 py-2.5 text-xs leading-relaxed text-forge-danger">
          {error}
        </p>
      )}

      {/* Toolbar / footer */}
      <div className="flex flex-wrap items-center gap-2 border-t border-white/[0.06] bg-forge-panel2/40 px-4 py-3">
        <Button variant="primary" onClick={apply} disabled={!dirty}>
          Apply
        </Button>
        {conn.mode === "live" && <Button onClick={loadFromServer}>Load from server</Button>}
        <Button variant="danger" onClick={onReset}>
          Reset to sample
        </Button>
        <span className="ml-auto font-mono text-[11px] text-forge-faint">
          {tools.length} tool{tools.length === 1 ? "" : "s"} · {lineCount} lines
        </span>
      </div>
    </Panel>
  );
}
