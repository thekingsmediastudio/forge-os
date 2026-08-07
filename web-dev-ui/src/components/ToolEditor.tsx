import { useEffect, useState } from "react";
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

  return (
    <Panel
      title="Tool definitions"
      right={
        <div className="flex items-center gap-2">
          {savedAt && <Badge tone="ok">applied</Badge>}
          <Badge tone={conn.mode === "mock" ? "accent" : "muted"}>
            {conn.mode === "mock" ? "mock source" : "editing local copy"}
          </Badge>
        </div>
      }
    >
      <p className="mb-3 text-xs text-forge-muted">
        Edit the OpenAI-style tool definitions below and Apply. The Tools view and the mock agent use this exact JSON,
        so you can iterate on names, descriptions and parameter schemas in ~1 second.
      </p>
      <textarea
        className="h-[52vh] w-full rounded-md border border-forge-border bg-forge-bg p-3 font-mono text-xs text-forge-text focus:border-forge-accent focus:outline-none"
        value={text}
        onChange={(e) => setText(e.target.value)}
        spellCheck={false}
      />
      {error && (
        <p className="mt-2 rounded-md border border-red-900/60 bg-red-950/30 px-3 py-2 text-xs text-red-400">{error}</p>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button variant="primary" onClick={apply}>
          Apply
        </Button>
        {conn.mode === "live" && <Button onClick={loadFromServer}>Load from server</Button>}
        <Button variant="danger" onClick={onReset}>
          Reset to sample
        </Button>
        <span className="ml-auto text-xs text-forge-muted">{tools.length} tool{tools.length === 1 ? "" : "s"}</span>
      </div>
    </Panel>
  );
}
