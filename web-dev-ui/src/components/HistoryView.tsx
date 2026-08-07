import { useState } from "react";
import { clearHistory, historyStore, type HistoryEntry } from "../store/history";
import { useStore } from "../store/store";
import { Badge, Button, EmptyState, Panel } from "../components/ui";

export default function HistoryView() {
  const entries = useStore(historyStore);
  const [open, setOpen] = useState<string | null>(null);

  return (
    <Panel
      title={`History`}
      right={
        <div className="flex items-center gap-2">
          <Badge tone="muted">{entries.length} run{entries.length === 1 ? "" : "s"}</Badge>
          {entries.length > 0 && (
            <Button variant="danger" onClick={clearHistory}>
              Clear
            </Button>
          )}
        </div>
      }
      bodyClassName="p-0"
    >
      {entries.length === 0 ? (
        <EmptyState
          title="No executions yet"
          hint="Run a tool or send a chat message — each call lands here with its input, output and latency."
        />
      ) : (
        <ul className="divide-y divide-white/[0.05]">
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
      <button
        onClick={props.onToggle}
        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors duration-150 hover:bg-forge-panel2/50"
      >
        <span
          className={`text-[10px] text-forge-faint transition-transform duration-200 ${props.open ? "rotate-90" : ""}`}
        >
          ▶
        </span>
        <Badge tone={entry.kind === "tool" ? "accent" : "muted"}>{entry.kind}</Badge>
        <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-forge-text">{entry.label}</span>
        <span
          className={`h-1.5 w-1.5 shrink-0 rounded-full ${
            entry.ok ? "bg-forge-ok shadow-[0_0_8px_rgba(52,211,153,0.5)]" : "bg-forge-danger shadow-[0_0_8px_rgba(248,113,113,0.5)]"
          }`}
        />
        <span className="w-16 text-right font-mono text-[11px] text-forge-muted">{entry.durationMs}ms</span>
        <span className="w-20 text-right font-mono text-[11px] text-forge-faint">{time}</span>
      </button>
      {props.open && (
        <div className="animate-fade-up grid grid-cols-1 gap-2.5 border-t border-white/[0.05] bg-forge-bg/40 px-4 py-3 md:grid-cols-2">
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
      <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-forge-faint">
        <span className={`h-1 w-1 rounded-full ${props.error ? "bg-forge-danger" : "bg-forge-accent"}`} />
        {props.title}
      </div>
      <pre
        className={`max-h-52 overflow-auto whitespace-pre-wrap break-words rounded-lg border p-2.5 font-mono text-xs leading-relaxed ${
          props.error
            ? "border-forge-danger/25 bg-forge-danger/[0.06] text-forge-danger"
            : "border-white/[0.06] bg-forge-panel2/50 text-forge-body"
        }`}
      >
        {text}
      </pre>
    </div>
  );
}
