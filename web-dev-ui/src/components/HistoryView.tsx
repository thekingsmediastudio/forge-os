import { useState } from "react";
import { clearHistory, historyStore, type HistoryEntry } from "../store/history";
import { useStore } from "../store/store";
import { Badge, Button, Panel } from "../components/ui";

export default function HistoryView() {
  const entries = useStore(historyStore);
  const [open, setOpen] = useState<string | null>(null);

  return (
    <Panel
      title={`History (${entries.length})`}
      right={
        entries.length > 0 ? (
          <Button variant="danger" onClick={clearHistory}>
            Clear
          </Button>
        ) : undefined
      }
    >
      {entries.length === 0 ? (
        <p className="py-8 text-center text-sm text-forge-muted">
          No executions yet. Run a tool or send a chat message.
        </p>
      ) : (
        <ul className="divide-y divide-forge-border">
          {entries.map((e) => (
            <Row key={e.id} entry={e} open={open === e.id} onToggle={() => setOpen(open === e.id ? null : e.id)} />
          ))}
        </ul>
      )}
    </Panel>
  );
}

function Row(props: { entry: HistoryEntry; open: boolean; onToggle: () => void }) {
  const { entry } = props;
  const time = new Date(entry.at).toLocaleTimeString();
  return (
    <li>
      <button onClick={props.onToggle} className="flex w-full items-center gap-3 px-1 py-2.5 text-left hover:bg-forge-border/20">
        <Badge tone={entry.kind === "tool" ? "accent" : "muted"}>{entry.kind}</Badge>
        <span className="min-w-0 flex-1 truncate font-mono text-sm text-forge-text">{entry.label}</span>
        <Badge tone={entry.ok ? "ok" : "err"}>{entry.ok ? "ok" : "err"}</Badge>
        <span className="w-16 text-right font-mono text-[11px] text-forge-muted">{entry.durationMs}ms</span>
        <span className="w-20 text-right font-mono text-[11px] text-forge-muted">{time}</span>
      </button>
      {props.open && (
        <div className="grid grid-cols-1 gap-2 px-2 pb-3 md:grid-cols-2">
          <JsonBlock title="Input" value={entry.input} />
          <JsonBlock title="Output" value={entry.output} error={!entry.ok} />
        </div>
      )}
    </li>
  );
}

function JsonBlock(props: { title: string; value: unknown; error?: boolean }) {
  let text: string;
  if (typeof props.value === "string") {
    text = props.value;
  } else {
    try {
      text = JSON.stringify(props.value, null, 2);
    } catch {
      text = String(props.value);
    }
  }
  return (
    <div>
      <div className="mb-1 text-[11px] font-medium text-forge-muted">{props.title}</div>
      <pre
        className={`max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-md border p-2 font-mono text-xs ${
          props.error ? "border-red-900/60 bg-red-950/20 text-red-300" : "border-forge-border bg-forge-bg text-forge-text"
        }`}
      >
        {text}
      </pre>
    </div>
  );
}
